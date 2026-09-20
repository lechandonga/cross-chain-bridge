package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import com.github.lechandonga.bridge.model.RetryPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 重复投递与重放防护：同一消息只执行一次；标识碰撞与时间窗口过期被拒绝。 */
class ReplayProtectionTest {

    @Test
    void duplicateDeliveryExecutedOnlyOnce() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("p1", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);

        BridgeResult first = f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());
        BridgeResult second = f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());
        BridgeResult third = f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());

        assertEquals(MessageStatus.EXECUTED, first.status());
        assertEquals(RejectionReason.REPLAY, second.reason());
        assertEquals(RejectionReason.REPLAY, third.reason());
        assertEquals(100, f.balanceOf("bob"), "重复投递只能有效执行一次");
        assertEquals(10, f.collectedFees(), "手续费也只收取一次");
    }

    @Test
    void idCollisionRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("p2", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);
        f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());

        // 相同 id、不同内容（金额不同）
        CrossChainMessage collision = f.signedMessage("p2", "transfer:bob:999");
        var d2 = f.prepareDelivery(collision);
        BridgeResult result = f.relayer.deliver(d2.message(), d2.proof(), d2.header());

        assertEquals(RejectionReason.ID_COLLISION, result.reason());
        assertEquals(100, f.balanceOf("bob"), "碰撞消息不得改变已有账务");
    }

    @Test
    void expiredWindowRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        // 推进目标链高度，使消息窗口过期
        for (int i = 0; i < 5; i++) {
            f.chainB.commitBlock();
        }
        CrossChainMessage msg = f.signedMessage("p3", "alice", "transfer:bob:100",
                10, 10_000, 2, 3); // validUntil=2 < 当前高度 5
        var delivery = f.prepareDelivery(msg);
        BridgeResult result = f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(RejectionReason.EXPIRED, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void notYetValidRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage unsigned = new CrossChainMessage("p4", BridgeTestFixture.CHAIN_A,
                BridgeTestFixture.CHAIN_B, "alice", 1, "transfer:bob:100", 10, 10_000,
                100, 1000, new RetryPolicy(3), null);
        CrossChainMessage msg = new CrossChainMessage(unsigned.id(), unsigned.sourceChain(),
                unsigned.targetChain(), unsigned.sender(), unsigned.nonce(), unsigned.payload(),
                unsigned.fee(), unsigned.gasLimit(), unsigned.validFrom(), unsigned.validUntil(),
                unsigned.retryPolicy(), f.credentialOf("alice", unsigned));
        var delivery = f.prepareDelivery(msg);
        BridgeResult result = f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(RejectionReason.NOT_YET_VALID, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }
}
