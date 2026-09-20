package com.github.lechandonga.bridge.bridge;

import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.chain.SimulatedChain;
import com.github.lechandonga.bridge.crypto.IdentityRegistry;
import com.github.lechandonga.bridge.crypto.Signatures;
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
 *
 * <p>安全语义：</p>
 * <ul>
 *   <li>凭证与发送方身份绑定：必须用发送方登记私钥签发，仅凭消息内容无法伪造；
 *       可区分凭证损坏（{@link RejectionReason#INVALID_SIGNATURE}）、
 *       凭证与发送方不一致（{@link RejectionReason#CREDENTIAL_SENDER_MISMATCH}）、
 *       发送方未登记（{@link RejectionReason#UNREGISTERED_SENDER}）。</li>
 *   <li>重入防护：同一处理路径（同线程）上的嵌套 submit/retry 被识别为
 *       {@link RejectionReason#REENTRANT_CALL}，不产生半更新状态、不重复记账；
 *       不持有跨调用的排他锁，正常并发投递不会被阻塞。</li>
 *   <li>重新执行前必须重新完成一次完整来源验证（区块头授权 + 正统性 + Merkle 包含），
 *       验证失败给出可区分原因且不改变目标链状态。</li>
 *   <li>重组只撤销真正落在被回滚区间、且尚未最终确定的执行；FINALIZED 永不回滚。</li>
 * </ul>
 */
public class Bridge {

    private static final Logger LOG = Logger.getLogger(Bridge.class.getName());

    /** 执行基础消耗 + 按载荷长度计费。 */
    static final long BASE_GAS = 100;

    private final SimulatedChain targetChain;
    private final LightClient lightClient;
    private final IdentityRegistry identities;

    private record Record(CrossChainMessage message, MerkleProof proof, BlockHeader header,
                          MessageStatus status, long executedAtHeight, int remainingRetries) {
        Record with(MessageStatus s, long h, int r) {
            return new Record(message, proof, header, s, h, r);
        }
    }

    private final Map<String, Record> records = new ConcurrentHashMap<>();

    /**
     * 重入检测：同一处理路径（同线程）在一次 submit/retry 尚未结束时再次进入。
     * 仅做线程内计数，不是跨线程互斥，因此不会阻塞其他线程的并发投递，也不会死锁。
     */
    private final ThreadLocal<Boolean> inProgress = ThreadLocal.withInitial(() -> false);

    /**
     * 执行窗口探针（默认关闭）：在状态已应用但区块尚未固化时触发，
     * 用于确定性地复现重入时序；生产中保持默认空实现即可。
     */
    @FunctionalInterface
    public interface ExecutionHook {
        /** 提交路径：全部校验通过、账务应用后、commitBlock 前回调。 */
        void beforeCommit(String messageId);
    }

    private volatile ExecutionHook hook = id -> {};

    public void setExecutionHook(ExecutionHook hook) {
        this.hook = hook == null ? id -> {} : hook;
    }

    public Bridge(SimulatedChain targetChain, LightClient lightClient, IdentityRegistry identities) {
        this.targetChain = targetChain;
        this.lightClient = lightClient;
        this.identities = identities;
    }

    /**
     * 提交并尝试执行一条跨链消息。
     * 依次校验：重入、字段完整性、链标识、有效期、发送方凭证、重放防护、完整来源验证，然后执行。
     */
    public synchronized BridgeResult submit(CrossChainMessage message, MerkleProof proof, BlockHeader sourceHeader) {
        if (inProgress.get()) {
            return reject(message == null ? null : message.id(),
                    RejectionReason.REENTRANT_CALL, "检测到同一路径重入: 上一次投递尚未结束");
        }
        inProgress.set(true);
        try {
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
            // 4. 发送方凭证（身份绑定的不可伪造凭证）
            BridgeResult credentialFailure = verifySenderCredential(message);
            if (credentialFailure != null) {
                return credentialFailure;
            }
            // 5. 重放防护与标识碰撞（重组撤销后链上 hash 已随回滚消失，允许重新提交）
            Optional<String> existingHash = targetChain.get(stateKey(id) + ":hash");
            if (existingHash.isPresent()) {
                if (!existingHash.get().equals(message.contentHash())) {
                    return reject(id, RejectionReason.ID_COLLISION,
                            "消息标识碰撞: id=" + id + " 内容哈希不一致");
                }
                return reject(id, RejectionReason.REPLAY, "消息已投递过: id=" + id);
            }
            // 6. 完整来源验证：区块头（授权门槛 + 正统性）+ Merkle 包含证明
            BridgeResult sourceFailure = verifySource(message, proof, sourceHeader);
            if (sourceFailure != null) {
                return sourceFailure;
            }

            // 7. 登记并执行
            Record record = new Record(message, proof, sourceHeader, MessageStatus.VERIFIED, -1,
                    message.retryPolicy().maxRetries());
            return execute(record);
        } finally {
            inProgress.remove();
        }
    }

    /**
     * 重试处于 RETRYABLE 状态的消息。
     * 重新执行前必须重新完成一次完整来源验证：旧证明/区块头若已失效（来源重组、
     * 授权不足等）则拒绝，且不消耗重试预算、不改变目标链状态。
     */
    public synchronized BridgeResult retry(String messageId) {
        if (inProgress.get()) {
            return reject(messageId, RejectionReason.REENTRANT_CALL,
                    "检测到同一路径重入: 上一次重试尚未结束");
        }
        inProgress.set(true);
        try {
            Record record = records.get(messageId);
            if (record == null) {
                return BridgeResult.of(messageId, MessageStatus.REJECTED, "消息不存在或未提交过");
            }
            if (record.status() != MessageStatus.RETRYABLE) {
                return BridgeResult.of(messageId, record.status(), "消息不在可重试状态");
            }
            // 重新执行前的完整来源验证（使用首次提交时留存的材料）
            BridgeResult sourceFailure = verifySource(record.message(), record.proof(), record.header());
            if (sourceFailure != null) {
                LOG.info("msg=" + messageId + " 重试前来源验证失败: 原因=" + sourceFailure.reason()
                        + " 依据=" + sourceFailure.detail() + " 目标链状态不变");
                return sourceFailure;
            }
            if (record.remainingRetries() <= 0) {
                return finalizeFailure(record);
            }
            Record updated = record.with(MessageStatus.RETRYABLE, -1, record.remainingRetries() - 1);
            return execute(updated);
        } finally {
            inProgress.remove();
        }
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
     * 处理目标链重组：链状态已回滚，将执行高度真正落在被回滚区间内、
     * 且尚未最终确定的消息标记为 REVERTED；其余消息（包括已最终确定的）保持原状态。
     *
     * @param revertedDepth 实际回滚的区块数（{@link SimulatedChain#reorg} 的返回值）
     */
    public synchronized void onReorg(long revertedDepth) {
        long head = targetChain.headHeight();
        long rollbackFrom = head + Math.max(0, revertedDepth); // 被回滚区间的起始高度（不含）
        records.replaceAll((id, record) -> {
            if (record.status() == MessageStatus.EXECUTED
                    && record.executedAtHeight() > head
                    && record.executedAtHeight() <= rollbackFrom
                    && record.executedAtHeight() > targetChain.finalizedHeight()) {
                LOG.info("msg=" + id + " 重组撤销: 执行高度=" + record.executedAtHeight()
                        + " 当前链头=" + head + " 判定=REVERTED");
                return record.with(MessageStatus.REVERTED, -1, record.remainingRetries());
            }
            if (record.status() == MessageStatus.EXECUTED
                    && record.executedAtHeight() <= targetChain.finalizedHeight()) {
                LOG.info("msg=" + id + " 重组触及但已最终确定: 执行高度="
                        + record.executedAtHeight() + " 判定=FINALIZED（不回滚）");
            }
            return record;
        });
    }

    /**
     * 原子地执行目标链重组并更新消息状态：回滚与撤销判定在桥接端点的同一把锁内完成，
     * 与并发投递/重试串行化，保证"投递 vs 重组"无论以何种顺序交错，最终状态唯一。
     *
     * @return 实际回滚的区块数；-1 表示重组触及最终确定高度而被链拒绝（状态不变）
     */
    public synchronized long handleReorg(long depth) {
        long reverted = targetChain.reorg(depth);
        if (reverted > 0) {
            onReorg(reverted);
        }
        return reverted;
    }

    // ---- 校验 ----

    /** 发送方凭证校验：区分未登记、凭证损坏/伪造、凭证与发送方不一致。 */
    private BridgeResult verifySenderCredential(CrossChainMessage message) {
        String id = message.id();
        if (!identities.isRegistered(message.sender())) {
            return reject(id, RejectionReason.UNREGISTERED_SENDER,
                    "发送方身份未登记: sender=" + message.sender());
        }
        if (!Signatures.isWellFormed(message.signature())) {
            return reject(id, RejectionReason.INVALID_SIGNATURE,
                    "凭证无法解析或已被篡改: sender=" + message.sender());
        }
        if (identities.verifyFor(message.sender(), message.contentHash(), message.signature())) {
            return null;
        }
        Optional<String> actual = identities.findSigner(message.contentHash(), message.signature());
        if (actual.isPresent()) {
            return reject(id, RejectionReason.CREDENTIAL_SENDER_MISMATCH,
                    "凭证由 " + actual.get() + " 签发，与声明发送方 " + message.sender() + " 不一致");
        }
        return reject(id, RejectionReason.INVALID_SIGNATURE,
                "凭证签名校验失败（内容或签名被篡改）: sender=" + message.sender());
    }

    /** 完整来源验证：区块头授权/正统性 + Merkle 包含 + 证明值与内容一致。 */
    private BridgeResult verifySource(CrossChainMessage message, MerkleProof proof, BlockHeader sourceHeader) {
        String id = message.id();
        if (sourceHeader == null) {
            return reject(id, RejectionReason.PROOF_NOT_COVERING, "缺少来源链区块头");
        }
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
        if (!message.contentHash().equals(proof.value())) {
            return reject(id, RejectionReason.PROOF_NOT_COVERING, "证明值与消息内容哈希不一致");
        }
        return null;
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
            return finalizeFailure(record);
        }

        // 原子执行：账务与状态一并写入，探针在固化前触发（此时若同路径重入将被拒绝，
        // 但本线程的执行随后正常提交，不会回滚已完成的这一次，也不会被计入两次）。
        applyPayload(message);
        hook.beforeCommit(id);
        long height = commitState(id, MessageStatus.EXECUTED, message);
        records.put(id, record.with(MessageStatus.EXECUTED, height, record.remainingRetries()));
        LOG.info("msg=" + id + " 执行成功: 高度=" + height + " 判定=EXECUTED");
        return BridgeResult.of(id, MessageStatus.EXECUTED, "执行成功，高度=" + height);
    }

    private BridgeResult finalizeFailure(Record record) {
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
