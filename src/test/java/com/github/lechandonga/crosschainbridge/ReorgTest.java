package com.github.lechandonga.crosschainbridge;

import com.github.lechandonga.crosschainbridge.bridge.*;
import com.github.lechandonga.crosschainbridge.chain.FinalizedRollbackException;
import com.github.lechandonga.crosschainbridge.model.*;
import com.github.lechandonga.crosschainbridge.support.TestMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReorgTest {

    @Test
    void unfinalizedMessageIsRevokedThenReIncludedAndExecuted() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 1_000L);
        system.stateB().credit("bob", 0L);
        system.stateA().credit("alice", 1_000L);

        BridgeMessage msg = TestMessages.transfer("reorg-1", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 100L, now);
        SignedMessage signed = system.endpointA().originate(msg, now);
        List<MessageProof> proofs = system.endpointA()
                .dispatchBatch(List.of(signed), system.endpointB());

        // 消息在 h=1，尚未最终确定；B 已验证并执行
        SubmitResult executed = system.endpointB().submit(proofs.get(0), now + 10L);
        assertTrue(executed.accepted());
        assertEquals(100L, system.stateB().balanceOf("bob"));

        // A 在高度 0 处重组（h=1 被替换，未最终）；新头先送达 B 的轻客户端，旧头被替换
        List<List<byte[]>> newChain = List.of(List.of(
                com.github.lechandonga.crosschainbridge.crypto.HashUtils.sha256("some-other-tx")));
        system.chainA().reorg(0L, newChain);
        system.lightClientOnB().ingestHeader(system.chainA().headerAt(1L));

        // 重组通知：B 撤销所有落在被替换区间且未最终的消息
        ReorgResult result = system.endpointB().handleReorg(0L, now + 20L);
        assertFalse(result.rollbackAttempted());
        assertTrue(result.revoked().contains("reorg-1"));
        assertTrue(result.finalizedKept().isEmpty());
        assertEquals(MessageStatus.REVOKED, system.endpointB().stateOf("reorg-1").status());
        // 已执行但未最终的账务效果被补偿回滚
        assertEquals(0L, system.stateB().balanceOf("bob"));
        assertEquals(1_000L, system.stateB().balanceOf("alice"));

        // 重组后旧证明不再有效，必须被重新验证拒绝
        SubmitResult stale = system.endpointB().submit(proofs.get(0), now + 30L);
        assertEquals(FailureReason.HEADER_STATE_ROOT_MISMATCH, stale.failureReason());

        // 消息在新链上被重新打包（新的 h=1 包含同一消息），重新出块、同步头、重新提交执行
        List<byte[]> newBlock = List.of(proofs.get(0).signedMessage().messageHash());
        system.chainA().reorg(0L, List.of(newBlock));
        system.endpointA().syncCanonicalHeaders(1L, system.endpointB());
        BlockHeader newHeader = system.chainA().headerAt(1L);
        MerkleProof newMerkle = new com.github.lechandonga.crosschainbridge.crypto.MerkleTreeBuilder(newBlock)
                .proofFor(0, 1L);
        MessageProof newProof = new MessageProof(proofs.get(0).signedMessage(), newMerkle, newHeader);

        SubmitResult reExecuted = system.endpointB().submit(newProof, now + 40L);
        assertTrue(reExecuted.accepted());
        assertEquals(MessageStatus.EXECUTED, reExecuted.status());
        // 重新包含并执行后账务再次生效，且只生效一次
        assertEquals(100L, system.stateB().balanceOf("bob"));
        assertEquals(900L, system.stateB().balanceOf("alice"));
    }

    @Test
    void finalizedExecutionCannotBeRolledBack() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 1_000L);
        system.stateB().credit("bob", 0L);

        BridgeMessage msg = TestMessages.transfer("reorg-2", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 100L, now);
        SignedMessage signed = system.endpointA().originate(msg, now);
        List<MessageProof> proofs = system.endpointA()
                .dispatchBatch(List.of(signed), system.endpointB());
        assertTrue(system.endpointB().submit(proofs.get(0), now + 10L).accepted());

        // h=1 最终确定
        system.chainA().finalizeUpTo(1L);
        system.endpointB().markFinalizedUpTo(1L, now + 15L);

        // 尝试在最终高度之下重组：来源链直接拒绝
        FinalizedRollbackException ex = assertThrows(FinalizedRollbackException.class,
                () -> system.chainA().reorg(0L,
                        List.of(List.of(com.github.lechandonga.crosschainbridge.crypto.HashUtils
                                .sha256("other")))));
        assertEquals(1L, ex.finalizedHeight());

        // 端点侧处理结果同样明确标记“回滚最终区块被拒绝”，且消息保留
        ReorgResult result = system.endpointB().handleReorg(0L, now + 20L);
        assertTrue(result.rollbackAttempted());
        assertEquals(FailureReason.FINALIZED_BLOCK_ROLLBACK_REJECTED, result.failureReason());
        assertTrue(result.finalizedKept().contains("reorg-2"));
        assertTrue(result.revoked().isEmpty());
        assertEquals(MessageStatus.EXECUTED, system.endpointB().stateOf("reorg-2").status());
        assertEquals(100L, system.stateB().balanceOf("bob"));
    }

    @Test
    void onlyUnfinalizedMessagesAboveForkAreRevoked() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 5_000L);

        // h=1 最终确定的消息
        BridgeMessage m1 = TestMessages.transfer("r-keep", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10L, now);
        SignedMessage s1 = system.endpointA().originate(m1, now);
        List<MessageProof> p1 = system.endpointA().dispatchBatch(List.of(s1), system.endpointB());
        assertTrue(system.endpointB().submit(p1.get(0), now + 5L).accepted());
        system.chainA().finalizeUpTo(1L);
        system.endpointB().markFinalizedUpTo(1L, now + 6L);

        // h=2 未最终的消息
        BridgeMessage m2 = TestMessages.transfer("r-drop", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "carol", 20L, now + 1L);
        SignedMessage s2 = system.endpointA().originate(m2, now + 1L);
        List<MessageProof> p2 = system.endpointA().dispatchBatch(List.of(s2), system.endpointB());
        assertTrue(system.endpointB().submit(p2.get(0), now + 12L).accepted());

        // 在 h=1 分叉：h=2 被替换
        List<List<byte[]>> fork = List.of(
                List.of(com.github.lechandonga.crosschainbridge.crypto.HashUtils.sha256("replacement")));
        system.chainA().reorg(1L, fork);

        ReorgResult result = system.endpointB().handleReorg(1L, now + 30L);
        assertFalse(result.rollbackAttempted());
        assertTrue(result.finalizedKept().contains("r-keep"));
        assertTrue(result.revoked().contains("r-drop"));
        assertEquals(MessageStatus.EXECUTED, system.endpointB().stateOf("r-keep").status());
        assertEquals(MessageStatus.REVOKED, system.endpointB().stateOf("r-drop").status());
    }
}
