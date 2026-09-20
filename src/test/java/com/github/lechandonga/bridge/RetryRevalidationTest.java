package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重新执行前的来源再验证：
 * 无论是资源超限后的重试，还是被重组撤销后的重新提交，都必须重新完成一次
 * 完整来源验证（区块头授权 + 正统性 + Merkle 包含）。来源证明已失效或被替换时，
 * 给出可区分原因、不改变目标链状态；重试场景下也不消耗重试预算。
 */
class RetryRevalidationTest {

    /** 基线：来源仍有效时，提高 gasLimit 无法改变已提交消息（重试沿用原消息），
     *  这里验证来源有效时重试走完校验后仍会因资源超限回到 RETRYABLE，预算正常递减。 */
    @Test
    void retryRevalidatesWhileSourceValid() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("x1", "alice", "transfer:bob:100",
                10, 50, 1_000, 3);
        var d = f.prepareDelivery(msg);
        assertEquals(MessageStatus.RETRYABLE,
                f.bridge.submit(d.message(), d.proof(), d.header()).status());

        BridgeResult retry = f.bridge.retry(msg.id());
        assertEquals(MessageStatus.RETRYABLE, retry.status(), "来源有效，重试进入正常判定");
        assertEquals(MessageStatus.RETRYABLE, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(0, f.balanceOf("bob"));
    }

    /** RETRYABLE 期间来源链重组，旧区块头被取代：重试必须在执行前被来源验证拦下。 */
    @Test
    void retryAfterSourceReorgRejectedAndKeepsRetryBudget() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("x2", "alice", "transfer:bob:100",
                10, 50, 1_000, 3);
        var d = f.prepareDelivery(msg);
        assertEquals(MessageStatus.RETRYABLE,
                f.bridge.submit(d.message(), d.proof(), d.header()).status());

        // 来源链重组掉消息所在高度，换一条不同内容的新链，轻客户端正统头切换
        assertEquals(1, f.chainA.reorg(1));
        f.chainA.applyNow("outbox:other", "zz");
        f.chainA.commitBlock();
        f.syncSourceCanonicalAfterReorg();

        BridgeResult r1 = f.bridge.retry(msg.id());
        assertEquals(RejectionReason.SOURCE_HEADER_SUPERSEDED, r1.reason());
        assertEquals(MessageStatus.RETRYABLE, f.bridge.statusOf(msg.id()).orElseThrow(),
                "再验证失败不得改变消息阶段");
        assertEquals(0, f.balanceOf("bob"));

        // 再验证失败不消耗重试预算：来源恢复后仍可重试 3 次（此处直接验证可重复拦截且状态不变）
        BridgeResult r2 = f.bridge.retry(msg.id());
        assertEquals(RejectionReason.SOURCE_HEADER_SUPERSEDED, r2.reason());
        assertEquals(MessageStatus.RETRYABLE, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(0, f.collectedFees(), "再验证失败不产生任何目标链账务");
    }

    /** 重组撤销后重新提交：来源区块头仍是当前正统头时，重新完整验证并执行一次。 */
    @Test
    void resubmitAfterTargetReorgRevalidatesAndExecutes() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("x3", "transfer:bob:100");
        var d = f.prepareDelivery(msg);
        f.bridge.submit(d.message(), d.proof(), d.header());
        // 仅目标链重组；来源链未动，旧来源头依旧正统
        assertEquals(1, f.bridge.handleReorg(1));
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(0, f.balanceOf("bob"));

        BridgeResult again = f.bridge.submit(d.message(), d.proof(), d.header());
        assertEquals(MessageStatus.EXECUTED, again.status(), "重新提交必须重新完成来源验证后执行");
        assertEquals(100, f.balanceOf("bob"), "重新执行后账务恢复且只执行一次");
        assertEquals(10, f.collectedFees());
    }

    /** 目标链撤销后，来源证明也已被来源重组取代：重新提交必须被拦截，目标链状态不变。 */
    @Test
    void resubmitAfterBothChainsReorgRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("x4", "transfer:bob:100");
        var d = f.prepareDelivery(msg);
        f.bridge.submit(d.message(), d.proof(), d.header());

        // 目标链撤销
        assertEquals(1, f.bridge.handleReorg(1));
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(msg.id()).orElseThrow());
        // 来源链也重组，消息所在旧头不再正统
        assertEquals(1, f.chainA.reorg(1));
        f.chainA.applyNow("outbox:other", "qq");
        f.chainA.commitBlock();
        f.syncSourceCanonicalAfterReorg();

        BridgeResult result = f.bridge.submit(d.message(), d.proof(), d.header());
        assertEquals(RejectionReason.SOURCE_HEADER_SUPERSEDED, result.reason());
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(msg.id()).orElseThrow(),
                "再验证失败不得改变消息状态");
        assertEquals(0, f.balanceOf("bob"));
        assertEquals(0, f.collectedFees());
    }

    /** 再验证反复失败不得消耗重试预算：超过 maxRetries 次后仍停留在 RETRYABLE，而非 FAILED_FINAL。 */
    @Test
    void failedRevalidationNeverConsumesRetryBudget() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("x5", "alice", "transfer:bob:100",
                10, 50, 1_000, 2);
        var d = f.prepareDelivery(msg);
        assertEquals(MessageStatus.RETRYABLE,
                f.bridge.submit(d.message(), d.proof(), d.header()).status());

        assertEquals(1, f.chainA.reorg(1));
        f.chainA.applyNow("outbox:other", "qq");
        f.chainA.commitBlock();
        f.syncSourceCanonicalAfterReorg();

        // maxRetries=2；若失败的再验证错误地消耗预算，第 3 次就会 FAILED_FINAL
        for (int i = 0; i < 4; i++) {
            BridgeResult r = f.bridge.retry(msg.id());
            assertEquals(RejectionReason.SOURCE_HEADER_SUPERSEDED, r.reason(),
                    "第 " + i + " 次重试仍应被来源验证拦截");
            assertEquals(MessageStatus.RETRYABLE, f.bridge.statusOf(msg.id()).orElseThrow());
        }
        assertEquals(0, f.balanceOf("bob"));
        assertEquals(0, f.collectedFees());
    }
}
