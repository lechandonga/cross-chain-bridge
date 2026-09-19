package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import com.github.lechandonga.bridge.model.RetryPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 提交校验：字段缺失、链标识不匹配、超有效期、签名无效，各原因可区分且不污染状态。 */
class SubmissionValidationTest {

    @Test
    void missingFieldRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = new CrossChainMessage("m1", BridgeTestFixture.CHAIN_A,
                BridgeTestFixture.CHAIN_B, "alice", 1, "", 10, 10_000, 0, 1000,
                new RetryPolicy(3), "sig");
        BridgeResult result = f.bridge.submit(msg, null, null);
        assertEquals(MessageStatus.REJECTED, result.status());
        assertEquals(RejectionReason.MISSING_FIELD, result.reason());
        assertTrue(f.chainB.stateView().isEmpty(), "被拒绝的消息不得改变目标链状态");
    }

    @Test
    void chainIdMismatchRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage base = f.signedMessage("m2", "transfer:bob:100");
        CrossChainMessage wrong = new CrossChainMessage(base.id(), base.sourceChain(),
                "chain-c", base.sender(), base.nonce(), base.payload(), base.fee(),
                base.gasLimit(), base.validFrom(), base.validUntil(), base.retryPolicy(),
                base.signature());
        BridgeResult result = f.bridge.submit(wrong, null, null);
        assertEquals(RejectionReason.CHAIN_ID_MISMATCH, result.reason());
        assertTrue(f.chainB.stateView().isEmpty());
    }

    @Test
    void expiredMessageRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        f.chainB.commitBlock();
        f.chainB.commitBlock();
        CrossChainMessage msg = f.signedMessage("m3", "alice", "transfer:bob:100",
                10, 10_000, 1, 3); // validUntil=1 < 当前高度 2
        BridgeResult result = f.bridge.submit(msg, f.proofFor(msg), null);
        assertEquals(RejectionReason.EXPIRED, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void invalidSignatureRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("m4", "transfer:bob:100");
        CrossChainMessage forged = new CrossChainMessage(msg.id(), msg.sourceChain(),
                msg.targetChain(), msg.sender(), msg.nonce(), msg.payload(), msg.fee(),
                msg.gasLimit(), msg.validFrom(), msg.validUntil(), msg.retryPolicy(),
                "sig:mallory:" + msg.contentHash());
        BridgeResult result = f.bridge.submit(forged, null, null);
        assertEquals(RejectionReason.INVALID_SIGNATURE, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void happyPathExecutes() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("m5", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);
        BridgeResult result = f.relayer.deliver(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(MessageStatus.EXECUTED, result.status());
        assertEquals(100, f.balanceOf("bob"));
        assertEquals(10, f.collectedFees());
    }
}
