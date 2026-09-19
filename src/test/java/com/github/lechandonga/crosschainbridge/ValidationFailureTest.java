package com.github.lechandonga.crosschainbridge;

import com.github.lechandonga.crosschainbridge.bridge.*;
import com.github.lechandonga.crosschainbridge.crypto.MerkleTreeBuilder;
import com.github.lechandonga.crosschainbridge.crypto.MessageSigner;
import com.github.lechandonga.crosschainbridge.model.*;
import com.github.lechandonga.crosschainbridge.support.TamperedProofs;
import com.github.lechandonga.crosschainbridge.support.TestMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidationFailureTest {

    private TwoChainSystem preparedSystem() {
        TwoChainSystem system = new TwoChainSystem(3);
        system.stateB().credit("bob", 50L);
        return system;
    }

    private List<MessageProof> oneProofAtoB(TwoChainSystem system, BridgeMessage msg, long now) {
        SignedMessage signed = system.endpointA().originate(msg, now);
        return system.endpointA().dispatchBatch(List.of(signed), system.endpointB());
    }

    @Test
    void blankFieldsAreRejected() {
        TwoChainSystem system = preparedSystem();
        long now = TestMessages.T0;
        BridgeMessage blank = TestMessages.blankId(ChainId.CHAIN_A, ChainId.CHAIN_B, now);
        MessageValidationException ex = assertThrows(MessageValidationException.class,
                () -> system.endpointA().originate(blank, now));
        assertEquals(FailureReason.NULL_OR_BLANK_FIELD, ex.reason());
    }

    @Test
    void sameSourceAndTargetIsRejectedAsInvalidChainId() {
        TwoChainSystem system = preparedSystem();
        long now = TestMessages.T0;
        BridgeMessage bad = TestMessages.wrongTarget("bad", ChainId.CHAIN_A, ChainId.CHAIN_A, now);
        MessageValidationException ex = assertThrows(MessageValidationException.class,
                () -> system.endpointA().originate(bad, now));
        assertEquals(FailureReason.INVALID_CHAIN_ID, ex.reason());
    }

    @Test
    void nonCounterpartChainIsRejectedAsInvalidChainId() {
        TwoChainSystem system = preparedSystem();
        long now = TestMessages.T0;
        // 只有两条链，source/target 非对端的另一类构造
        FailureReason reason = system.endpointA()
                .validateMessage(TestMessages.wrongTarget("bad2", ChainId.CHAIN_B, ChainId.CHAIN_B, now), now);
        assertEquals(FailureReason.INVALID_CHAIN_ID, reason);
    }

    @Test
    void expiredMessageIsRejected() {
        TwoChainSystem system = preparedSystem();
        long now = TestMessages.T0;
        BridgeMessage expired = TestMessages.expired("exp", ChainId.CHAIN_A, ChainId.CHAIN_B, now);
        MessageValidationException ex = assertThrows(MessageValidationException.class,
                () -> system.endpointA().originate(expired, now));
        assertEquals(FailureReason.MESSAGE_EXPIRED, ex.reason());
    }

    @Test
    void targetChainMismatchIsRejectedAtSubmit() {
        TwoChainSystem system = preparedSystem();
        long now = TestMessages.T0;
        // 消息声明目标为 A（由 A 签名发出的“回环”无法通过 originate，这里直接验证目标端判定）
        BridgeMessage msg = TestMessages.valid("mismatch", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "transfer:alice,bob,10", now);
        List<MessageProof> proofs = oneProofAtoB(system, msg, now);
        // 投递给 endpointA 而非声明的 endpointB
        SubmitResult result = system.endpointA().submit(proofs.get(0), now + 10L);
        assertFalse(result.accepted());
        assertEquals(FailureReason.TARGET_CHAIN_MISMATCH, result.failureReason());
        assertEquals(50L, system.stateB().balanceOf("bob"));
        // 目标端 B 上不应留下任何状态记录
        assertNull(system.endpointB().stateOf("mismatch"));
    }

    @Test
    void brokenMerkleProofIsRejectedAndStateUntouched() {
        TwoChainSystem system = preparedSystem();
        system.stateB().credit("alice", 1_000L);
        long now = TestMessages.T0;
        // 同一批放两条消息，保证 Merkle 路径非空
        BridgeMessage msg = TestMessages.transfer("p1", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10, now);
        BridgeMessage other = TestMessages.transfer("p1-other", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "carol", 5, now);
        SignedMessage s1 = system.endpointA().originate(msg, now);
        SignedMessage s2 = system.endpointA().originate(other, now);
        List<MessageProof> proofs = system.endpointA()
                .dispatchBatch(List.of(s1, s2), system.endpointB());

        SubmitResult result = system.endpointB()
                .submit(TamperedProofs.withBrokenMerklePath(proofs.get(0)), now + 10L);
        assertEquals(FailureReason.PROOF_NOT_COVERING_MESSAGE, result.failureReason());
        assertEquals(50L, system.stateB().balanceOf("bob"));
        // 被拒消息不占信箱：原证明随后仍可正常提交
        SubmitResult retry = system.endpointB().submit(proofs.get(0), now + 20L);
        assertTrue(retry.accepted());
        assertEquals(60L, system.stateB().balanceOf("bob"));
    }

    @Test
    void foreignLeafProofIsRejected() {
        TwoChainSystem system = preparedSystem();
        long now = TestMessages.T0;
        BridgeMessage msg = TestMessages.transfer("p2", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10, now);
        List<MessageProof> proofs = oneProofAtoB(system, msg, now);
        SubmitResult result = system.endpointB()
                .submit(TamperedProofs.withForeignLeaf(proofs.get(0)), now + 10L);
        assertEquals(FailureReason.PROOF_NOT_COVERING_MESSAGE, result.failureReason());
    }

    @Test
    void stateRootMismatchIsRejected() {
        TwoChainSystem system = preparedSystem();
        long now = TestMessages.T0;
        BridgeMessage msg = TestMessages.transfer("p3", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10, now);
        List<MessageProof> proofs = oneProofAtoB(system, msg, now);
        SubmitResult r1 = system.endpointB()
                .submit(TamperedProofs.withStateRootMismatch(proofs.get(0)), now + 10L);
        assertEquals(FailureReason.HEADER_STATE_ROOT_MISMATCH, r1.failureReason());
        SubmitResult r2 = system.endpointB()
                .submit(TamperedProofs.withHeaderHashMismatch(proofs.get(0)), now + 10L);
        assertEquals(FailureReason.HEADER_STATE_ROOT_MISMATCH, r2.failureReason());
        assertEquals(50L, system.stateB().balanceOf("bob"));
    }

    @Test
    void insufficientQuorumIsRejected() {
        TwoChainSystem system = preparedSystem();
        system.stateB().credit("alice", 1_000L);
        long now = TestMessages.T0;
        BridgeMessage msg = TestMessages.transfer("p4", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10, now);
        SignedMessage fullySigned = system.endpointA().originate(msg, now);
        byte[] hash = MessageSigner.messageHash(msg);
        List<MessageProof> fullProofs = system.endpointA()
                .dispatchBatch(List.of(fullySigned), system.endpointB());

        // 攻击者只拿到 2 名验证者（阈值 3）签名，但构造了不同 messageId 的伪造消息：
        // 其叶子哈希不在区块交易树中，先以“证明不覆盖”拒绝（属于证明失败类，同样不得放行）。
        BridgeMessage forged = TestMessages.transfer("p4-forged", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 999, now);
        SignedMessage underSigned = MessageSigner.sign(forged, system.validatorKeys().subList(0, 2));
        MerkleProof bogus = new MerkleProof(underSigned.messageHash(),
                fullProofs.get(0).merkleProof().path(),
                fullProofs.get(0).merkleProof().pathDirection(), 1, 0);
        MessageProof underProof = new MessageProof(underSigned, bogus,
                fullProofs.get(0).sourceHeader());

        SubmitResult result = system.endpointB().submit(underProof, now + 10L);
        assertEquals(FailureReason.PROOF_NOT_COVERING_MESSAGE, result.failureReason());

        // 另一场景：不足签名的消息本身真实存在于树中（与 p4 同批），则必须按仲裁不足拒绝
        BridgeMessage real2 = TestMessages.transfer("p4b", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10, now);
        SignedMessage full2 = system.endpointA().originate(real2, now);
        List<MessageProof> batch2 = system.endpointA()
                .dispatchBatch(List.of(full2), system.endpointB());
        SignedMessage under2 = MessageSigner.sign(real2, system.validatorKeys().subList(0, 2));
        MessageProof underProof2 = new MessageProof(under2, batch2.get(0).merkleProof(),
                batch2.get(0).sourceHeader());
        SubmitResult result2 = system.endpointB().submit(underProof2, now + 11L);
        assertEquals(FailureReason.INSUFFICIENT_VALIDATOR_QUORUM, result2.failureReason());

        assertEquals(50L, system.stateB().balanceOf("bob"));
        // 随后足阈值的完整证明仍可执行
        SubmitResult full = system.endpointB().submit(fullProofs.get(0), now + 20L);
        assertTrue(full.accepted());
        assertEquals(60L, system.stateB().balanceOf("bob"));
    }

    @Test
    void unknownHeaderIsRejected() {
        TwoChainSystem system = preparedSystem();
        long now = TestMessages.T0;
        BridgeMessage msg = TestMessages.transfer("p5", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10, now);
        List<MessageProof> proofs = oneProofAtoB(system, msg, now);
        // 换一个全新的系统（其轻客户端从未同步过该头）
        TwoChainSystem other = new TwoChainSystem(3);
        other.stateB().credit("bob", 50L);
        SubmitResult result = other.endpointB().submit(proofs.get(0), now + 10L);
        assertEquals(FailureReason.HEADER_STATE_ROOT_MISMATCH, result.failureReason());
        assertEquals(50L, other.stateB().balanceOf("bob"));
    }
}
