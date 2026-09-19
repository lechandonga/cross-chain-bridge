package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import org.junit.jupiter.api.Test;

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
}
