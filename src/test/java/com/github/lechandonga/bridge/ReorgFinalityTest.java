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

    /**
     * 一次重组只回滚部分区块：只有执行高度真正落在被回滚区间、且尚未最终确定的消息被撤销；
     * 已最终确定的永不回滚，未落在区间内的保持 EXECUTED。
     */
    @Test
    void partialReorgRevertsOnlyInRangeUnfinalized() {
        BridgeTestFixture f = new BridgeTestFixture();
        // m1 在高度 1，随后最终确定
        CrossChainMessage m1 = f.signedMessage("r5a", "transfer:a:10");
        var d1 = f.prepareDelivery(m1);
        f.relayer.deliver(d1.message(), d1.proof(), d1.header());
        f.chainB.finalizeUpTo(1);

        // m2 在高度 2、m3 在高度 3，均未最终确定
        CrossChainMessage m2 = f.signedMessage("r5b", "transfer:b:20");
        var d2 = f.prepareDelivery(m2);
        f.relayer.deliver(d2.message(), d2.proof(), d2.header());

        CrossChainMessage m3 = f.signedMessage("r5c", "transfer:c:30");
        var d3 = f.prepareDelivery(m3);
        f.relayer.deliver(d3.message(), d3.proof(), d3.header());

        assertEquals(3, f.chainB.headHeight());

        // 只回滚 1 个区块（高度 3）：仅 m3 被撤销
        assertEquals(1, f.bridge.handleReorg(1));
        assertEquals(MessageStatus.FINALIZED, f.bridge.statusOf(m1.id()).orElseThrow());
        assertEquals(MessageStatus.EXECUTED, f.bridge.statusOf(m2.id()).orElseThrow(),
                "未落在回滚区间内的消息保持原状态");
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(m3.id()).orElseThrow());
        assertEquals(10, f.balanceOf("a"));
        assertEquals(20, f.balanceOf("b"), "区间外的账务保留");
        assertEquals(0, f.balanceOf("c"), "区间内的账务回滚");

        // 再回滚 1 个区块（高度 2）：m2 被撤销，m1 仍最终确定
        assertEquals(1, f.bridge.handleReorg(1));
        assertEquals(MessageStatus.FINALIZED, f.bridge.statusOf(m1.id()).orElseThrow());
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(m2.id()).orElseThrow());
        assertEquals(0, f.balanceOf("b"), "m2 账务应已随第二次重组回滚");
        assertEquals(10, f.balanceOf("a"), "已最终确定的消息在任何重组下都不回滚");
    }

    /** 回滚多个区块时，FINALIZED 边界以下的执行必须保留（链也会拒绝越界深度）。 */
    @Test
    void reorgBeyondFinalizedIsRejectedLeavingStateIntact() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage m1 = f.signedMessage("r6a", "transfer:a:11");
        var d1 = f.prepareDelivery(m1);
        f.relayer.deliver(d1.message(), d1.proof(), d1.header());
        f.chainB.finalizeUpTo(1);
        CrossChainMessage m2 = f.signedMessage("r6b", "transfer:b:22");
        var d2 = f.prepareDelivery(m2);
        f.relayer.deliver(d2.message(), d2.proof(), d2.header());

        long reverted = f.bridge.handleReorg(2); // 回滚后落到高度 1 < 最终确定高度，拒绝
        assertEquals(-1, reverted);
        assertEquals(MessageStatus.FINALIZED, f.bridge.statusOf(m1.id()).orElseThrow());
        assertEquals(MessageStatus.EXECUTED, f.bridge.statusOf(m2.id()).orElseThrow(),
                "被拒绝的重组不得撤销任何消息");
        assertEquals(11, f.balanceOf("a"));
        assertEquals(22, f.balanceOf("b"));
    }
}
