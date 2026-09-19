package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import com.github.lechandonga.bridge.relayer.Relayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** 并发投递：多通道乱序处理结果一致；并发重复投递只执行一次，无半更新状态。 */
class ConcurrencyTest {

    @Test
    void concurrentChannelsDeterministicOutcome() {
        BridgeTestFixture f = new BridgeTestFixture();
        int channels = 4;
        int perChannel = 10;

        List<CompletableFuture<List<BridgeResult>>> futures = new ArrayList<>();
        for (int c = 0; c < channels; c++) {
            List<Relayer.Delivery> deliveries = new ArrayList<>();
            for (int i = 0; i < perChannel; i++) {
                CrossChainMessage msg = f.signedMessage("c" + c + "-m" + i,
                        "transfer:acct" + c + ":" + (i + 1));
                deliveries.add(f.prepareDelivery(msg));
            }
            futures.add(f.relayer.deliverChannelAsync("channel-" + c, deliveries));
        }
        List<BridgeResult> results = futures.stream()
                .flatMap(cf -> cf.join().stream()).toList();

        assertEquals(channels * perChannel, results.size());
        assertTrue(results.stream().allMatch(r -> r.status() == MessageStatus.EXECUTED));
        for (int c = 0; c < channels; c++) {
            long expected = (long) perChannel * (perChannel + 1) / 2;
            assertEquals(expected, f.balanceOf("acct" + c), "通道 " + c + " 账务必须确定");
        }
        assertEquals(channels * perChannel * 10L, f.collectedFees());
        f.relayer.shutdown();
    }

    @Test
    void differentOrderSameFinalState() {
        // 同一批消息以两种相反顺序处理，最终状态必须一致
        List<String> ids = List.of("o1", "o2", "o3", "o4", "o5");
        var stateA = runOrder(ids, false);
        var stateB = runOrder(ids, true);
        assertEquals(stateA, stateB, "处理顺序不一致时最终状态必须一致");
    }

    private java.util.Map<String, String> runOrder(List<String> ids, boolean reverse) {
        BridgeTestFixture f = new BridgeTestFixture();
        List<Relayer.Delivery> deliveries = new ArrayList<>();
        for (String id : ids) {
            deliveries.add(f.prepareDelivery(f.signedMessage(id, "transfer:bob:10")));
        }
        if (reverse) {
            Collections.reverse(deliveries);
        }
        deliveries.forEach(d -> f.relayer.deliver(d.message(), d.proof(), d.header()));
        f.relayer.shutdown();
        return f.chainB.stateView();
    }

    @Test
    void concurrentDuplicateDeliverySingleExecution() throws Exception {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("dup1", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);

        int threads = 16;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            List<CompletableFuture<BridgeResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> f.bridge.submit(delivery.message(), delivery.proof(), delivery.header()), pool));
            }
            List<BridgeResult> results = new ArrayList<>();
            for (var cf : futures) {
                results.add(cf.get());
            }
            long executed = results.stream().filter(r -> r.status() == MessageStatus.EXECUTED).count();
            long replayed = results.stream().filter(r -> r.reason() == RejectionReason.REPLAY).count();
            assertEquals(1, executed, "并发重复投递只能有一条有效执行");
            assertEquals(threads - 1, replayed);
            assertEquals(100, f.balanceOf("bob"), "不得出现半更新的中间状态");
            assertEquals(10, f.collectedFees());
        }
        f.relayer.shutdown();
    }
}
