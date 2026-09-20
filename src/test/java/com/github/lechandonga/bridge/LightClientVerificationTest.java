package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.crypto.Signatures;
import com.github.lechandonga.bridge.lightclient.LightClient;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 轻客户端验证：包含证明、状态根、授权凭证四类失败可区分（伪造/越权/重复/不足），
 * 以及被重组取代的区块头不得放行；任何失败都不污染目标链状态。
 */
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
        f.commitOnSource(msg);
        MerkleProof proof = f.proofFor(msg);
        // 取真实头，仅篡改状态根并用验证者重新授权，再把它作为同高度新正统头观察，
        // 从而隔离掉正统性因素，单独检验状态根口径
        BlockHeader real = f.chainA.headerAt(1).orElseThrow();
        BlockHeader tampered = new BlockHeader(real.chainId(), real.height(), real.parentHash(),
                "deadbeef", real.timestamp(),
                f.validatorSignatures(new BlockHeader(real.chainId(), real.height(), real.parentHash(),
                        "deadbeef", real.timestamp(), List.of()), List.of("val1", "val2")));
        f.lightClient.observeCanonical(tampered);
        BridgeResult result = f.bridge.submit(msg, proof, tampered);
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
        // 只有 1 个验证者授权，低于阈值 2
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
        BlockHeader signed = f.signedHeader(header, List.of("val1", "val2"));
        f.lightClient.observeCanonical(signed);
        MerkleProof proof = f.proofFor(msg);
        BridgeResult result = f.bridge.submit(msg, proof, signed);
        assertEquals(RejectionReason.PROOF_NOT_COVERING, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void forgedAuthorizationRejectedAndDoesNotCount() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v5", "transfer:bob:50");
        f.chainA.applyNow("outbox:" + msg.id(), msg.contentHash());
        BlockHeader header = f.chainA.commitBlock();
        MerkleProof proof = f.proofFor(msg);
        // 一份真授权 + 一份伪造/篡改凭证：伪造的不能凑门槛
        BlockHeader forged = new BlockHeader(header.chainId(), header.height(), header.parentHash(),
                header.stateRoot(), header.timestamp(),
                List.of(f.validatorSignatures(header, List.of("val1")).get(0),
                        "cred:v1:val2:Tm90QVJlYWxTaWduYXR1cmU="));
        BridgeResult result = f.bridge.submit(msg, proof, forged);
        assertEquals(RejectionReason.INVALID_SIGNATURE, result.reason());
        assertEquals(0, f.balanceOf("bob"), "伪造授权不得凑够门槛");
    }

    @Test
    void outsiderAuthorizationRejectedAndDoesNotCount() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v6", "transfer:bob:50");
        f.chainA.applyNow("outbox:" + msg.id(), msg.contentHash());
        BlockHeader header = f.chainA.commitBlock();
        MerkleProof proof = f.proofFor(msg);
        KeyPair eve = f.registerOutsider("eve");
        // val1（集合内）+ eve（密钥真实但不在验证者集合）
        BlockHeader outsider = new BlockHeader(header.chainId(), header.height(), header.parentHash(),
                header.stateRoot(), header.timestamp(),
                List.of(f.validatorSignatures(header, List.of("val1")).get(0),
                        Signatures.issue(eve.getPrivate(), "eve", header.hash())));
        BridgeResult result = f.bridge.submit(msg, proof, outsider);
        assertEquals(RejectionReason.AUTHORIZER_NOT_AUTHORIZED, result.reason());
        assertEquals(0, f.balanceOf("bob"), "越权主体的授权不得凑门槛");
    }

    @Test
    void duplicateAuthorizationRejectedAndCountsOnce() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v7", "transfer:bob:50");
        f.chainA.applyNow("outbox:" + msg.id(), msg.contentHash());
        BlockHeader header = f.chainA.commitBlock();
        MerkleProof proof = f.proofFor(msg);
        String val1Sig = f.validatorSignatures(header, List.of("val1")).get(0);
        // val1 的同一份授权被重复列入多次，仍只计一次，无法达到阈值 2
        BlockHeader dup = new BlockHeader(header.chainId(), header.height(), header.parentHash(),
                header.stateRoot(), header.timestamp(), List.of(val1Sig, val1Sig, val1Sig));
        BridgeResult result = f.bridge.submit(msg, proof, dup);
        assertEquals(RejectionReason.DUPLICATE_AUTHORIZER, result.reason());
        assertEquals(0, f.balanceOf("bob"), "同一验证者重复计入不得凑够门槛");
    }

    @Test
    void duplicateIgnoredWhenUniqueAuthorizationsReachThreshold() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v8", "transfer:bob:50");
        f.chainA.applyNow("outbox:" + msg.id(), msg.contentHash());
        BlockHeader header = f.chainA.commitBlock();
        MerkleProof proof = f.proofFor(msg);
        String val1Sig = f.validatorSignatures(header, List.of("val1")).get(0);
        String val2Sig = f.validatorSignatures(header, List.of("val2")).get(0);
        // 去重后 val1+val2 达阈值，重复项被忽略，验证通过
        BlockHeader withDup = new BlockHeader(header.chainId(), header.height(), header.parentHash(),
                header.stateRoot(), header.timestamp(), List.of(val1Sig, val1Sig, val2Sig));
        f.lightClient.observeCanonical(withDup);
        BridgeResult result = f.bridge.submit(msg, proof, withDup);
        assertEquals(MessageStatus.EXECUTED, result.status());
        assertEquals(50, f.balanceOf("bob"));
    }

    @Test
    void supersededHeaderRejectedAfterSourceReorg() {
        BridgeTestFixture f = new BridgeTestFixture();
        // 旧链：消息 m-old 包含在高度 1 的旧分叉头里
        CrossChainMessage oldMsg = f.signedMessage("v9a", "transfer:bob:77");
        BlockHeader oldHeader = f.commitOnSource(oldMsg);
        MerkleProof oldProof = f.proofFor(oldMsg);

        // 来源链重组掉高度 1，再以不同内容重新出块，轻客户端正统头切换到新头
        assertEquals(1, f.chainA.reorg(1));
        f.chainA.applyNow("outbox:v9b", "different-content");
        f.chainA.commitBlock();
        f.syncSourceCanonicalAfterReorg();

        // 用旧分叉头与旧证明投递：来源验证必须判为已被取代
        BridgeResult result = f.bridge.submit(oldMsg, oldProof, oldHeader);
        assertEquals(RejectionReason.SOURCE_HEADER_SUPERSEDED, result.reason());
        assertEquals(0, f.balanceOf("bob"), "来源证明失效后不得放行");
        assertTrue(f.chainB.stateView().isEmpty(), "验证失败不得改变目标链状态");
    }

    @Test
    void validAuthorizationStillExecutes() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("v10", "transfer:bob:50");
        var delivery = f.prepareDelivery(msg);
        BridgeResult result = f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(MessageStatus.EXECUTED, result.status());
        assertEquals(50, f.balanceOf("bob"));
    }
}
