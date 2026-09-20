package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.crypto.Signing;
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
        // 用与篡改头匹配的真实签名，排除签名因素
        BlockHeader signedTampered = f.signedHeader(tampered, BridgeTestFixture.VALIDATORS);
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
        BlockHeader weak = f.signedHeader(header, List.of("val1"));
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
        BlockHeader signed = f.signedHeader(header, BridgeTestFixture.VALIDATORS);
        MerkleProof proof = f.proofFor(msg);
        BridgeResult result = f.bridge.submit(msg, proof, signed);
        assertEquals(RejectionReason.PROOF_NOT_COVERING, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void forgedValidatorSignatureRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v5", "transfer:bob:50");
        f.chainA.applyNow("outbox:" + msg.id(), msg.contentHash());
        BlockHeader header = f.chainA.commitBlock();
        // 用验证者集合之外的私钥伪造签名（再配一个真实签名，凑数也不能放行）
        var outsider = Signing.generateKeyPair();
        String forged1 = LightClient.sign(outsider.getPrivate(), header);
        String forged2 = LightClient.sign(Signing.generateKeyPair().getPrivate(), header);
        BlockHeader fake = new BlockHeader(header.chainId(), header.height(), header.parentHash(),
                header.stateRoot(), header.timestamp(), List.of(forged1, forged2));
        MerkleProof proof = f.proofFor(msg);
        BridgeResult result = f.bridge.submit(msg, proof, fake);
        assertEquals(RejectionReason.CREDENTIAL_MALFORMED, result.reason(),
                "伪造的验证者授权不能参与凑够门槛");
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void duplicatedValidatorSignatureNotCountedTwice() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v6", "transfer:bob:50");
        f.chainA.applyNow("outbox:" + msg.id(), msg.contentHash());
        BlockHeader header = f.chainA.commitBlock();
        MerkleProof proof = f.proofFor(msg);
        // 同一个验证者签名出现两次：阈值是 2，重复授权不得凑数
        String sig = LightClient.sign(f.validatorKeyOf("val1").getPrivate(), header);
        BlockHeader duplicated = new BlockHeader(header.chainId(), header.height(),
                header.parentHash(), header.stateRoot(), header.timestamp(), List.of(sig, sig));
        BridgeResult result = f.bridge.submit(msg, proof, duplicated);
        assertEquals(RejectionReason.DUPLICATE_AUTHORIZATION, result.reason());
        assertEquals(0, f.balanceOf("bob"));
        assertTrue(f.chainB.stateView().isEmpty());
    }

    @Test
    void revokedSourceHeaderRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v7", "transfer:bob:50");
        var delivery = f.prepareDelivery(msg);
        // 来源链发生重组，旧区块头被标记撤销
        f.lightClient.markHeaderRevoked(BridgeTestFixture.CHAIN_A, delivery.header());
        BridgeResult result = f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(RejectionReason.SOURCE_HEADER_REVOKED, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }
}
