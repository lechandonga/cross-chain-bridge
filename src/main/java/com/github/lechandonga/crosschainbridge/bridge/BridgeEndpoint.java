package com.github.lechandonga.crosschainbridge.bridge;

import com.github.lechandonga.crosschainbridge.chain.ChainState;
import com.github.lechandonga.crosschainbridge.chain.SimulatedBlockchain;
import com.github.lechandonga.crosschainbridge.crypto.MerkleTreeBuilder;
import com.github.lechandonga.crosschainbridge.crypto.MessageSigner;
import com.github.lechandonga.crosschainbridge.logging.DecisionLog;
import com.github.lechandonga.crosschainbridge.lightclient.LightClient;
import com.github.lechandonga.crosschainbridge.model.*;

import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 桥接端点：一条链一个。
 *
 * <p>发起端：{@link #originate} 做消息字段/链标识/有效期校验并由验证者集合签名；
 * {@link #dispatchBatch} 将签名消息打包出块并为每条消息生成 Merkle 包含证明。
 *
 * <p>目标端：{@link #submit} 依次执行
 * 基本校验（字段、链标识、有效期）→ 重放检查 → 轻客户端证明验证 → 原子执行；
 * {@link #retry} 处理资源限额导致的可重试终态转换；
 * {@link #handleReorg} 区分“未最终被撤销”与“已最终不得回滚”。
 */
public final class BridgeEndpoint {

    private final String name;
    private final SimulatedBlockchain chain;
    private final ChainState state;
    private final LightClient lightClient;
    private final TargetMailbox mailbox;
    private final DecisionLog decisionLog;
    private final List<KeyPair> validatorKeys;

    /** 消息标识 -> 状态记录（两端共用：发起端记录 INCLUDED，目标端记录完整生命周期）。 */
    private final Map<String, MessageState> tracked = new ConcurrentHashMap<>();
    /** 入站消息标识 -> 待处理证明（重试时重新验证）。 */
    private final Map<String, MessageProof> pendingProofs = new ConcurrentHashMap<>();
    /** 消息标识 -> 首次提交时间（用于重试时间窗口判定）。 */
    private final Map<String, Long> submittedAt = new ConcurrentHashMap<>();

    /** 重试前可对消息追加的手续费上限（模拟费用市场/发送方补足 gas 费）。 */
    private final Map<String, Long> feeTopUp = new ConcurrentHashMap<>();

    /** 来源链（对端）已同步到本端轻客户端的最新最终高度，用于重组判定。 */
    private volatile long sourceFinalizedHeight = 0L;

    /** 目标端执行临界区：同一端点的提交/执行串行化，杜绝半更新与接口重入。 */
    private final ReentrantLock executionLock = new ReentrantLock();

    /** 撤销补偿：消息因重组被撤销时，回滚它此前已应用的账务效果。 */
    public interface RevocationCompensator {
        void compensate(BridgeMessage message, ChainState targetState);
    }

    /** 默认补偿：把 {@code transfer:from,to,amount} 反向应用（to 扣回、from 补回）。 */
    public static final RevocationCompensator REVERSE_TRANSFER = (message, targetState) -> {
        String payload = message.payload();
        String[] args = payload.substring(payload.indexOf(':') + 1).split(",");
        if (args.length == 3) {
            long amount = Long.parseLong(args[2].trim());
            targetState.debit(args[1].trim(), amount);
            targetState.credit(args[0].trim(), amount);
        }
    };

    private volatile RevocationCompensator revocationCompensator = REVERSE_TRANSFER;

    public void setRevocationCompensator(RevocationCompensator compensator) {
        this.revocationCompensator = compensator;
    }

    public BridgeEndpoint(String name,
                          SimulatedBlockchain chain,
                          ChainState state,
                          LightClient lightClient,
                          TargetMailbox mailbox,
                          DecisionLog decisionLog,
                          List<KeyPair> validatorKeys) {
        this.name = name;
        this.chain = chain;
        this.state = state;
        this.lightClient = lightClient;
        this.mailbox = mailbox;
        this.decisionLog = decisionLog;
        this.validatorKeys = List.copyOf(validatorKeys);
    }

    public String name() {
        return name;
    }

    public SimulatedBlockchain chain() {
        return chain;
    }

    public ChainState state() {
        return state;
    }

    public TargetMailbox mailbox() {
        return mailbox;
    }

    public DecisionLog decisionLog() {
        return decisionLog;
    }

    // ------------------------------------------------------------------
    // 发起端
    // ------------------------------------------------------------------

    /** 发起端：校验消息字段/链标识/有效期并签名。 */
    public SignedMessage originate(BridgeMessage message) {
        return originate(message, System.currentTimeMillis());
    }

    /** 发起端：校验消息字段/链标识/有效期并签名（注入判定时刻）。 */
    public SignedMessage originate(BridgeMessage message, long nowMillis) {
        FailureReason reason = validateMessage(message, nowMillis);
        if (reason != null) {
            decisionLog.log(message == null ? null : message.messageId(),
                    "REJECTED", reason.name(), "origination validation on " + name);
            throw new MessageValidationException(reason,
                    "message rejected on originate: " + reason);
        }
        SignedMessage signed = MessageSigner.sign(message, validatorKeys);
        MessageState ms = tracked.computeIfAbsent(message.messageId(), MessageState::new);
        ms.transitionTo(MessageStatus.INCLUDED, FailureReason.NONE, nowMillis);
        decisionLog.log(message.messageId(), "SIGNED", FailureReason.NONE.name(),
                "signed by " + signed.signatures().size() + " validators on " + name);
        return signed;
    }

    /** 基本字段校验：缺失字段 / 链标识不匹配 / 超出有效期，三类原因可区分。 */
    public FailureReason validateMessage(BridgeMessage m, long nowMillis) {
        if (m == null
                || m.messageId() == null || m.messageId().isBlank()
                || m.payload() == null || m.payload().isBlank()) {
            return FailureReason.NULL_OR_BLANK_FIELD;
        }
        if (m.sourceChain() == null || m.targetChain() == null) {
            return FailureReason.NULL_OR_BLANK_FIELD;
        }
        if (m.sourceChain() == m.targetChain()) {
            return FailureReason.INVALID_CHAIN_ID;
        }
        if (m.sourceChain().counterpart() != m.targetChain()) {
            return FailureReason.INVALID_CHAIN_ID;
        }
        if (m.retryPolicy() == null) {
            return FailureReason.NULL_OR_BLANK_FIELD;
        }
        if (m.validUntilMillis() <= m.createdAtMillis()) {
            return FailureReason.NULL_OR_BLANK_FIELD;
        }
        if (nowMillis > m.validUntilMillis()) {
            return FailureReason.MESSAGE_EXPIRED;
        }
        return null;
    }

    /**
     * 发起端：把一批已签名消息打包进一个区块，同步给对端轻客户端，
     * 并为每条消息生成 Merkle 包含证明。
     */
    public List<MessageProof> dispatchBatch(List<SignedMessage> signed, BridgeEndpoint targetEndpoint) {
        List<byte[]> hashes = signed.stream().map(SignedMessage::messageHash).toList();
        BlockHeader header = chain.produceBlock(hashes);
        MerkleTreeBuilder tree = new MerkleTreeBuilder(hashes);
        List<MessageProof> proofs = new ArrayList<>(signed.size());
        for (int i = 0; i < signed.size(); i++) {
            SignedMessage sm = signed.get(i);
            MerkleProof merkle = tree.proofFor(i, header.height());
            proofs.add(new MessageProof(sm, merkle, header));
            MessageState ms = tracked.computeIfAbsent(sm.message().messageId(), MessageState::new);
            ms.transitionTo(MessageStatus.INCLUDED, FailureReason.NONE, System.currentTimeMillis());
            ms.setBlockHeight(header.height());
        }
        // 新头喂给目标端的轻客户端
        targetEndpoint.lightClient.ingestHeader(header);
        return List.copyOf(proofs);
    }

    // ------------------------------------------------------------------
    // 目标端：提交
    // ------------------------------------------------------------------

    /** 目标端：提交消息证明（含基本校验与重放检查），返回处理结果。 */
    public SubmitResult submit(MessageProof proof, long nowMillis) {
        // 接口重入保护：同端点递归/并发重入直接拒绝，且不得产生任何状态变化
        if (executionLock.isHeldByCurrentThread()) {
            String id = proof != null && proof.signedMessage() != null
                    ? proof.signedMessage().message().messageId() : null;
            decisionLog.log(id, "REJECTED", FailureReason.REENTRANCY_DETECTED.name(),
                    "re-entrant submit call on " + name);
            return SubmitResult.rejected(id, FailureReason.REENTRANCY_DETECTED,
                    "endpoint re-entered while executing");
        }
        executionLock.lock();
        try {
            return doSubmit(proof, nowMillis);
        } finally {
            executionLock.unlock();
        }
    }

    private SubmitResult doSubmit(MessageProof proof, long nowMillis) {
        if (proof == null || proof.signedMessage() == null || proof.signedMessage().message() == null) {
            decisionLog.log(null, "REJECTED", FailureReason.NULL_OR_BLANK_FIELD.name(),
                    "null proof submitted to " + name);
            return SubmitResult.rejected(null, FailureReason.NULL_OR_BLANK_FIELD, "proof is null");
        }
        BridgeMessage message = proof.signedMessage().message();
        String id = message.messageId();

        TargetMailbox.Origin origin = new TargetMailbox.Origin(
                message.sourceChain().name(), message.nonce());

        // 已成功执行的消息：来源一致 => 幂等拒绝重复；来源不一致 => 标识碰撞
        if (mailbox.isExecuted(id)) {
            if (!origin.equals(mailbox.executedOrigin(id))) {
                decisionLog.log(id, "REJECTED", FailureReason.MESSAGE_ID_COLLISION.name(),
                        "executed id reused by a different origin on " + name);
                return SubmitResult.rejected(id, FailureReason.MESSAGE_ID_COLLISION,
                        "message id collision with executed message");
            }
            decisionLog.log(id, "REJECTED", FailureReason.DUPLICATE_MESSAGE.name(),
                    "message already executed on " + name);
            return SubmitResult.rejected(id, FailureReason.DUPLICATE_MESSAGE,
                    "message already executed");
        }

        // 基本校验
        FailureReason basic = validateMessage(message, nowMillis);
        if (basic != null) {
            return rejectWithoutStateChange(id, basic,
                    "basic validation failed: " + basic, nowMillis);
        }
        // 消息必须确实发往本链
        if (message.targetChain() != chain.chainId()) {
            return rejectWithoutStateChange(id, FailureReason.TARGET_CHAIN_MISMATCH,
                    "target=" + message.targetChain() + " but endpoint serves " + chain.chainId(),
                    nowMillis);
        }
        // 重复并发投递 / 跨链标识碰撞：第一个占用成功者继续，其余按原因拒绝
        TargetMailbox.ReserveOutcome outcome = mailbox.reserve(id, origin);
        if (outcome == TargetMailbox.ReserveOutcome.DUPLICATE) {
            decisionLog.log(id, "REJECTED", FailureReason.DUPLICATE_MESSAGE.name(),
                    "concurrent or repeated delivery while slot occupied on " + name);
            return SubmitResult.rejected(id, FailureReason.DUPLICATE_MESSAGE,
                    "duplicate concurrent delivery");
        }
        if (outcome == TargetMailbox.ReserveOutcome.COLLISION) {
            decisionLog.log(id, "REJECTED", FailureReason.MESSAGE_ID_COLLISION.name(),
                    "same messageId claimed by different origin (source/nonce) on " + name);
            return SubmitResult.rejected(id, FailureReason.MESSAGE_ID_COLLISION,
                    "message id collision with a different source message");
        }

        try {
            // 轻客户端证明验证（覆盖性 / 头与状态根 / 验证者仲裁）
            FailureReason proofFailure = lightClient.verify(proof);
            if (proofFailure != null) {
                mailbox.release(id); // 验证失败：不占用信箱，目标链状态不变
                decisionLog.log(id, "REJECTED", proofFailure.name(),
                        "light client verification failed on " + name);
                MessageState ms = tracked.computeIfAbsent(id, MessageState::new);
                ms.transitionTo(MessageStatus.REJECTED, proofFailure, nowMillis);
                return SubmitResult.rejected(id, proofFailure,
                        "proof verification failed: " + proofFailure);
            }

            pendingProofs.put(id, proof);
            submittedAt.putIfAbsent(id, nowMillis);
            MessageState state = tracked.computeIfAbsent(id, MessageState::new);
            state.transitionTo(MessageStatus.VERIFIED, FailureReason.NONE, nowMillis);
            state.setBlockHeight(proof.sourceHeader().height());
            state.setFinalized(proof.sourceHeader().finalized());
            decisionLog.log(id, "VERIFIED", FailureReason.NONE.name(),
                    "proof verified against header h=" + proof.sourceHeader().height());

            return executeVerified(message, proof, state, nowMillis);
        } catch (RuntimeException ex) {
            mailbox.release(id);
            decisionLog.log(id, "REJECTED", FailureReason.EXECUTION_ERROR.name(),
                    "unexpected error: " + ex.getMessage());
            throw ex;
        }
    }

    private SubmitResult executeVerified(BridgeMessage message, MessageProof proof,
                                         MessageState ms, long nowMillis) {
        String id = message.messageId();
        long effectiveFeeLimit = message.feeLimit()
                + feeTopUp.getOrDefault(id, 0L);

        // 资源限额：声明消耗超过（原始上限 + 追加额度） -> 可重试，消息不丢失、状态不变
        if (message.requiredGas() > effectiveFeeLimit) {
            ms.transitionTo(MessageStatus.RETRYABLE, FailureReason.RESOURCE_LIMIT_EXCEEDED, nowMillis);
            decisionLog.log(id, "RETRYABLE", FailureReason.RESOURCE_LIMIT_EXCEEDED.name(),
                    "requiredGas=" + message.requiredGas() + " > effectiveFeeLimit=" + effectiveFeeLimit
                            + "; retries left=" + (message.retryPolicy().maxRetries() - ms.retryCount()));
            // 可重试消息保留信箱占用，防止窗口期内被重复执行
            return new SubmitResult(id, false, MessageStatus.RETRYABLE,
                    FailureReason.RESOURCE_LIMIT_EXCEEDED, ms.retryCount(),
                    "resource limit exceeded; retryable");
        }

        ChainState.ApplyOutcome outcome = this.state.applyPayloadAtomically(message.payload());
        if (outcome == ChainState.ApplyOutcome.APPLIED) {
            mailbox.markExecuted(id);
            ms.transitionTo(MessageStatus.EXECUTED, FailureReason.NONE, nowMillis);
            decisionLog.log(id, "EXECUTED", FailureReason.NONE.name(),
                    "payload applied atomically on " + name + " h=" + proof.sourceHeader().height());
            return SubmitResult.accepted(id, MessageStatus.EXECUTED);
        }
        // 执行失败（余额不足/载荷畸形）属于确定性终态，释放占用，不落任何账务
        mailbox.release(id);
        FailureReason reason = outcome == ChainState.ApplyOutcome.INSUFFICIENT_FUNDS
                ? FailureReason.EXECUTION_ERROR : FailureReason.NULL_OR_BLANK_FIELD;
        ms.transitionTo(MessageStatus.FAILED_FINAL, reason, nowMillis);
        decisionLog.log(id, "REJECTED", reason.name(),
                "execution outcome=" + outcome + " on " + name);
        return SubmitResult.rejected(id, reason, "execution failed: " + outcome);
    }

    // ------------------------------------------------------------------
    // 目标端：重试
    // ------------------------------------------------------------------

    /** 对 RETRYABLE 消息重试一次；窗口过期/次数耗尽给出确定终态。 */
    public SubmitResult retry(String messageId, long nowMillis) {
        if (executionLock.isHeldByCurrentThread()) {
            decisionLog.log(messageId, "REJECTED", FailureReason.REENTRANCY_DETECTED.name(),
                    "re-entrant retry call on " + name);
            return SubmitResult.rejected(messageId, FailureReason.REENTRANCY_DETECTED,
                    "endpoint re-entered while retrying");
        }
        executionLock.lock();
        try {
            return doRetry(messageId, nowMillis);
        } finally {
            executionLock.unlock();
        }
    }

    private SubmitResult doRetry(String messageId, long nowMillis) {
        MessageState ms = tracked.get(messageId);
        MessageProof proof = pendingProofs.get(messageId);
        if (ms == null || proof == null) {
            decisionLog.log(messageId, "REJECTED", FailureReason.NULL_OR_BLANK_FIELD.name(),
                    "no retryable message known on " + name);
            return SubmitResult.rejected(messageId, FailureReason.NULL_OR_BLANK_FIELD,
                    "message not found");
        }
        BridgeMessage message = proof.signedMessage().message();
        RetryPolicy policy = message.retryPolicy();

        // 重试时间窗口（自首次提交起）过期 => 确定终态
        long firstSubmit = submittedAt.getOrDefault(messageId, nowMillis);
        if (nowMillis > firstSubmit + policy.retryWindowMillis()) {
            ms.transitionTo(MessageStatus.FAILED_FINAL, FailureReason.RETRY_WINDOW_EXPIRED, nowMillis);
            mailbox.release(messageId);
            decisionLog.log(messageId, "FAILED_FINAL", FailureReason.RETRY_WINDOW_EXPIRED.name(),
                    "retry window elapsed at " + nowMillis);
            return new SubmitResult(messageId, false, MessageStatus.FAILED_FINAL,
                    FailureReason.RETRY_WINDOW_EXPIRED, ms.retryCount(), "retry window expired");
        }
        // 消息自身有效期也必须仍然有效
        if (nowMillis > message.validUntilMillis()) {
            ms.transitionTo(MessageStatus.EXPIRED, FailureReason.MESSAGE_EXPIRED, nowMillis);
            mailbox.release(messageId);
            decisionLog.log(messageId, "EXPIRED", FailureReason.MESSAGE_EXPIRED.name(),
                    "message validity ended before successful retry");
            return new SubmitResult(messageId, false, MessageStatus.EXPIRED,
                    FailureReason.MESSAGE_EXPIRED, ms.retryCount(), "message expired");
        }
        // 次数耗尽 => 确定终态
        if (ms.retryCount() >= policy.maxRetries()) {
            ms.transitionTo(MessageStatus.FAILED_FINAL, FailureReason.RETRIES_EXHAUSTED, nowMillis);
            mailbox.release(messageId);
            decisionLog.log(messageId, "FAILED_FINAL", FailureReason.RETRIES_EXHAUSTED.name(),
                    "maxRetries=" + policy.maxRetries() + " exhausted");
            return new SubmitResult(messageId, false, MessageStatus.FAILED_FINAL,
                    FailureReason.RETRIES_EXHAUSTED, ms.retryCount(), "retries exhausted");
        }

        // 重试前重新验证证明（来源链可能已重组）
        FailureReason reverify = lightClient.verify(proof);
        if (reverify != null) {
            ms.transitionTo(MessageStatus.REVOKED, FailureReason.REORG_REVOKED_UNFINALIZED, nowMillis);
            mailbox.release(messageId);
            decisionLog.log(messageId, "REVOKED", FailureReason.REORG_REVOKED_UNFINALIZED.name(),
                    "proof no longer valid on retry: " + reverify);
            return new SubmitResult(messageId, false, MessageStatus.REVOKED,
                    FailureReason.REORG_REVOKED_UNFINALIZED, ms.retryCount(),
                    "revoked after source change");
        }

        ms.incrementRetry();
        decisionLog.log(messageId, "RETRY", FailureReason.RESOURCE_LIMIT_EXCEEDED.name(),
                "retry attempt " + ms.retryCount() + "/" + policy.maxRetries());
        return executeVerified(message, proof, ms, nowMillis);
    }

    // ------------------------------------------------------------------
    // 重组处理
    // ------------------------------------------------------------------

    /**
     * 来源链重组通知：
     * 已提交但尚未最终确定的消息撤销/待重新验证；
     * 已最终确定的消息保留，若调用方试图回滚最终区块则明确拒绝。
     */
    public ReorgResult handleReorg(long forkHeight, long nowMillis) {
        List<String> revoked = new ArrayList<>();
        List<String> finalizedKept = new ArrayList<>();
        boolean rollbackAttempted = forkHeight < sourceFinalizedHeight;

        executionLock.lock();
        try {
            for (Map.Entry<String, MessageState> entry : tracked.entrySet()) {
                MessageState ms = entry.getValue();
                String id = entry.getKey();
                boolean executed = mailbox.isExecuted(id);
                if (ms.blockHeight() > forkHeight && !ms.finalized()) {
                    // 落在被替换区间且未最终确定：无论是否已在目标端执行，一律撤销。
                    // 已执行而未最终的效果必须能回滚，否则来源链重组即等价于非法放行。
                    boolean hadExecuted = executed;
                    if (hadExecuted) {
                        MessageProof oldProof = pendingProofs.get(id);
                        if (oldProof != null) {
                            revocationCompensator.compensate(oldProof.signedMessage().message(), state);
                        }
                    }
                    ms.transitionTo(MessageStatus.REVOKED,
                            FailureReason.REORG_REVOKED_UNFINALIZED, nowMillis);
                    mailbox.forgetAfterReorg(id);
                    pendingProofs.remove(id);
                    submittedAt.remove(id);
                    feeTopUp.remove(id);
                    revoked.add(id);
                    decisionLog.log(id, hadExecuted ? "REVOKED-EXECUTED" : "REVOKED",
                            FailureReason.REORG_REVOKED_UNFINALIZED.name(),
                            "unfinalized message above fork h=" + forkHeight
                                    + " revoked (hadExecuted=" + hadExecuted + ")");
                } else if (executed && ms.finalized()) {
                    finalizedKept.add(id);
                    decisionLog.log(id, "FINALIZED-KEPT", FailureReason.NONE.name(),
                            "finalized execution preserved through fork h=" + forkHeight);
                }
            }
        } finally {
            executionLock.unlock();
        }
        FailureReason reason = rollbackAttempted
                ? FailureReason.FINALIZED_BLOCK_ROLLBACK_REJECTED
                : FailureReason.NONE;
        if (rollbackAttempted) {
            decisionLog.log(null, "REORG-REJECTED",
                    FailureReason.FINALIZED_BLOCK_ROLLBACK_REJECTED.name(),
                    "fork h=" + forkHeight + " would roll back source-finalized h="
                            + sourceFinalizedHeight);
        }
        return new ReorgResult(forkHeight, List.copyOf(revoked), List.copyOf(finalizedKept),
                rollbackAttempted, reason);
    }

    /** 通知某高度已最终确定（由来源链最终性推进后调用）。 */
    public void markFinalizedUpTo(long height, long nowMillis) {
        executionLock.lock();
        try {
            this.sourceFinalizedHeight = Math.max(this.sourceFinalizedHeight, height);
            for (MessageState ms : tracked.values()) {
                if (ms.blockHeight() >= 0 && ms.blockHeight() <= height) {
                    ms.setFinalized(true);
                }
            }
        } finally {
            executionLock.unlock();
        }
    }

    public long sourceFinalizedHeight() {
        return sourceFinalizedHeight;
    }

    /** 为一条可重试消息追加手续费/资源额度，使下一次重试可能越过资源上限。 */
    public void addFeeTopUp(String messageId, long extra) {
        if (extra < 0) {
            throw new IllegalArgumentException("negative top-up");
        }
        feeTopUp.merge(messageId, extra, Long::sum);
    }

    /**
     * 来源链重组后，把当前规范链 [fromHeight, head] 的区块头
     * 重新喂给对端端点的轻客户端（同高度旧头会被替换），
     * 并把来源链最终高度同步给对端。
     */
    public void syncCanonicalHeaders(long fromHeight, BridgeEndpoint targetEndpoint) {
        for (long h = fromHeight; h <= chain.headHeight(); h++) {
            targetEndpoint.lightClient.ingestHeader(chain.headerAt(h));
        }
        targetEndpoint.sourceFinalizedHeight = chain.finalizedHeight();
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public MessageState stateOf(String messageId) {
        return tracked.get(messageId);
    }

    public Collection<MessageState> trackedStates() {
        return List.copyOf(tracked.values());
    }

    private SubmitResult rejectWithoutStateChange(String id, FailureReason reason,
                                                  String detail, long nowMillis) {
        // 字段/链标识/有效期类拒绝：不登记状态、不碰信箱、不改账务
        decisionLog.log(id, "REJECTED", reason.name(), detail + " on " + name);
        return SubmitResult.rejected(id, reason, detail);
    }
}
