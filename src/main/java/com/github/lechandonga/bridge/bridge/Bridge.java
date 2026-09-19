package com.github.lechandonga.bridge.bridge;

import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.chain.SimulatedChain;
import com.github.lechandonga.bridge.lightclient.LightClient;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 部署在目标链上的桥接端点。
 * 所有状态变更在 synchronized 方法内原子完成，接口重入不会产生半更新状态。
 * 消息状态写入链状态（key: msg:&lt;id&gt;），重组回滚时自动撤销。
 */
public class Bridge {

    private static final Logger LOG = Logger.getLogger(Bridge.class.getName());

    /** 执行基础消耗 + 按载荷长度计费。 */
    static final long BASE_GAS = 100;

    private final SimulatedChain targetChain;
    private final LightClient lightClient;

    private record Record(CrossChainMessage message, MerkleProof proof, BlockHeader header,
                          MessageStatus status, long executedAtHeight, int remainingRetries) {
        Record with(MessageStatus s, long h, int r) {
            return new Record(message, proof, header, s, h, r);
        }
    }

    private final Map<String, Record> records = new ConcurrentHashMap<>();

    public Bridge(SimulatedChain targetChain, LightClient lightClient) {
        this.targetChain = targetChain;
        this.lightClient = lightClient;
    }

    /**
     * 提交并尝试执行一条跨链消息。
     * 依次校验：字段完整性、链标识、有效期、签名、重放防护、轻客户端证明，然后执行。
     */
    public synchronized BridgeResult submit(CrossChainMessage message, MerkleProof proof, BlockHeader sourceHeader) {
        String id = message.id();

        // 1. 字段完整性
        if (isBlank(id) || isBlank(message.sourceChain()) || isBlank(message.targetChain())
                || isBlank(message.sender()) || isBlank(message.payload()) || message.retryPolicy() == null) {
            return reject(id, RejectionReason.MISSING_FIELD, "必填字段缺失");
        }
        // 2. 链标识
        if (!message.targetChain().equals(targetChain.chainId())) {
            return reject(id, RejectionReason.CHAIN_ID_MISMATCH,
                    "目标链不匹配: 声明=" + message.targetChain() + " 实际=" + targetChain.chainId());
        }
        // 3. 有效期（以目标链高度为时钟）
        long now = targetChain.headHeight();
        if (message.validUntil() < now) {
            return reject(id, RejectionReason.EXPIRED,
                    "消息已过期: validUntil=" + message.validUntil() + " 当前高度=" + now);
        }
        if (message.validFrom() > now) {
            return reject(id, RejectionReason.NOT_YET_VALID,
                    "消息尚未生效: validFrom=" + message.validFrom() + " 当前高度=" + now);
        }
        // 4. 签名
        String expectedSig = "sig:" + message.sender() + ":" + message.contentHash();
        if (!expectedSig.equals(message.signature())) {
            return reject(id, RejectionReason.INVALID_SIGNATURE, "签名与消息内容不匹配");
        }
        // 5. 重放防护与标识碰撞
        Optional<String> existingHash = targetChain.get(stateKey(id) + ":hash");
        if (existingHash.isPresent()) {
            if (!existingHash.get().equals(message.contentHash())) {
                return reject(id, RejectionReason.ID_COLLISION,
                        "消息标识碰撞: id=" + id + " 内容哈希不一致");
            }
            return reject(id, RejectionReason.REPLAY, "消息已投递过: id=" + id);
        }
        // 6. 轻客户端验证：区块头 + 包含证明
        LightClient.VerificationResult headerResult = lightClient.verifyHeader(sourceHeader);
        if (!headerResult.ok()) {
            return reject(id, headerResult.reason(), "区块头验证失败: " + headerResult.reason());
        }
        String outboxKey = "outbox:" + id;
        LightClient.VerificationResult inclusion =
                lightClient.verifyInclusion(sourceHeader, proof, outboxKey);
        if (!inclusion.ok()) {
            return reject(id, inclusion.reason(), "包含证明验证失败: " + inclusion.reason());
        }
        // 证明中的值必须与消息内容哈希一致（证明确实覆盖该消息）
        if (!message.contentHash().equals(proof.value())) {
            return reject(id, RejectionReason.PROOF_NOT_COVERING,
                    "证明值与消息内容哈希不一致");
        }

        // 7. 登记并执行
        Record record = new Record(message, proof, sourceHeader, MessageStatus.VERIFIED, -1,
                message.retryPolicy().maxRetries());
        return execute(record);
    }

    /** 重试处于 RETRYABLE 状态的消息。 */
    public synchronized BridgeResult retry(String messageId) {
        Record record = records.get(messageId);
        if (record == null || record.status() != MessageStatus.RETRYABLE) {
            return BridgeResult.of(messageId, record == null ? MessageStatus.REJECTED : record.status(),
                    "消息不在可重试状态");
        }
        if (record.remainingRetries() <= 0) {
            return finalize_failure(record);
        }
        Record updated = record.with(MessageStatus.RETRYABLE, -1, record.remainingRetries() - 1);
        return execute(updated);
    }

    /** 查询消息当前状态（EXECUTED 且所在高度已最终确定时报告 FINALIZED）。 */
    public synchronized Optional<MessageStatus> statusOf(String messageId) {
        Record record = records.get(messageId);
        if (record == null) {
            return Optional.empty();
        }
        if (record.status() == MessageStatus.EXECUTED
                && record.executedAtHeight() <= targetChain.finalizedHeight()) {
            return Optional.of(MessageStatus.FINALIZED);
        }
        return Optional.of(record.status());
    }

    /**
     * 处理目标链重组：链状态已回滚，将未最终确定的已执行消息标记为 REVERTED，
     * 可重新提交验证；已最终确定的消息不受影响（链拒绝回滚到最终确定高度以下）。
     */
    public synchronized void onReorg(long revertedDepth) {
        long head = targetChain.headHeight();
        records.replaceAll((id, record) -> {
            if (record.status() == MessageStatus.EXECUTED && record.executedAtHeight() > head) {
                LOG.info("msg=" + id + " 重组撤销: 执行高度=" + record.executedAtHeight()
                        + " 当前链头=" + head + " 判定=REVERTED");
                return record.with(MessageStatus.REVERTED, -1, record.remainingRetries());
            }
            return record;
        });
    }

    // ---- 内部 ----

    private BridgeResult execute(Record record) {
        CrossChainMessage message = record.message();
        String id = message.id();
        long gasCost = BASE_GAS + message.payload().length();

        if (message.gasLimit() < gasCost) {
            if (record.remainingRetries() > 0) {
                records.put(id, record.with(MessageStatus.RETRYABLE, -1, record.remainingRetries()));
                writeState(id, MessageStatus.RETRYABLE, message);
                LOG.info("msg=" + id + " 资源超限: gasLimit=" + message.gasLimit()
                        + " 需要=" + gasCost + " 判定=RETRYABLE 剩余重试=" + record.remainingRetries());
                return BridgeResult.of(id, MessageStatus.RETRYABLE,
                        "执行消耗超限: gasLimit=" + message.gasLimit() + " 需要=" + gasCost);
            }
            return finalize_failure(record);
        }

        // 原子执行：账务与状态一并写入，随后固化到新区块
        applyPayload(message);
        long height = commitState(id, MessageStatus.EXECUTED, message);
        records.put(id, record.with(MessageStatus.EXECUTED, height, record.remainingRetries()));
        LOG.info("msg=" + id + " 执行成功: 高度=" + height + " 判定=EXECUTED");
        return BridgeResult.of(id, MessageStatus.EXECUTED, "执行成功，高度=" + height);
    }

    private BridgeResult finalize_failure(Record record) {
        String id = record.message().id();
        records.put(id, record.with(MessageStatus.FAILED_FINAL, -1, 0));
        writeState(id, MessageStatus.FAILED_FINAL, record.message());
        LOG.info("msg=" + id + " 重试耗尽: 判定=FAILED_FINAL 原因=" + RejectionReason.RETRIES_EXHAUSTED);
        return new BridgeResult(id, MessageStatus.FAILED_FINAL, RejectionReason.RETRIES_EXHAUSTED,
                "重试耗尽，消息进入确定终态");
    }

    /** 应用载荷：transfer:<account>:<amount>，并收取手续费。 */
    private void applyPayload(CrossChainMessage message) {
        String[] parts = message.payload().split(":");
        if (parts.length == 3 && parts[0].equals("transfer")) {
            String account = parts[1];
            long amount = Long.parseLong(parts[2]);
            long balance = targetChain.get("balance:" + account).map(Long::parseLong).orElse(0L);
            targetChain.applyNow("balance:" + account, String.valueOf(balance + amount));
        }
        long fees = targetChain.get("fees:collector").map(Long::parseLong).orElse(0L);
        targetChain.applyNow("fees:collector", String.valueOf(fees + message.fee()));
    }

    private void writeState(String id, MessageStatus status, CrossChainMessage message) {
        targetChain.applyNow(stateKey(id), status.name());
        targetChain.applyNow(stateKey(id) + ":hash", message.contentHash());
    }

    private long commitState(String id, MessageStatus status, CrossChainMessage message) {
        writeState(id, status, message);
        return targetChain.commitBlock().height();
    }

    private String stateKey(String id) {
        return "msg:" + id;
    }

    private BridgeResult reject(String id, RejectionReason reason, String detail) {
        LOG.info("msg=" + id + " 拒绝: 原因=" + reason + " 依据=" + detail);
        return BridgeResult.rejected(id, reason, detail);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
