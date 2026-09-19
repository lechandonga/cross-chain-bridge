package com.github.lechandonga.crosschainbridge.support;

import com.github.lechandonga.crosschainbridge.model.BridgeMessage;
import com.github.lechandonga.crosschainbridge.model.ChainId;
import com.github.lechandonga.crosschainbridge.model.RetryPolicy;

/** 测试消息构造辅助。 */
public final class TestMessages {

    public static final long T0 = 1_000_000L;
    public static final long DEFAULT_VALIDITY = 100_000L;

    private TestMessages() {
    }

    private static final java.util.concurrent.atomic.AtomicLong NONCE_SEQ =
            new java.util.concurrent.atomic.AtomicLong(1_000L);

    public static BridgeMessage valid(String id, ChainId source, ChainId target, String payload, long now) {
        return new BridgeMessage(id, source, target, payload, NONCE_SEQ.incrementAndGet(),
                now, now + DEFAULT_VALIDITY, 10L, 100L, RetryPolicy.DEFAULT);
    }

    public static BridgeMessage transfer(String id, ChainId source, ChainId target,
                                         String from, String to, long amount, long now) {
        return valid(id, source, target, "transfer:" + from + "," + to + "," + amount, now);
    }

    public static BridgeMessage withGasAndFee(String id, ChainId source, ChainId target,
                                              String payload, long requiredGas, long feeLimit,
                                              int maxRetries, long retryWindow, long now) {
        return new BridgeMessage(id, source, target, payload, Math.abs(id.hashCode()),
                now, now + DEFAULT_VALIDITY, requiredGas, feeLimit,
                new RetryPolicy(maxRetries, retryWindow));
    }

    public static BridgeMessage expired(String id, ChainId source, ChainId target, long now) {
        return new BridgeMessage(id, source, target, "transfer:a,b,1", 1L,
                now - DEFAULT_VALIDITY * 2, now - 1L, 10L, 100L, RetryPolicy.DEFAULT);
    }

    public static BridgeMessage blankId(ChainId source, ChainId target, long now) {
        return new BridgeMessage("  ", source, target, "transfer:a,b,1", 1L,
                now, now + DEFAULT_VALIDITY, 10L, 100L, RetryPolicy.DEFAULT);
    }

    public static BridgeMessage wrongTarget(String id, ChainId source, ChainId declaredTarget, long now) {
        return new BridgeMessage(id, source, declaredTarget, "transfer:a,b,1", 1L,
                now, now + DEFAULT_VALIDITY, 10L, 100L, RetryPolicy.DEFAULT);
    }
}
