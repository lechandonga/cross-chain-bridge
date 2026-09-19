package com.github.lechandonga.crosschainbridge;

import com.github.lechandonga.crosschainbridge.bridge.*;
import com.github.lechandonga.crosschainbridge.model.*;
import com.github.lechandonga.crosschainbridge.support.TestMessages;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyAndRetryTest {

    /** 构造一个包含 4 条消息（A->B）的已分发批次。 */
    private List<MessageProof> prepareBatch(TwoChainSystem system, int count, long now) {
        system.stateB().credit("alice", 1_000_000L);
        List<SignedMessage> signed = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BridgeMessage msg = TestMessages.transfer("c-" + i,
                    ChainId.CHAIN_A, ChainId.CHAIN_B, "alice", "bob", 100L, now + i);
            signed.add(system.endpointA().originate(msg, now + i));
        }
        return system.endpointA().dispatchBatch(signed, system.endpointB());
    }

    @Test
    void concurrentOutOfOrderDeliveryProducesConsistentBalances() throws Exception {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        List<MessageProof> proofs = prepareBatch(system, 4, now);

        // 乱序 + 重复：下标序列 [3,0,2,1,0,3,2,1]，每条消息出现两次
        List<Integer> order = List.of(3, 0, 2, 1, 0, 3, 2, 1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Relayer relayer = new Relayer(system.endpointA(), system.endpointB(), proofs, pool);
            List<SubmitResult> results = relayer.deliver(order, now + 50L);

            long accepted = results.stream().filter(SubmitResult::accepted).count();
            long duplicates = results.stream()
                    .filter(r -> !r.accepted() && r.failureReason() == FailureReason.DUPLICATE_MESSAGE)
                    .count();
            assertEquals(4, accepted, "每条消息恰好被执行一次");
            assertEquals(4, duplicates, "其余 4 次投递全部判为重复");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        // 4 条消息每条转账 100，账务结果与投递顺序、并发无关
        assertEquals(999_600L, system.stateB().balanceOf("alice"));
        assertEquals(400L, system.stateB().balanceOf("bob"));
        for (int i = 0; i < 4; i++) {
            assertEquals(MessageStatus.EXECUTED, system.endpointB().stateOf("c-" + i).status());
        }
    }

    @Test
    void sameMessageSubmittedConcurrentlyIsExecutedExactlyOnce() throws Exception {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        List<MessageProof> proofs = prepareBatch(system, 1, now);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<SubmitResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> system.endpointB().submit(proofs.get(0), now + 30L)));
            }
            long accepted = 0;
            long duplicated = 0;
            for (var f : futures) {
                SubmitResult r = f.get();
                if (r.accepted()) {
                    accepted++;
                } else if (r.failureReason() == FailureReason.DUPLICATE_MESSAGE) {
                    duplicated++;
                }
            }
            assertEquals(1, accepted);
            assertEquals(threads - 1, duplicated);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
        assertEquals(999_900L, system.stateB().balanceOf("alice"));
        assertEquals(100L, system.stateB().balanceOf("bob"));
    }

    @Test
    void resourceLimitExceededEntersRetryableThenSucceedsWhenRaised() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 1_000L);
        // requiredGas=200 > feeLimit=100：资源超限
        BridgeMessage msg = TestMessages.withGasAndFee("gas-1", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "transfer:alice,bob,50", 200L, 100L, 3, 100_000L, now);
        SignedMessage signed = system.endpointA().originate(msg, now);
        List<MessageProof> proofs = system.endpointA().dispatchBatch(List.of(signed), system.endpointB());

        SubmitResult first = system.endpointB().submit(proofs.get(0), now + 10L);
        assertFalse(first.accepted());
        assertEquals(MessageStatus.RETRYABLE, first.status());
        assertEquals(FailureReason.RESOURCE_LIMIT_EXCEEDED, first.failureReason());
        // 进入可重试期间不执行任何账务
        assertEquals(1_000L, system.stateB().balanceOf("alice"));
        assertEquals(0L, system.stateB().balanceOf("bob"));
        // 消息并未丢失，重复投递在占用期被拒绝
        assertEquals(FailureReason.DUPLICATE_MESSAGE,
                system.endpointB().submit(proofs.get(0), now + 11L).failureReason());

        // 提高费用上限后重试成功（重新构造同一语义、新 id 不必要——这里通过重试通道：
        // 资源消耗条件未变，因此先演示重试耗尽；成功路径见下一个测试）
        SubmitResult retry = system.endpointB().retry("gas-1", now + 20L);
        assertEquals(MessageStatus.RETRYABLE, retry.status());
        assertEquals(1, retry.retryCount());
    }

    @Test
    void retrySucceedsAfterFeeIsToppedUp() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 1_000L);
        BridgeMessage msg = TestMessages.withGasAndFee("gas-topup", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "transfer:alice,bob,50", 200L, 100L, 3, 100_000L, now);
        SignedMessage signed = system.endpointA().originate(msg, now);
        List<MessageProof> proofs = system.endpointA().dispatchBatch(List.of(signed), system.endpointB());

        SubmitResult first = system.endpointB().submit(proofs.get(0), now + 10L);
        assertEquals(MessageStatus.RETRYABLE, first.status());

        // 发送方补足 150 的资源额度（100+150 >= 200），重试成功
        system.endpointB().addFeeTopUp("gas-topup", 150L);
        SubmitResult second = system.endpointB().retry("gas-topup", now + 30L);
        assertTrue(second.accepted());
        assertEquals(MessageStatus.EXECUTED, second.status());
        assertEquals(950L, system.stateB().balanceOf("alice"));
        assertEquals(50L, system.stateB().balanceOf("bob"));
    }

    @Test
    void retriesExhaustedReachesDeterministicFinalState() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 1_000L);
        BridgeMessage msg = TestMessages.withGasAndFee("gas-2", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "transfer:alice,bob,50", 500L, 100L, 2, 100_000L, now);
        SignedMessage signed = system.endpointA().originate(msg, now);
        List<MessageProof> proofs = system.endpointA().dispatchBatch(List.of(signed), system.endpointB());

        system.endpointB().submit(proofs.get(0), now + 10L);
        SubmitResult r1 = system.endpointB().retry("gas-2", now + 20L);
        assertEquals(MessageStatus.RETRYABLE, r1.status());
        SubmitResult r2 = system.endpointB().retry("gas-2", now + 30L);
        assertEquals(MessageStatus.RETRYABLE, r2.status());
        // 第 3 次（超过 maxRetries=2）给出确定终态
        SubmitResult r3 = system.endpointB().retry("gas-2", now + 40L);
        assertFalse(r3.accepted());
        assertEquals(MessageStatus.FAILED_FINAL, r3.status());
        assertEquals(FailureReason.RETRIES_EXHAUSTED, r3.failureReason());
        assertEquals(1_000L, system.stateB().balanceOf("alice"));
    }

    @Test
    void retryWindowExpiryGivesDeterministicFinalState() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 1_000L);
        BridgeMessage msg = TestMessages.withGasAndFee("gas-3", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "transfer:alice,bob,50", 500L, 100L, 5, 1_000L, now);
        SignedMessage signed = system.endpointA().originate(msg, now);
        List<MessageProof> proofs = system.endpointA().dispatchBatch(List.of(signed), system.endpointB());

        SubmitResult first = system.endpointB().submit(proofs.get(0), now + 10L);
        assertEquals(MessageStatus.RETRYABLE, first.status());

        // 超过重试窗口（首次提交 now+10，窗口 1000ms）
        SubmitResult late = system.endpointB().retry("gas-3", now + 2_000L);
        assertEquals(MessageStatus.FAILED_FINAL, late.status());
        assertEquals(FailureReason.RETRY_WINDOW_EXPIRED, late.failureReason());
    }

    @Test
    void messageExpiryDuringRetryMarksExpired() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 1_000L);
        // 自定义一个有效期短于重试窗口的消息
        BridgeMessage msg = new BridgeMessage("gas-4", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "transfer:alice,bob,50", 999L, now, now + 500L, 500L, 100L,
                new RetryPolicy(5, 100_000L));
        SignedMessage signed = system.endpointA().originate(msg, now);
        List<MessageProof> proofs = system.endpointA().dispatchBatch(List.of(signed), system.endpointB());

        SubmitResult first = system.endpointB().submit(proofs.get(0), now + 10L);
        assertEquals(MessageStatus.RETRYABLE, first.status());
        SubmitResult late = system.endpointB().retry("gas-4", now + 600L);
        assertEquals(MessageStatus.EXPIRED, late.status());
        assertEquals(FailureReason.MESSAGE_EXPIRED, late.failureReason());
    }
}
