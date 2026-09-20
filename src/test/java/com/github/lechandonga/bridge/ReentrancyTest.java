package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重入语义：一次投递/重试尚未结束时从同一处理路径（同线程）再次进入，
 * 必须被识别为 {@link RejectionReason#REENTRANT_CALL}；
 * 不得半更新、不得重复记账，也不得阻塞其他消息的正常并发投递。
 */
class ReentrancyTest {

    /** 在执行窗口（账务已应用、区块未固化）内同路径再次 submit：被识别为重入，外层仍只执行一次。 */
    @Test
    void reentrantSubmitDetectedAndExecutedOnce() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("e1", "alice", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);

        List<BridgeResult> nestedResults = new ArrayList<>();
        f.bridge.setExecutionHook(id -> {
            if (id.equals(msg.id())) {
                // 同一路径（同线程）在提交尚未结束时再次进入
                BridgeResult nested = f.bridge.submit(
                        delivery.message(), delivery.proof(), delivery.header());
                nestedResults.add(nested);
            }
        });

        BridgeResult outer = f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());

        assertEquals(MessageStatus.EXECUTED, outer.status(), "外层调用必须正常完成");
        assertEquals(1, nestedResults.size());
        assertEquals(MessageStatus.REJECTED, nestedResults.get(0).status());
        assertEquals(RejectionReason.REENTRANT_CALL, nestedResults.get(0).reason());

        assertEquals(100, f.balanceOf("bob"), "重入不得导致重复记账");
        assertEquals(10, f.collectedFees(), "手续费只收一次");
        assertEquals(MessageStatus.EXECUTED, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(RejectionReason.REPLAY,
                f.bridge.submit(delivery.message(), delivery.proof(), delivery.header()).reason(),
                "外层结束后重入标记已清除，再次投递按正常重放处理");
    }

    /** 重入 retry 路径同样被识别。 */
    @Test
    void reentrantRetryDetected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("e2", "alice", "transfer:bob:80");
        var delivery = f.prepareDelivery(msg);

        List<BridgeResult> nested = new ArrayList<>();
        f.bridge.setExecutionHook(id -> {
            if (id.equals(msg.id())) {
                nested.add(f.bridge.retry(msg.id()));
            }
        });

        BridgeResult outer = f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(MessageStatus.EXECUTED, outer.status());
        assertEquals(1, nested.size());
        assertEquals(RejectionReason.REENTRANT_CALL, nested.get(0).reason());
        assertEquals(80, f.balanceOf("bob"));
        assertEquals(10, f.collectedFees());
    }

    /** 重入防护是线程内的，不构成跨线程互斥：不同消息并发投递互不阻塞、全部成功。 */
    @Test
    void concurrentDifferentMessagesAreNotBlocked() throws Exception {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage m1 = f.signedMessage("e3a", "transfer:bob:10");
        CrossChainMessage m2 = f.signedMessage("e3b", "transfer:bob:20");
        var d1 = f.prepareDelivery(m1);
        var d2 = f.prepareDelivery(m2);

        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<CompletableFuture<BridgeResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                var d = (i % 2 == 0) ? d1 : d2;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try { barrier.await(); } catch (Exception ignored) {}
                    return f.bridge.submit(d.message(), d.proof(), d.header());
                }, pool));
            }
            List<BridgeResult> results = new ArrayList<>();
            for (var cf : futures) {
                results.add(cf.get());
            }
            long executed = results.stream().filter(r -> r.status() == MessageStatus.EXECUTED).count();
            long replay = results.stream().filter(r -> r.reason() == RejectionReason.REPLAY).count();
            assertEquals(2, executed, "两条不同消息各执行一次");
            assertEquals(threads - 2, replay, "其余为正常重放，而不是重入误报");
            assertFalse(results.stream().anyMatch(r -> r.reason() == RejectionReason.REENTRANT_CALL),
                    "跨线程并发投递不得被误判为重入");
        }
        assertEquals(30, f.balanceOf("bob"));
        assertEquals(20, f.collectedFees());
    }
}
