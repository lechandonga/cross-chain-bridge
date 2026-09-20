package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发投递与重组交错：结果确定、可重放一致，同一消息的有效执行最多一次。
 */
class ReorgConcurrencyTest {

    /**
     * 大量线程同时投递同一消息，同时另一线程反复触发目标链重组。
     * 无论事件如何交错：每个规范链纪元内至多执行一次；最终不会出现半更新账务
     * （余额只可能是 0 或 100，手续费只可能是 0 或 10），且无死锁。
     */
    @RepeatedTest(20)
    void concurrentDeliveryInterleavedWithReorgsIsSafe() throws Exception {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("i1", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);

        int submitters = 8;
        int reorgRounds = 20;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger reverted = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(submitters + 1)) {
            List<CompletableFuture<Void>> jobs = new ArrayList<>();

            for (int t = 0; t < submitters; t++) {
                jobs.add(CompletableFuture.runAsync(() -> {
                    await(start);
                    for (int round = 0; round < reorgRounds; round++) {
                        BridgeResult r = f.bridge.submit(delivery.message(),
                                delivery.proof(), delivery.header());
                        tally(r, executed, reverted, replayed);
                    }
                }, pool));
            }
            // 重组线程：交替"回滚执行区块"和"放行重新提交"
            jobs.add(CompletableFuture.runAsync(() -> {
                await(start);
                for (int round = 0; round < reorgRounds; round++) {
                    long revertedBlocks = f.bridge.handleReorg(1);
                    if (revertedBlocks == 1) {
                        reverted.incrementAndGet();
                    }
                }
            }, pool));

            start.countDown();
            for (var job : jobs) {
                job.join();
            }
        }

        // 账务不变量：金额永远只可能是 0 或 100，手续费只可能是 0 或 10
        long balance = f.balanceOf("bob");
        assertTrue(balance == 0 || balance == 100,
                "交错后余额出现半更新中间状态: " + balance);
        long fees = f.collectedFees();
        assertTrue(fees == 0 || fees == 10,
                "交错后手续费出现半更新中间状态: " + fees);
        // 余额与手续费必须同生共死（同一原子执行产生）
        assertEquals(balance == 100, fees == 10, "账务与手续费必须一致存在或一致回滚");

        // 最终消息状态只能是确定终态之一
        MessageStatus terminal = f.bridge.statusOf(msg.id()).orElseThrow();
        assertTrue(terminal == MessageStatus.EXECUTED
                        || terminal == MessageStatus.REVERTED,
                "交错结束后状态必须确定: " + terminal);

        // 每个规范纪元内 EXECUTED 计数不超过投递轮数，且任何时刻没有重复记账
        assertTrue(executed.get() <= reorgRounds);
        f.relayer.shutdown();
    }

    /**
     * 事件顺序重放一致性：对同一消息，{提交、重组、重新提交} 三种事件的各种到达顺序，
     * 结束后再做一次最终重组并重新提交，有效执行总次数恒为 1（账务 100/10）。
     */
    @Test
    void sameEventsDifferentOrdersSameOutcome() {
        List<List<String>> orders = List.of(
                List.of("submit", "reorg", "resubmit"),
                List.of("submit", "resubmit", "reorg"),
                List.of("reorg", "submit", "resubmit"),
                List.of("resubmit", "submit", "reorg"),
                List.of("reorg", "resubmit", "submit"),
                List.of("resubmit", "reorg", "submit"));

        String snapshot = null;
        for (List<String> order : orders) {
            BridgeTestFixture f = new BridgeTestFixture();
            CrossChainMessage msg = f.signedMessage("i2", "transfer:bob:100");
            var delivery = f.prepareDelivery(msg);

            for (String event : order) {
                switch (event) {
                    case "submit", "resubmit" -> f.bridge.submit(delivery.message(),
                            delivery.proof(), delivery.header());
                    case "reorg" -> f.bridge.handleReorg(1);
                    default -> throw new IllegalStateException(event);
                }
            }
            // 收口：无论中间顺序如何，再执行一次"重组后重新提交"，结果必须唯一
            f.bridge.handleReorg(1);
            BlockHeader freshHeader = f.commitOnSource(msg);
            MerkleProof freshProof = f.proofFor(msg);
            BridgeResult last = f.bridge.submit(delivery.message(), freshProof, freshHeader);
            assertEquals(MessageStatus.EXECUTED, last.status(),
                    "顺序 " + order + " 收口执行必须成功");
            assertEquals(100, f.balanceOf("bob"), "顺序 " + order + " 账务不一致");
            assertEquals(10, f.collectedFees(), "顺序 " + order + " 手续费不一致");

            String current = f.chainB.stateView().toString();
            if (snapshot == null) {
                snapshot = current;
            } else {
                assertEquals(snapshot, current, "不同事件顺序必须得到可重放的一致最终状态");
            }
            f.relayer.shutdown();
        }
    }

    /**
     * 重组期间到达的重复投递：在途投递与重组互斥，串行化后第二条投递命中原有的
     * 重放防护或已撤销状态，绝不发生同一纪元内的重复执行。
     */
    @Test
    void duplicateSubmissionsAroundReorgExecuteAtMostOncePerEpoch() throws Exception {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("i3", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);

        int threads = 12;
        AtomicInteger executed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    await(start);
                    BridgeResult r = f.bridge.submit(delivery.message(),
                            delivery.proof(), delivery.header());
                    tally(r, executed, new AtomicInteger(), new AtomicInteger());
                }, pool));
            }
            start.countDown();
            for (var future : futures) {
                future.join();
            }
        }
        assertEquals(1, executed.get(), "同一纪元内有效执行最多一次");
        assertEquals(100, f.balanceOf("bob"));
        assertEquals(10, f.collectedFees());
        f.relayer.shutdown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void tally(BridgeResult r, AtomicInteger executed, AtomicInteger reverted,
                       AtomicInteger replayed) {
        if (r.status() == MessageStatus.EXECUTED) {
            executed.incrementAndGet();
        } else if (r.status() == MessageStatus.REVERTED) {
            reverted.incrementAndGet();
        } else if (r.reason() == RejectionReason.REPLAY) {
            replayed.incrementAndGet();
        }
    }
}
