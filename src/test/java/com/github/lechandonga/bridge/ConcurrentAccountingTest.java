package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.relayer.Relayer;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发执行的账务一致性：多条不同消息并发成功执行时，共享账户余额与共享手续费累计
 * 必须精确等于各条消息应得金额之和；任意交错、重复多轮结果完全一致。
 */
class ConcurrentAccountingTest {

    private static final long FEE = 10;

    /**
     * 多线程并发投递一批不同消息：多个线程打同一个账户（共享余额键），
     * 所有消息共同累计手续费（共享记账键）。通过执行切面让所有线程在执行前对齐，
     * 最大化读写交错；最终账必须一分不差。
     */
    @RepeatedTest(15)
    void concurrentDistinctMessagesExactAccounting() throws Exception {
        BridgeTestFixture f = new BridgeTestFixture();
        int threads = 8;
        int perThread = 20;
        int accounts = 4;

        // 预备全部投递材料（来源链登记 + 证明）
        Relayer.Delivery[][] deliveries = new Relayer.Delivery[threads][perThread];
        long[] expectedBalance = new long[accounts];
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                int acct = (t + i) % accounts;
                long amount = t * perThread + i + 1;
                CrossChainMessage msg = f.signedMessage("m-" + t + "-" + i,
                        "transfer:acct" + acct + ":" + amount);
                deliveries[t][i] = f.prepareDelivery(msg);
                expectedBalance[acct] += amount;
            }
        }
        long expectedFees = (long) threads * perThread * FEE;

        // 执行切面：让所有线程在真正记账前对齐，放大读-改-写交错窗口
        CyclicBarrier barrier = new CyclicBarrier(threads);
        f.bridge.setBeforeExecuteHook(m -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 屏障超时/中断不阻断执行
            }
        });

        AtomicInteger executed = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<CompletableFuture<Void>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                jobs.add(CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < perThread; i++) {
                        Relayer.Delivery d = deliveries[thread][i];
                        BridgeResult r = f.bridge.submit(d.message(), d.proof(), d.header());
                        if (r.status() == MessageStatus.EXECUTED) {
                            executed.incrementAndGet();
                        }
                    }
                }, pool));
            }
            for (var job : jobs) {
                job.join();
            }
        }

        assertEquals(threads * perThread, executed.get(), "全部消息都必须成功执行");
        for (int a = 0; a < accounts; a++) {
            assertEquals(expectedBalance[a], f.balanceOf("acct" + a),
                    "账户 acct" + a + " 余额必须等于各条消息应得金额之和");
        }
        assertEquals(expectedFees, f.collectedFees(),
                "手续费累计必须等于全部已执行消息手续费之和");
        f.relayer.shutdown();
    }

    /**
     * 同一批输入反复并发执行多轮，每轮最终账完全一致（确定性）。
     */
    @Test
    void repeatedConcurrentRunsProduceIdenticalLedger() throws Exception {
        int threads = 6;
        int perThread = 15;
        String reference = null;
        for (int round = 0; round < 5; round++) {
            BridgeTestFixture f = new BridgeTestFixture();
            List<Relayer.Delivery> deliveries = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                for (int i = 0; i < perThread; i++) {
                    deliveries.add(f.prepareDelivery(f.signedMessage("r-" + t + "-" + i,
                            "transfer:shared:" + (i + 1))));
                }
            }
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(threads)) {
                List<CompletableFuture<Void>> jobs = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int thread = t;
                    jobs.add(CompletableFuture.runAsync(() -> {
                        await(start);
                        for (int i = thread; i < deliveries.size(); i += threads) {
                            Relayer.Delivery d = deliveries.get(i);
                            f.bridge.submit(d.message(), d.proof(), d.header());
                        }
                    }, pool));
                }
                start.countDown();
                for (var job : jobs) {
                    job.join();
                }
            }
            long expectedSum = (long) threads * perThread * (perThread + 1) / 2;
            assertEquals(expectedSum, f.balanceOf("shared"), "第 " + round + " 轮余额不一致");
            assertEquals((long) threads * perThread * FEE, f.collectedFees(),
                    "第 " + round + " 轮手续费不一致");
            String ledger = f.chainB.stateView().toString()
                    .replaceAll("msg:r-\\d+-\\d+(:hash)?=[^,}]+,?", "");
            if (reference == null) {
                reference = ledger;
            } else {
                assertEquals(reference, ledger, "不同轮次的账务结果必须完全一致");
            }
            f.relayer.shutdown();
        }
    }

    /**
     * 多条不同消息并发投递、并与重组交错：结束后链上账务必须与桥接记录精确对齐——
     * 处于 EXECUTED 状态的消息，其金额与手续费恰好全部体现在链上；被撤销的消息
     * 不留任何账务痕迹；任何一笔有效执行都不会被重复计入。
     */
    @RepeatedTest(10)
    void concurrentDeliveryWithReorgsLedgerMatchesRecords() throws Exception {
        BridgeTestFixture f = new BridgeTestFixture();
        int messageCount = 12;
        int accounts = 2;

        List<Relayer.Delivery> deliveries = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        List<String> accountOf = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            String acct = "acct" + (i % accounts);
            long amount = i + 1;
            deliveries.add(f.prepareDelivery(f.signedMessage("x-" + i,
                    "transfer:" + acct + ":" + amount)));
            amounts.add(amount);
            accountOf.add(acct);
        }

        int submitters = 6;
        int rounds = 6;
        int reorgRounds = 30;
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(submitters + 1)) {
            List<CompletableFuture<Void>> jobs = new ArrayList<>();
            for (int t = 0; t < submitters; t++) {
                jobs.add(CompletableFuture.runAsync(() -> {
                    await(start);
                    for (int round = 0; round < rounds; round++) {
                        for (Relayer.Delivery d : deliveries) {
                            f.bridge.submit(d.message(), d.proof(), d.header());
                        }
                    }
                }, pool));
            }
            jobs.add(CompletableFuture.runAsync(() -> {
                await(start);
                for (int i = 0; i < reorgRounds; i++) {
                    f.bridge.handleReorg(1);
                }
            }, pool));
            start.countDown();
            for (var job : jobs) {
                job.join();
            }
        }

        // 以桥接记录为准重算应有账务：只有仍处 EXECUTED 的消息才应体现在链上
        long[] expectedBalance = new long[accounts];
        long expectedFees = 0;
        for (int i = 0; i < messageCount; i++) {
            MessageStatus status = f.bridge.statusOf("x-" + i).orElseThrow();
            assertTrue(status == MessageStatus.EXECUTED || status == MessageStatus.REVERTED,
                    "交错结束后消息状态必须确定: x-" + i + "=" + status);
            if (status == MessageStatus.EXECUTED) {
                expectedBalance[i % accounts] += amounts.get(i);
                expectedFees += FEE;
            }
        }
        for (int a = 0; a < accounts; a++) {
            assertEquals(expectedBalance[a], f.balanceOf("acct" + a),
                    "账户 acct" + a + " 链上余额必须与仍生效的执行记录一致");
        }
        assertEquals(expectedFees, f.collectedFees(),
                "链上手续费必须与仍生效的执行记录一致（同生共死、不重复计入）");
        f.relayer.shutdown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
