package com.github.lechandonga.crosschainbridge.bridge;

import com.github.lechandonga.crosschainbridge.model.MessageProof;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 中继器：把来源端生成的证明按给定序列并发投递给目标端。
 *
 * <p>deliveryOrder 是证明列表的下标序列：
 * 可以乱序（如 [2,0,1]），也可以包含重复下标（如 [0,0,1]）以模拟重复投递。
 * 并发提交的线性化由 {@link BridgeEndpoint} 的执行锁与信箱保证。
 */
public final class Relayer {

    private final BridgeEndpoint target;
    private final List<MessageProof> proofs;
    private final ExecutorService executor;

    public Relayer(BridgeEndpoint source,
                   BridgeEndpoint target,
                   List<MessageProof> proofs,
                   ExecutorService executor) {
        this.target = target;
        this.proofs = List.copyOf(proofs);
        this.executor = executor;
    }

    public List<MessageProof> proofs() {
        return proofs;
    }

    /** 按给定投递序列并发投递（序列可乱序、可含重复），等待全部完成。 */
    public List<SubmitResult> deliver(List<Integer> deliveryOrder, long nowMillis) {
        List<Future<SubmitResult>> futures = new ArrayList<>(deliveryOrder.size());
        for (int index : deliveryOrder) {
            MessageProof proof = proofs.get(index);
            futures.add(executor.submit(() -> target.submit(proof, nowMillis)));
        }
        List<SubmitResult> results = new ArrayList<>(deliveryOrder.size());
        for (Future<SubmitResult> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                throw new IllegalStateException("delivery failed", e);
            }
        }
        return Collections.unmodifiableList(results);
    }
}
