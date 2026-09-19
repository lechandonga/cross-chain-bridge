package com.github.lechandonga.crosschainbridge.bridge;

import com.github.lechandonga.crosschainbridge.model.MessageStatus;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标链重放防护信箱：记录已见消息标识及其当前阶段。
 * 所有方法线程安全。
 *
 * <p>两阶段协议保证并发下只执行一次：
 * <pre>
 *   reserve(id, origin) -> FIRST 首次见到，调用方独占执行权
 *                         DUPLICATE 同一消息重复投递
 *                         COLLISION 同 id 但来源指纹不同（跨链标识碰撞）
 *   markExecuted(id)     执行成功落定
 *   release(id)          执行失败释放占用，允许后续重试/重投
 * </pre>
 */
public final class TargetMailbox {

    /** 预留结果，三种情形可区分。 */
    public enum ReserveOutcome {
        FIRST,
        DUPLICATE,
        COLLISION
    }

    /** 消息的来源指纹：来源链 + nonce（跨链消息标识碰撞检测使用）。 */
    public record Origin(String sourceChain, long nonce) {
    }

    private final Set<String> reserved = ConcurrentHashMap.newKeySet();
    private final Set<String> executed = ConcurrentHashMap.newKeySet();
    private final Map<String, MessageStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, Origin> origins = new ConcurrentHashMap<>();

    public TargetMailbox() {
    }

    /**
     * 尝试登记一条消息。
     *
     * @return FIRST 首次；DUPLICATE 同 id 同来源的重复投递；COLLISION 同 id 不同来源
     */
    public ReserveOutcome reserve(String messageId, Origin origin) {
        synchronized (reserved) {
            Origin existing = origins.get(messageId);
            if (existing != null && !existing.equals(origin)) {
                return ReserveOutcome.COLLISION;
            }
            boolean first = reserved.add(messageId);
            if (first) {
                origins.put(messageId, origin);
                statuses.put(messageId, MessageStatus.SUBMITTED);
                return ReserveOutcome.FIRST;
            }
            return ReserveOutcome.DUPLICATE;
        }
    }

    /** 兼容旧调用的无来源预留（仅测试/占位使用）。 */
    public boolean reserve(String messageId) {
        return reserve(messageId, new Origin("unknown", 0L)) == ReserveOutcome.FIRST;
    }

    public void markExecuted(String messageId) {
        executed.add(messageId);
        statuses.put(messageId, MessageStatus.EXECUTED);
    }

    /** 已执行消息的来源指纹；id 已执行但来源不一致即碰撞。 */
    public Origin executedOrigin(String messageId) {
        return origins.get(messageId);
    }

    /** 释放未成功的占用（重试可重试消息时不需要释放，重试复用同一槽位）。 */
    public void release(String messageId) {
        synchronized (reserved) {
            reserved.remove(messageId);
            statuses.remove(messageId);
            // 来源指纹保留至彻底遗忘，避免释放-重投窗口中的碰撞漏判
        }
    }

    /**
     * 来源链重组撤销后彻底遗忘一条消息：同时清除占用、已执行标记与来源指纹，
     * 使该消息在新链上重新包含、重新验证通过后可以再次执行。
     */
    public void forgetAfterReorg(String messageId) {
        synchronized (reserved) {
            reserved.remove(messageId);
            executed.remove(messageId);
            statuses.remove(messageId);
            origins.remove(messageId);
        }
    }

    public boolean isExecuted(String messageId) {
        return executed.contains(messageId);
    }

    public boolean isReserved(String messageId) {
        return reserved.contains(messageId);
    }

    public MessageStatus statusOf(String messageId) {
        return statuses.get(messageId);
    }

    public Set<String> executedIds() {
        return Set.copyOf(executed);
    }
}
