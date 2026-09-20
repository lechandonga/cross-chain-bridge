package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发投递与重组交错：
 * 无论投递、重试、重组事件以何种顺序到达，结果确定且可重放一致；
 * 每条消息的有效执行最多一次（同一正统链片段内不重复记账）。
 */
class ReorgConcurrencyTest {

    /**
     * 枚举两条消息的投递与一次目标链重组的全部 6 种到达顺序。
     * 每处理一个事件，就用「各消息当前阶段」推演出的期望状态对照链上余额与手续费，
     * 从而覆盖交错过程中的每一个中间状态，而不只是最终状态。
     */
    @Test
    void allInterleavingsProduceConsistentState() {
        List<String> events = List.of("A", "B", "R");
        for (List<String> order : permutations(events)) {
            runOneInterleaving(order);
        }
    }

    private void runOneInterleaving(List<String> order) {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage ma = f.signedMessage("i-a", "transfer:bob:100");
        CrossChainMessage mb = f.signedMessage("i-b", "transfer:bob:200");
        var da = f.prepareDelivery(ma);
        var db = f.prepareDelivery(mb);

        Map<String, MessageStatus> expected = new HashMap<>();
        List<String> executionOrder = new ArrayList<>();
        long expectedFee = 0;

        for (String e : order) {
            if (e.equals("R")) {
                f.bridge.handleReorg(1);
                // 重组只可能撤销最近一个仍处于 EXECUTED 的消息（执行即出块）
                String lastExecuted = null;
                for (int i = executionOrder.size() - 1; i >= 0; i--) {
                    if (expected.get(executionOrder.get(i)) == MessageStatus.EXECUTED) {
                        lastExecuted = executionOrder.get(i);
                        break;
                    }
                }
                if (lastExecuted != null) {
                    // handleReorg(1) 回滚 1 块，恰好撤销链头上最近一次执行
                    expected.put(lastExecuted, MessageStatus.REVERTED);
                    expectedFee -= 10; // 手续费写在链状态中，随重组一并回滚
                }
            } else {
                var d = e.equals("A") ? da : db;
                BridgeResult r = f.bridge.submit(d.message(), d.proof(), d.header());
                if (r.status() == MessageStatus.EXECUTED) {
                    expected.put(e, MessageStatus.EXECUTED);
                    executionOrder.add(e);
                    expectedFee += 10;
                } else {
                    assertEquals(RejectionReason.REPLAY, r.reason(),
                            "非执行结果只能是重放，顺序=" + order);
                    expected.putIfAbsent(e, MessageStatus.EXECUTED);
                }
            }

            // 对照期望阶段：逐消息状态
            assertStage(f, ma.id(), expected.get("A"), order);
            assertStage(f, mb.id(), expected.get("B"), order);

            // 对照链上账务：余额 = 所有 EXECUTED 消息金额之和
            long expectedBalance = 0;
            if (expected.get("A") == MessageStatus.EXECUTED) expectedBalance += 100;
            if (expected.get("B") == MessageStatus.EXECUTED) expectedBalance += 200;
            assertEquals(expectedBalance, f.balanceOf("bob"),
                    "余额必须与未撤销执行集合一致，顺序=" + order);
            assertEquals(expectedFee, f.collectedFees(),
                    "手续费必须与成功提交次数一致，顺序=" + order);
        }
        f.relayer.shutdown();
    }

    private void assertStage(BridgeTestFixture f, String id, MessageStatus expected, List<String> order) {
        var actual = f.bridge.statusOf(id);
        if (expected == null) {
            assertTrue(actual.isEmpty(), "未投递的消息不应有状态，顺序=" + order);
        } else {
            assertEquals(expected, actual.orElseThrow(), "msg=" + id + " 顺序=" + order);
        }
    }

    /** 多线程下同一消息并发投递与目标链重组反复交错：账务恒为单份，绝不双花。 */
    @Test
    void concurrentDeliveryInterleavedWithReorgsNoDoubleSpend() throws Exception {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("i-conc", "transfer:bob:100");
        var d = f.prepareDelivery(msg);

        int threads = 12;
        int rounds = 200;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final boolean reorger = (t == 0);
                futures.add(CompletableFuture.runAsync(() -> {
                    try { barrier.await(); } catch (Exception ignored) {}
                    for (int i = 0; i < rounds; i++) {
                        if (reorger) {
                            f.bridge.handleReorg(1); // 深度可能超过链头，链返回 0，属正常
                        } else {
                            f.bridge.submit(d.message(), d.proof(), d.header());
                        }
                    }
                }, pool));
            }
            for (var cf : futures) cf.join();
        }

        // 交错过程中余额永远只能是 0 或 100，不可能出现 200（重复记账）
        assertTrue(f.balanceOf("bob") == 0 || f.balanceOf("bob") == 100,
                "并发交错下不得重复记账，实际余额=" + f.balanceOf("bob"));

        // 收敛：若最后执行已被撤销，则补投递一次，使链上恰好留下一次有效执行
        var status = f.bridge.statusOf(msg.id());
        if (status.isPresent() && status.get() == MessageStatus.REVERTED) {
            BridgeResult again = f.bridge.submit(d.message(), d.proof(), d.header());
            assertEquals(MessageStatus.EXECUTED, again.status());
        }
        f.chainB.finalizeUpTo(f.chainB.headHeight());

        assertEquals(MessageStatus.FINALIZED, f.bridge.statusOf(msg.id()).orElseThrow(),
                "收敛后必须达到唯一最终状态");
        assertEquals(100, f.balanceOf("bob"), "收敛后必须恰好一次有效执行");
        assertTrue(f.collectedFees() % 10 == 0, "手续费必须是单次费用的整数倍（无脏写）");
        f.relayer.shutdown();
    }

    private static List<List<String>> permutations(List<String> items) {
        List<List<String>> out = new ArrayList<>();
        permute(new ArrayList<>(items), 0, out);
        return out;
    }

    private static void permute(List<String> arr, int k, List<List<String>> out) {
        if (k == arr.size() - 1) {
            out.add(List.copyOf(arr));
            return;
        }
        for (int i = k; i < arr.size(); i++) {
            java.util.Collections.swap(arr, i, k);
            permute(arr, k + 1, out);
            java.util.Collections.swap(arr, i, k);
        }
    }
}
