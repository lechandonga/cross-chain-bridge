package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.lightclient.LightClient;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.RejectionReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 轻客户端验证：证明不覆盖、状态根不一致、验证者不足，均不得放行且不污染状态。 */
class LightClientVerificationTest {

    @Test
    void proofNotCoveringRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v1", "transfer:bob:50");
        BlockHeader header = f.commitOnSource(msg);
        // 证明指向另一个 key，无法覆盖目标消息
        f.chainA.applyNow("outbox:other", "x");
        MerkleProof wrongProof = MerkleProof.build(f.chainA.stateView(), "outbox:other");
        BridgeResult result = f.bridge.submit(msg, wrongProof, header);
        assertEquals(RejectionReason.PROOF_NOT_COVERING, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void stateRootMismatchRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v2", "transfer:bob:50");
        BlockHeader header = f.commitOnSource(msg);
        MerkleProof proof = f.proofFor(msg);
        // 篡改区块头状态根
        BlockHeader tampered = new BlockHeader(header.chainId(), header.height(),
                header.parentHash(), "deadbeef", header.timestamp(), header.validatorSignatures());
        // 用与篡改头匹配的签名，排除签名因素
        BlockHeader signedTampered = new BlockHeader(tampered.chainId(), tampered.height(),
                tampered.parentHash(), tampered.stateRoot(), tampered.timestamp(),
                BridgeTestFixture.VALIDATORS.stream().map(v -> LightClient.sign(v, tampered)).toList());
        BridgeResult result = f.bridge.submit(msg, proof, signedTampered);
        assertEquals(RejectionReason.STATE_ROOT_MISMATCH, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void insufficientValidatorsRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v3", "transfer:bob:50");
        f.chainA.applyNow("outbox:" + msg.id(), msg.contentHash());
        BlockHeader header = f.chainA.commitBlock();
        MerkleProof proof = f.proofFor(msg);
        // 只有 1 个验证者签名，低于阈值 2
        BlockHeader weak = new BlockHeader(header.chainId(), header.height(), header.parentHash(),
                header.stateRoot(), header.timestamp(),
                List.of(LightClient.sign("val1", header)));
        BridgeResult result = f.bridge.submit(msg, proof, weak);
        assertEquals(RejectionReason.INSUFFICIENT_VALIDATORS, result.reason());
        assertEquals(0, f.balanceOf("bob"));
        assertTrue(f.chainB.stateView().isEmpty(), "被拒绝的消息不得改变目标链状态");
    }

    @Test
    void proofValueMustMatchMessage() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v4", "transfer:bob:50");
        // outbox 中登记的是另一条消息的哈希
        f.chainA.applyNow("outbox:" + msg.id(), "other-content-hash");
        BlockHeader header = f.chainA.commitBlock();
        BlockHeader signed = new BlockHeader(header.chainId(), header.height(), header.parentHash(),
                header.stateRoot(), header.timestamp(),
                BridgeTestFixture.VALIDATORS.stream().map(v -> LightClient.sign(v, header)).toList());
        MerkleProof proof = f.proofFor(msg);
        BridgeResult result = f.bridge.submit(msg, proof, signed);
        assertEquals(RejectionReason.PROOF_NOT_COVERING, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }
}
