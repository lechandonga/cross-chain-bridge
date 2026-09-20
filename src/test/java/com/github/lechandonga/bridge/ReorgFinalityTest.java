package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 重组与最终性：未最终确定的执行可撤销并重新验证；已最终确定的不得回滚。 */
class ReorgFinalityTest {

    @Test
    void unfinalizedExecutionRevertedOnReorg() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("r1", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);
        BridgeResult executed = f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(MessageStatus.EXECUTED, executed.status());
        assertEquals(100, f.balanceOf("bob"));

        // 重组回滚 1 个区块（执行所在区块，尚未最终确定）
        long reverted = f.chainB.reorg(1);
        assertEquals(1, reverted);
        f.bridge.onReorg(reverted);

        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(0, f.balanceOf("bob"), "重组后账务必须回滚");
        assertEquals(0, f.collectedFees());
    }

    @Test
    void revertedMessageCanBeReexecuted() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("r2", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);
        f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());
        f.chainB.reorg(1);
        f.bridge.onReorg(1);

        // 重组后重新提交同一消息：重新验证并执行
        BridgeResult again = f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(MessageStatus.EXECUTED, again.status());
        assertEquals(100, f.balanceOf("bob"), "重新执行后账务恢复且只执行一次");
    }

    @Test
    void finalizedMessageSurvivesReorgAttempt() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("r3", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);
        f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());

        // 最终确定执行所在高度
        long execHeight = f.chainB.headHeight();
        f.chainB.finalizeUpTo(execHeight);
        assertEquals(MessageStatus.FINALIZED, f.bridge.statusOf(msg.id()).orElseThrow());

        // 试图重组到最终确定高度以下：被拒绝，状态不变
        long reverted = f.chainB.reorg(1);
        assertEquals(-1, reverted, "触及已最终确定区块的重组必须被拒绝");
        f.bridge.onReorg(0);
        assertEquals(MessageStatus.FINALIZED, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(100, f.balanceOf("bob"), "已最终确定的消息不得被回滚");
    }

    @Test
    void mixedFinalityDistinguishable() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage m1 = f.signedMessage("r4a", "transfer:bob:100");
        var d1 = f.prepareDelivery(m1);
        f.relayer.deliver(d1.message(), d1.proof(), d1.header());
        f.chainB.finalizeUpTo(f.chainB.headHeight()); // m1 最终确定

        CrossChainMessage m2 = f.signedMessage("r4b", "transfer:carol:200");
        var d2 = f.prepareDelivery(m2);
        f.relayer.deliver(d2.message(), d2.proof(), d2.header());

        // 只回滚 m2 所在区块
        assertEquals(1, f.chainB.reorg(1));
        f.bridge.onReorg(1);

        assertEquals(MessageStatus.FINALIZED, f.bridge.statusOf(m1.id()).orElseThrow());
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(m2.id()).orElseThrow());
        assertEquals(100, f.balanceOf("bob"), "已最终确定的账务保留");
        assertEquals(0, f.balanceOf("carol"), "未最终确定的账务回滚");
    }

    @Test
    void partialReorgOnlyRevertsMessagesInsideRolledBackRange() {
        BridgeTestFixture f = new BridgeTestFixture();
        // 三条消息依次执行，分别落在目标链高度 1、2、3
        CrossChainMessage m1 = f.signedMessage("r5a", "transfer:bob:10");
        CrossChainMessage m2 = f.signedMessage("r5b", "transfer:carol:20");
        CrossChainMessage m3 = f.signedMessage("r5c", "transfer:dave:30");
        for (CrossChainMessage m : List.of(m1, m2, m3)) {
            var d = f.prepareDelivery(m);
            assertEquals(MessageStatus.EXECUTED, f.relayer.deliver(d.message(), d.proof(), d.header()).status());
        }
        assertEquals(3, f.chainB.headHeight());

        // 只回滚最后 1 个区块：仅 m3 落在回滚范围内；m1/m2 即使未最终确定也保持 EXECUTED
        assertEquals(1, f.chainB.reorg(1));
        f.bridge.onReorg(1);

        assertEquals(MessageStatus.EXECUTED, f.bridge.statusOf(m1.id()).orElseThrow());
        assertEquals(MessageStatus.EXECUTED, f.bridge.statusOf(m2.id()).orElseThrow());
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(m3.id()).orElseThrow());
        assertEquals(10, f.balanceOf("bob"));
        assertEquals(20, f.balanceOf("carol"));
        assertEquals(0, f.balanceOf("dave"), "回滚范围之外的消息与账务不得被撤销");
    }

    @Test
    void finalizedExecutionsNeverRevertedEvenWhenDeepReorgRollsOthers() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage m1 = f.signedMessage("r6a", "transfer:bob:10");
        CrossChainMessage m2 = f.signedMessage("r6b", "transfer:carol:20");
        CrossChainMessage m3 = f.signedMessage("r6c", "transfer:dave:30");
        for (CrossChainMessage m : List.of(m1, m2, m3)) {
            var d = f.prepareDelivery(m);
            f.relayer.deliver(d.message(), d.proof(), d.header());
        }
        // m1 在高度 1 最终确定；重组只允许回滚高度 1 以上
        f.chainB.finalizeUpTo(1);
        // 回滚高度 2、3（深度 2）：m2/m3 撤销，m1 虽在深度之外也被最终确定，双保险
        assertEquals(2, f.chainB.reorg(2));
        f.bridge.onReorg(2);

        assertEquals(MessageStatus.FINALIZED, f.bridge.statusOf(m1.id()).orElseThrow());
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(m2.id()).orElseThrow());
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(m3.id()).orElseThrow());
        assertEquals(10, f.balanceOf("bob"), "已最终确定的账务在任何重组下都不得回滚");
        assertEquals(0, f.balanceOf("carol"));
        assertEquals(0, f.balanceOf("dave"));
    }
}
