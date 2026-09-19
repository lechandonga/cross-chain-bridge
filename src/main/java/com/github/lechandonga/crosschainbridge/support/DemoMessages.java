package com.github.lechandonga.crosschainbridge.support;

import com.github.lechandonga.crosschainbridge.model.BridgeMessage;
import com.github.lechandonga.crosschainbridge.model.ChainId;
import com.github.lechandonga.crosschainbridge.model.RetryPolicy;

import java.util.concurrent.atomic.AtomicLong;

/** main 演示用消息工厂（与测试工厂分离，避免 main 依赖 test classpath）。 */
public final class DemoMessages {

    private static final AtomicLong NONCE = new AtomicLong(5_000L);

    private DemoMessages() {
    }

    public static BridgeMessage transfer(String id, ChainId source, ChainId target,
                                         String from, String to, long amount,
                                         long now, long validityMillis) {
        return new BridgeMessage(id, source, target, "transfer:" + from + "," + to + "," + amount,
                NONCE.incrementAndGet(), now, now + validityMillis, 10L, 100L,
                new RetryPolicy(3, 100_000L));
    }

    public static BridgeMessage withGas(String id, BridgeMessage base, long requiredGas, long feeLimit) {
        return new BridgeMessage(id, base.sourceChain(), base.targetChain(), base.payload(),
                base.nonce(), base.createdAtMillis(), base.validUntilMillis(),
                requiredGas, feeLimit, base.retryPolicy());
    }
}
