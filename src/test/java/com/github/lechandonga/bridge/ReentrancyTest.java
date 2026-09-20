package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重入语义：一次投递/重试尚未结束时从同一路径再次进入，必须被识别为可区分失败，
 * 不得出现半更新状态、不得重复记账，也不得影响后续正常处理。
 */
class ReentrancyTest {

    @Test
    void reentrantSubmitWhileDeliveryInFlightIsRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("e1", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);

        // 执行切面在任何账务写入之前触发：同线程、同一消息、同一路径再次进入
        f.bridge.setBeforeExecuteHook(inFlight -> {
            if (inFlight.id().equals(msg.id())) {
                BridgeResult nested = f.bridge.submit(delivery.message(),
                        delivery.proof(), delivery.header());
                assertEquals(MessageStatus.REJECTED, nested.status());
                assertEquals(RejectionReason.REENTRANCY_DETECTED, nested.reason());
            }
        });

        BridgeResult outer = f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(MessageStatus.EXECUTED, outer.status(), "外层投递仍须正常完成");
        assertEquals(100, f.balanceOf("bob"), "重入不得造成重复记账");
        assertEquals(10, f.collectedFees());

        // 重入检测不得污染状态：之后再来的重复投递走标准重放口径
        BridgeResult later = f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(RejectionReason.REPLAY, later.reason());
        assertEquals(100, f.balanceOf("bob"));
        assertEquals(10, f.collectedFees());
    }

    @Test
    void reentrantRetryWhileRetryInFlightIsRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        // gasLimit=50 永远超限（成本 116），maxRetries=3
        CrossChainMessage msg = f.signedMessage("e2", "alice", "transfer:bob:100",
                10, 50, 1_000, 3);
        var delivery = f.prepareDelivery(msg);
        assertEquals(MessageStatus.RETRYABLE,
                f.bridge.submit(delivery.message(), delivery.proof(), delivery.header()).status());

        // 重试执行切面内同线程再次重试同一路径
        f.bridge.setBeforeExecuteHook(inFlight -> {
            if (inFlight.id().equals(msg.id())) {
                BridgeResult nested = f.bridge.retry(msg.id());
                assertEquals(RejectionReason.REENTRANCY_DETECTED, nested.reason());
                assertEquals(0, f.balanceOf("bob"), "重入重试不得记账");
            }
        });

        BridgeResult retry = f.bridge.retry(msg.id());
        assertEquals(MessageStatus.RETRYABLE, retry.status(), "外层重试正常结算为可重试");
        assertEquals(0, f.balanceOf("bob"));
        assertEquals(0, f.collectedFees(), "重入不得造成重复收取手续费");
    }

    @Test
    void reentrantCallDoesNotConsumeRetryBudget() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("e3", "alice", "transfer:bob:100",
                10, 50, 1_000, 2); // 允许 2 次重试
        var delivery = f.prepareDelivery(msg);
        f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());

        f.bridge.setBeforeExecuteHook(inFlight -> {
            if (inFlight.id().equals(msg.id())) {
                f.bridge.retry(msg.id()); // 重入：不得扣减重试次数
            }
        });
        // 第一次合法重试：超限时预算 2 -> 1，仍为 RETRYABLE
        assertEquals(MessageStatus.RETRYABLE, f.bridge.retry(msg.id()).status());
        // 第二次合法重试：预算 1 -> 0。若重入曾多扣过一次预算，这里就会提前 FAILED_FINAL
        BridgeResult second = f.bridge.retry(msg.id());
        assertEquals(MessageStatus.FAILED_FINAL, second.status());
        assertEquals(RejectionReason.RETRIES_EXHAUSTED, second.reason(),
                "预算只能被真实重试消耗：两次合法重试后恰好在终态耗尽");
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void unrelatedMessageIsNotBlockedByReentrancyGuard() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage m1 = f.signedMessage("e4a", "transfer:bob:100");
        var d1 = f.prepareDelivery(m1);
        CrossChainMessage m2 = f.signedMessage("e4b", "transfer:carol:200");
        var d2 = f.prepareDelivery(m2);

        // m1 执行切面内尝试处理另一条消息：不同消息锁，不能被当作 m1 的重入
        f.bridge.setBeforeExecuteHook(inFlight -> {
            if (inFlight.id().equals(m1.id())) {
                BridgeResult other = f.bridge.submit(d2.message(), d2.proof(), d2.header());
                assertEquals(MessageStatus.EXECUTED, other.status());
            }
        });

        BridgeResult first = f.bridge.submit(d1.message(), d1.proof(), d1.header());
        assertEquals(MessageStatus.EXECUTED, first.status());
        assertEquals(100, f.balanceOf("bob"));
        assertEquals(200, f.balanceOf("carol"), "不同消息的正常处理不得被重入防护阻塞");
    }

    @Test
    void concurrentDifferentMessagesAreNotSerialisedIntoLongBlocking() throws Exception {
        BridgeTestFixture f = new BridgeTestFixture();
        int count = 8;
        java.util.List<CrossChainMessage> messages = new java.util.ArrayList<>();
        java.util.List<com.github.lechandonga.bridge.relayer.Relayer.Delivery> deliveries =
                new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            CrossChainMessage msg = f.signedMessage("e5-" + i, "transfer:acct" + i + ":10");
            messages.add(msg);
            deliveries.add(f.prepareDelivery(msg));
        }

        // 每条消息在执行切面内停顿，模拟一次较慢的真实执行；
        // 若重入防护退化为全局锁，总耗时将接近 count 倍停顿，这里验证它们能并行推进
        f.bridge.setBeforeExecuteHook(inFlight -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long start = System.nanoTime();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(count)) {
            java.util.List<java.util.concurrent.CompletableFuture<BridgeResult>> futures =
                    new java.util.ArrayList<>();
            for (var d : deliveries) {
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> f.bridge.submit(d.message(), d.proof(), d.header()), pool));
            }
            for (var future : futures) {
                assertEquals(MessageStatus.EXECUTED, future.join().status());
            }
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < count * 100L,
                "不同消息被串行阻塞，耗时 " + elapsedMillis + "ms 接近全串行上限");
        f.relayer.shutdown();
    }

    @Test
    void reorgIsNotDeadlockedByInFlightGuards() throws Exception {
        BridgeTestFixture f = new BridgeTestFixture();
        // 先放一条会一直停在执行切面的慢投递（另一条消息），期间重组不得死锁
        CrossChainMessage slow = f.signedMessage("e6a", "transfer:bob:10");
        var slowDelivery = f.prepareDelivery(slow);
        CrossChainMessage quick = f.signedMessage("e6b", "transfer:carol:20");
        var quickDelivery = f.prepareDelivery(quick);

        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        f.bridge.setBeforeExecuteHook(inFlight -> {
            if (inFlight.id().equals(slow.id())) {
                entered.countDown();
                try {
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            // 慢投递占用的是 slow 的消息锁，与 quick、重组均无锁嵌套
            var slowFuture = java.util.concurrent.CompletableFuture.runAsync(() ->
                    f.bridge.submit(slowDelivery.message(), slowDelivery.proof(),
                            slowDelivery.header()), pool);
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));

            // slow 尚未结束时，另一条消息可以照常推进
            var quickFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    f.bridge.submit(quickDelivery.message(), quickDelivery.proof(),
                            quickDelivery.header()), pool);
            assertEquals(MessageStatus.EXECUTED,
                    quickFuture.get(2, java.util.concurrent.TimeUnit.SECONDS).status());

            // 深度为 0 的重组不回滚任何区块，必须立刻返回且不被慢投递卡死成死锁
            var noopReorg = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> f.bridge.handleReorg(0), pool);
            assertEquals(0L, noopReorg.get(2, java.util.concurrent.TimeUnit.SECONDS),
                    "无实际回滚的重组不得被在途投递死锁");

            release.countDown();
            slowFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(MessageStatus.EXECUTED, f.bridge.statusOf(slow.id()).orElseThrow());
        assertEquals(10, f.balanceOf("bob"));
        assertEquals(20, f.balanceOf("carol"));
        f.relayer.shutdown();
    }
}
