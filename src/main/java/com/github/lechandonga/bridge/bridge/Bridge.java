package com.github.lechandonga.bridge.bridge;

import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.chain.SimulatedChain;
import com.github.lechandonga.bridge.crypto.CredentialVerifier;
import com.github.lechandonga.bridge.lightclient.LightClient;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 部署在目标链上的桥接端点。
 *
 * <p>每条消息持有独立的 {@link ReentrantLock}：
 * <ul>
 *   <li>同线程在一次提交/重试未结束时从同一路径再次进入 →
 *       {@link RejectionReason#REENTRANCY_DETECTED}，不产生半更新状态、不重复记账；</li>
 *   <li>不同线程的并发投递互斥串行，第二条命中原有的重放/状态防护，不会长时间互相阻塞。</li>
 * </ul>
 *
 * <p>每一次执行（初次提交、RETRYABLE 重试、REVERTED 后重新提交）之前都重新完成完整的
 * 来源验证：凭证、来源区块头（含撤销检查）、包含证明、证明值与消息内容一致。
 */
public class Bridge {

    private static final Logger LOG = Logger.getLogger(Bridge.class.getName());

    /** 执行基础消耗 + 按载荷长度计费。 */
    static final long BASE_GAS = 100;

    private final SimulatedChain targetChain;
    private final LightClient lightClient;
    private final CredentialVerifier credentials;

    private record Record(CrossChainMessage message, MerkleProof proof, BlockHeader header,
                          MessageStatus status, long executedAtHeight, int remainingRetries) {
        Record with(MerkleProof proof, BlockHeader header, MessageStatus status, long h, int r) {
            return new Record(message, proof, header, status, h, r);
        }
    }

    private final Map<String, Record> records = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> messageLocks = new ConcurrentHashMap<>();
    /**
     * 重组与投递之间的串行屏障。投递在消息锁内短暂登记在途计数；重组在该锁上等待所有
     * 在途投递整体结束后再开始。锁顺序始终为：消息锁 → reorgLock（计数登记），
     * 重组只持有 reorgLock 而不申请消息锁，因此不会死锁，投递临界区极短也不会被长时间阻塞。
     */
    private final Object reorgLock = new Object();
    private int inFlight = 0;

    /**
     * 账务临界区锁：不同消息的"载荷记账 + 状态写入 + 区块提交 + 账本登记"必须整体串行。
     * 消息级锁只互斥同一消息，无法保护跨消息共享的记账键（balance:&lt;account&gt;、
     * fees:collector）上的读-改-写；没有这把锁，并发执行会丢失更新，且一个消息的
     * 写可能混入另一个消息的区块快照，重组回滚后账务与状态撕裂。
     * 临界区内只有内存操作（微秒级），执行切面等可能较慢的段落都在临界区之外，
     * 互不相关的并发投递不会因此长时间互相等待。
     * 锁顺序恒为：消息锁 → ledgerLock（→ 链对象监视器），重组路径不持有 ledgerLock，不会死锁。
     */
    private final Object ledgerLock = new Object();

    /** 测试用执行切面：在真正执行（可能记账）之前回调，用于稳定复现重入时序。 */
    private volatile Consumer<CrossChainMessage> beforeExecuteHook = m -> { };

    public Bridge(SimulatedChain targetChain, LightClient lightClient, CredentialVerifier credentials) {
        this.targetChain = targetChain;
        this.lightClient = lightClient;
        this.credentials = credentials;
    }

    /**
     * 提交并尝试执行一条跨链消息。
     * 依次校验：字段完整性、链标识、有效期、重入、重放防护、凭证、轻客户端证明，然后执行。
     */
    public BridgeResult submit(CrossChainMessage message, MerkleProof proof, BlockHeader sourceHeader) {
        String id = message == null ? null : message.id();

        // 1. 字段完整性（不触碰任何状态，也无需锁）
        if (message == null || isBlank(id) || isBlank(message.sourceChain())
                || isBlank(message.targetChain()) || isBlank(message.sender())
                || isBlank(message.payload()) || message.retryPolicy() == null) {
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

        // 4. 消息级互斥 + 同线程重入识别
        ReentrantLock lock = messageLocks.computeIfAbsent(id, k -> new ReentrantLock());
        if (lock.isHeldByCurrentThread()) {
            return reject(id, RejectionReason.REENTRANCY_DETECTED,
                    "提交路径在同一次处理结束前被重入");
        }
        lock.lock();
        enterInFlight();
        try {
            // 5. 重放防护与标识碰撞（以链上状态为准，重组回滚后可再次提交）
            Optional<String> existingHash = targetChain.get(stateKey(id) + ":hash");
            if (existingHash.isPresent()) {
                if (!existingHash.get().equals(message.contentHash())) {
                    return reject(id, RejectionReason.ID_COLLISION,
                            "消息标识碰撞: id=" + id + " 内容哈希不一致");
                }
                return reject(id, RejectionReason.REPLAY, "消息已投递过: id=" + id);
            }
            // 6. 完整来源验证：发送方凭证 + 来源区块头 + 包含证明
            BridgeResult sourceFailure = verifySource(message, proof, sourceHeader);
            if (sourceFailure != null) {
                return sourceFailure;
            }

            // 7. 登记并执行
            Record record = new Record(message, proof, sourceHeader, MessageStatus.VERIFIED, -1,
                    message.retryPolicy().maxRetries());
            return execute(record);
        } finally {
            exitInFlight();
            lock.unlock();
        }
    }

    /** 重试处于 RETRYABLE 状态的消息：使用登记时保存的来源材料重新做完整来源验证。 */
    public BridgeResult retry(String messageId) {
        return retryWith(messageId, null, null);
    }

    /**
     * 携带最新来源材料重试：每一次重试都必须重新完成完整来源验证，
     * 证明已失效/被替换时给出可区分原因且不改变目标链状态。
     */
    public BridgeResult retry(String messageId, MerkleProof freshProof, BlockHeader freshHeader) {
        return retryWith(messageId, freshProof, freshHeader);
    }

    private BridgeResult retryWith(String messageId, MerkleProof freshProof, BlockHeader freshHeader) {
        if (isBlank(messageId)) {
            return reject(messageId, RejectionReason.MISSING_FIELD, "重试缺少消息标识");
        }
        ReentrantLock lock = messageLocks.computeIfAbsent(messageId, k -> new ReentrantLock());
        if (lock.isHeldByCurrentThread()) {
            return reject(messageId, RejectionReason.REENTRANCY_DETECTED,
                    "重试路径在同一次处理结束前被重入");
        }
        lock.lock();
        enterInFlight();
        try {
            Record record = records.get(messageId);
            if (record == null || record.status() != MessageStatus.RETRYABLE) {
                return BridgeResult.of(messageId,
                        record == null ? MessageStatus.REJECTED : record.status(),
                        "消息不在可重试状态");
            }

            // 每一次重新执行前都必须重新完成完整的来源验证
            MerkleProof proof = freshProof != null ? freshProof : record.proof();
            BlockHeader header = freshHeader != null ? freshHeader : record.header();
            BridgeResult sourceFailure = verifySource(record.message(), proof, header);
            if (sourceFailure != null) {
                // 验证不通过：状态、重试次数、账务全部不变
                LOG.info("msg=" + messageId + " 重试前来源验证未通过: 原因="
                        + sourceFailure.reason() + " 判定保持=" + record.status());
                return sourceFailure;
            }

            if (record.remainingRetries() <= 0) {
                return finalizeFailure(record.with(proof, header, MessageStatus.RETRYABLE, -1, 0));
            }
            Record updated = record.with(proof, header, MessageStatus.RETRYABLE, -1,
                    record.remainingRetries() - 1);
            return execute(updated);
        } finally {
            exitInFlight();
            lock.unlock();
        }
    }

    /** 查询消息当前状态（EXECUTED 且所在高度已最终确定时报告 FINALIZED）。 */
    public Optional<MessageStatus> statusOf(String messageId) {
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
     * 处理目标链重组：仅撤销真正落在被回滚范围（执行高度 &gt; 当前链头）
     * 且尚未最终确定的 EXECUTED 消息；其余消息保持原状态。
     * 已最终确定的消息在任何重组下都不会被回滚。
     */
    public void onReorg(long revertedDepth) {
        long head = targetChain.headHeight();
        long finalized = targetChain.finalizedHeight();
        records.replaceAll((id, record) -> {
            if (record.status() == MessageStatus.EXECUTED
                    && record.executedAtHeight() > head
                    && record.executedAtHeight() > finalized) {
                LOG.info("msg=" + id + " 重组撤销: 执行高度=" + record.executedAtHeight()
                        + " 当前链头=" + head + " 最终确定高度=" + finalized + " 判定=REVERTED");
                return record.with(record.proof(), record.header(),
                        MessageStatus.REVERTED, -1, record.remainingRetries());
            }
            if (record.status() == MessageStatus.EXECUTED
                    && record.executedAtHeight() > head && record.executedAtHeight() <= finalized) {
                // 理论上链会拒绝触及最终确定区块的重组；此处保持防御性不变更
                LOG.warning("msg=" + id + " 重组试图触及已最终确定执行: 执行高度="
                        + record.executedAtHeight() + " 最终确定高度=" + finalized + " 保持不变");
            }
            return record;
        });
    }

    /**
     * 原子地执行目标链重组并通知桥接端点：等待所有在途投递整体结束后，链回滚与受影响
     * 消息的状态撤销在同一串行区内完成，消除"链已回滚但桥接尚未收到通知/投递刚写入"的
     * 交错窗口。链拒绝重组（返回 -1/0）时桥接状态不变。
     *
     * @return 目标链实际回滚的区块数；触及已最终确定区块时返回 -1
     */
    public long handleReorg(long depth) {
        synchronized (reorgLock) {
            boolean interrupted = false;
            while (inFlight > 0) {
                try {
                    reorgLock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            long reverted = targetChain.reorg(depth);
            if (reverted > 0) {
                onReorg(reverted);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return reverted;
        }
    }

    /** 登记一个在途投递（调用方已持有该消息锁）。 */
    private void enterInFlight() {
        synchronized (reorgLock) {
            inFlight++;
        }
    }

    /** 结束一个在途投递；若已无在途操作则唤醒等待的重组。 */
    private void exitInFlight() {
        synchronized (reorgLock) {
            inFlight--;
            if (inFlight == 0) {
                reorgLock.notifyAll();
            }
        }
    }

    /** 注册测试用执行切面。 */
    public void setBeforeExecuteHook(Consumer<CrossChainMessage> hook) {
        this.beforeExecuteHook = hook == null ? m -> { } : hook;
    }

    // ---- 内部 ----

    /**
     * 完整来源验证：发送方凭证（身份绑定）→ 来源区块头（含撤销/重复/伪造口径）
     * → Merkle 包含证明 → 证明值与消息内容一致。任何一项失败都返回可区分的拒绝结果。
     */
    private BridgeResult verifySource(CrossChainMessage message, MerkleProof proof, BlockHeader sourceHeader) {
        String id = message.id();

        RejectionReason credentialFailure =
                credentials.verify(message.sender(), message.contentHash(), message.signature());
        if (credentialFailure != null) {
            return reject(id, credentialFailure,
                    "发送方凭证校验失败: sender=" + message.sender() + " 原因=" + credentialFailure);
        }

        LightClient.VerificationResult headerResult = lightClient.verifyHeader(sourceHeader);
        if (!headerResult.ok()) {
            return reject(id, headerResult.reason(),
                    "区块头验证失败: " + headerResult.reason());
        }

        String outboxKey = "outbox:" + id;
        LightClient.VerificationResult inclusion =
                lightClient.verifyInclusion(sourceHeader, proof, outboxKey);
        if (!inclusion.ok()) {
            return reject(id, inclusion.reason(),
                    "包含证明验证失败: " + inclusion.reason());
        }

        if (!message.contentHash().equals(proof.value())) {
            return reject(id, RejectionReason.PROOF_NOT_COVERING,
                    "证明值与消息内容哈希不一致");
        }
        return null;
    }

    private BridgeResult execute(Record record) {
        CrossChainMessage message = record.message();
        String id = message.id();
        long gasCost = BASE_GAS + message.payload().length();

        if (message.gasLimit() < gasCost) {
            if (record.remainingRetries() > 0) {
                synchronized (ledgerLock) {
                    records.put(id, record.with(record.proof(), record.header(),
                            MessageStatus.RETRYABLE, -1, record.remainingRetries()));
                    writeState(id, MessageStatus.RETRYABLE, message);
                }
                LOG.info("msg=" + id + " 资源超限: gasLimit=" + message.gasLimit()
                        + " 需要=" + gasCost + " 判定=RETRYABLE 剩余重试=" + record.remainingRetries());
                return BridgeResult.of(id, MessageStatus.RETRYABLE,
                        "执行消耗超限: gasLimit=" + message.gasLimit() + " 需要=" + gasCost);
            }
            return finalizeFailure(record);
        }

        // 测试切面在任何账务写入之前触发：切面内重入必须被识别，且不得发生任何记账
        beforeExecuteHook.accept(message);

        // 原子执行：账务与状态一并写入，随后固化到新区块；整段在账务临界区内完成，
        // 保证共享记账键不丢失更新、每条消息的写入恰好落在自己的区块快照里
        synchronized (ledgerLock) {
            applyPayload(message);
            long height = commitState(id, MessageStatus.EXECUTED, message);
            records.put(id, record.with(record.proof(), record.header(),
                    MessageStatus.EXECUTED, height, record.remainingRetries()));
            LOG.info("msg=" + id + " 执行成功: 高度=" + height + " 判定=EXECUTED");
            return BridgeResult.of(id, MessageStatus.EXECUTED, "执行成功，高度=" + height);
        }
    }

    private BridgeResult finalizeFailure(Record record) {
        String id = record.message().id();
        synchronized (ledgerLock) {
            records.put(id, record.with(record.proof(), record.header(),
                    MessageStatus.FAILED_FINAL, -1, 0));
            writeState(id, MessageStatus.FAILED_FINAL, record.message());
        }
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
