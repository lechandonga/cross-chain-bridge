package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 手续费与资源限额：执行超限进入可重试状态；重试耗尽进入确定终态。 */
class GasAndRetryTest {

    @Test
    void gasExceededBecomesRetryableNotLost() {
        BridgeTestFixture f = new BridgeTestFixture();
        // gasLimit 低于执行成本（BASE_GAS=100 + payload 长度）
        CrossChainMessage msg = f.signedMessage("g1", "alice", "transfer:bob:100",
                10, 50, 1_000, 3);
        var delivery = f.prepareDelivery(msg);
        BridgeResult result = f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());

        assertEquals(MessageStatus.RETRYABLE, result.status(), "超限消息必须进入可重试状态而非丢失");
        assertEquals(MessageStatus.RETRYABLE, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(0, f.balanceOf("bob"), "未成功执行不得记账");
    }

    @Test
    void retriesExhaustedGivesFinalFailure() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("g2", "alice", "transfer:bob:100",
                10, 50, 1_000, 2);
        var delivery = f.prepareDelivery(msg);
        f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());

        // 重试 2 次后耗尽
        assertEquals(MessageStatus.RETRYABLE, f.bridge.retry(msg.id()).status());
        BridgeResult last = f.bridge.retry(msg.id());
        assertEquals(MessageStatus.FAILED_FINAL, last.status());
        assertEquals(RejectionReason.RETRIES_EXHAUSTED, last.reason());
        assertEquals(MessageStatus.FAILED_FINAL, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(0, f.balanceOf("bob"), "终态失败不得有账务残留");
    }

    @Test
    void sufficientGasExecutesAndCollectsFee() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("g3", "alice", "transfer:bob:100",
                25, 10_000, 1_000, 0);
        var delivery = f.prepareDelivery(msg);
        BridgeResult result = f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(MessageStatus.EXECUTED, result.status());
        assertEquals(100, f.balanceOf("bob"));
        assertEquals(25, f.collectedFees(), "手续费按消息声明收取");
    }
}
