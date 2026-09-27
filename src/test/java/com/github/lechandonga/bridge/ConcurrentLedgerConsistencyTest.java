package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import com.github.lechandonga.bridge.relayer.Relayer;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发账务一致性：多条不同消息并发在目标链成功执行时，共享账户余额与手续费累计
 * 必须精确等于各条成功执行消息应得金额之和；任意交错、反复执行结果完全一致；
 * 与重组交错时，链上账务与消息状态同生共死。
 */
class ConcurrentLedgerConsistencyTest {

    /**
     * 稳定复现：执行切面内设置集结点，所有投递线程在记账前同时到达，
     * 最大化共享键读-改-写的交错窗口。任何丢失更新都会立刻反映为总额短缺。
     */
    @Test
    void simultaneousAccountingNeverLosesUpdates() throws Exception {
        int n = 16;
        BridgeTestFixture f = new BridgeTestFixture();
        List<Relayer.Delivery> deliveries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            deliveries.add(f.prepareDelivery(
                    f.signedMessage("led-" + i, "transfer:bob:" + (i + 1))));
        }

        CyclicBarrier barrier = new CyclicBarrier(n);
        f.bridge.setBeforeExecuteHook(m -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        List<BridgeResult> results = submitAll(f, deliveries, n);
        assertEquals(n, results.stream().filter(r -> r.status() == MessageStatus.EXECUTED).count(),
                "全部消息都必须成功执行");

        long expectedBalance = (long) n * (n + 1) / 2;
        assertEquals(expectedBalance, f.balanceOf("bob"),
                "并发记账不得丢失更新：余额必须等于各条消息金额之和");
        assertEquals(n * 10L, f.collectedFees(),
                "并发记账不得丢失更新：手续费必须等于各条消息手续费之和");
        f.relayer.shutdown();
    }

    /**
     * 多账户 + 共享手续费混合负载，反复多轮：每轮总额都必须精确，
     * 且最终链上状态逐轮完全一致。
     */
    @RepeatedTest(5)
    void repeatedConcurrentBatchesAreExactAndIdentical() throws Exception {
        int accounts = 4;
        int perAccount = 8;
        String referenceState = null;
        for (int round = 0; round < 3; round++) {
            BridgeTestFixture f = new BridgeTestFixture();
            List<Relayer.Delivery> deliveries = new ArrayList<>();
            for (int a = 0; a < accounts; a++) {
                for (int i = 0; i < perAccount; i++) {
                    deliveries.add(f.prepareDelivery(f.signedMessage(
                            "rep-" + a + "-" + i, "transfer:acct" + a + ":" + (i + 1))));
                }
            }
            List<BridgeResult> results = submitAll(f, deliveries, accounts * perAccount);
            assertEquals(accounts * perAccount,
                    results.stream().filter(r -> r.status() == MessageStatus.EXECUTED).count());

            long expectedPerAccount = (long) perAccount * (perAccount + 1) / 2;
            for (int a = 0; a < accounts; a++) {
                assertEquals(expectedPerAccount, f.balanceOf("acct" + a),
                        "第 " + round + " 轮账户 acct" + a + " 余额必须精确");
            }
            assertEquals(accounts * perAccount * 10L, f.collectedFees(),
                    "第 " + round + " 轮手续费累计必须精确");

            String state = f.chainB.stateView().toString();
            if (referenceState == null) {
                referenceState = state;
            } else {
                assertEquals(referenceState, state, "反复并发执行最终状态必须完全一致");
            }
            f.relayer.shutdown();
        }
    }

    /**
     * 同一条消息被并发重复投递、且不同消息打同一个账户：每条消息至多执行一次，
     * 总额仍精确等于各条不同消息之和。
     */
    @Test
    void mixedDuplicateAndDistinctDeliveriesSettleExactly() throws Exception {
        int n = 12;
        BridgeTestFixture f = new BridgeTestFixture();
        List<Relayer.Delivery> deliveries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            deliveries.add(f.prepareDelivery(
                    f.signedMessage("mix-" + i, "transfer:bob:" + (i + 1))));
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(n * 2)) {
            List<CompletableFuture<Void>> jobs = new ArrayList<>();
            for (Relayer.Delivery d : deliveries) {
                for (int dup = 0; dup < 2; dup++) {
                    jobs.add(CompletableFuture.runAsync(() -> {
                        await(start);
                        BridgeResult r = f.bridge.submit(d.message(), d.proof(), d.header());
                        if (r.status() == MessageStatus.EXECUTED) {
                            executed.incrementAndGet();
                        } else if (r.reason() == RejectionReason.REPLAY) {
                            replayed.incrementAndGet();
                        }
                    }, pool));
                }
            }
            start.countDown();
            for (var job : jobs) {
                job.join();
            }
        }
        assertEquals(n, executed.get(), "每条不同消息必须恰好执行一次");
        assertEquals(n, replayed.get(), "重复投递必须全部命中重放防护");
        assertEquals((long) n * (n + 1) / 2, f.balanceOf("bob"));
        assertEquals(n * 10L, f.collectedFees());
        f.relayer.shutdown();
    }

    /**
     * 并发投递与重组交错：结束后链上账务必须与消息状态同生共死——
     * 余额/手续费恰好等于仍处于 EXECUTED 状态的消息应得金额之和，不多不少。
     */
    @RepeatedTest(10)
    void ledgerAndStatusSurviveOrRevertTogetherUnderReorgs() throws Exception {
        int n = 10;
        BridgeTestFixture f = new BridgeTestFixture();
        List<CrossChainMessage> messages = new ArrayList<>();
        List<Relayer.Delivery> deliveries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CrossChainMessage msg = f.signedMessage("rg-" + i, "transfer:bob:" + (i + 1));
            messages.add(msg);
            deliveries.add(f.prepareDelivery(msg));
        }

        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(n + 1)) {
            List<CompletableFuture<Void>> jobs = new ArrayList<>();
            for (Relayer.Delivery d : deliveries) {
                jobs.add(CompletableFuture.runAsync(() -> {
                    await(start);
                    f.bridge.submit(d.message(), d.proof(), d.header());
                }, pool));
            }
            jobs.add(CompletableFuture.runAsync(() -> {
                await(start);
                for (int i = 0; i < n; i++) {
                    f.bridge.handleReorg(1);
                }
            }, pool));
            start.countDown();
            for (var job : jobs) {
                job.join();
            }
        }

        long expectedBalance = 0;
        long expectedFees = 0;
        for (int i = 0; i < n; i++) {
            MessageStatus status = f.bridge.statusOf(messages.get(i).id()).orElseThrow();
            assertTrue(status == MessageStatus.EXECUTED || status == MessageStatus.REVERTED,
                    "交错结束后状态必须确定: " + status);
            if (status == MessageStatus.EXECUTED) {
                expectedBalance += i + 1;
                expectedFees += 10;
            }
        }
        assertEquals(expectedBalance, f.balanceOf("bob"),
                "余额必须恰好等于仍处于 EXECUTED 状态的消息金额之和");
        assertEquals(expectedFees, f.collectedFees(),
                "手续费必须恰好等于仍处于 EXECUTED 状态的消息手续费之和");
        // 链上记录的消息状态与桥接账本一致：EXECUTED 的消息链上必须留有执行记录
        Map<String, String> view = f.chainB.stateView();
        for (int i = 0; i < n; i++) {
            MessageStatus status = f.bridge.statusOf(messages.get(i).id()).orElseThrow();
            String onChain = view.get("msg:rg-" + i);
            if (status == MessageStatus.EXECUTED) {
                assertEquals(MessageStatus.EXECUTED.name(), onChain,
                        "EXECUTED 消息在链上必须有对应状态记录");
            }
        }
        f.relayer.shutdown();
    }

    private static List<BridgeResult> submitAll(BridgeTestFixture f,
                                                List<Relayer.Delivery> deliveries, int threads) {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<CompletableFuture<BridgeResult>> futures = new ArrayList<>();
            for (Relayer.Delivery d : deliveries) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return f.bridge.submit(d.message(), d.proof(), d.header());
                }, pool));
            }
            start.countDown();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
