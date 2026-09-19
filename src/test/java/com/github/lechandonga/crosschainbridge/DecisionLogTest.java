package com.github.lechandonga.crosschainbridge;

import com.github.lechandonga.crosschainbridge.bridge.*;
import com.github.lechandonga.crosschainbridge.logging.DecisionLog;
import com.github.lechandonga.crosschainbridge.model.*;
import com.github.lechandonga.crosschainbridge.support.TamperedProofs;
import com.github.lechandonga.crosschainbridge.support.TestMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecisionLogTest {

    @Test
    void everyDecisionCarriesMessageIdReasonAndBasis() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 1_000L);

        BridgeMessage msg = TestMessages.transfer("log-1", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10, now);
        SignedMessage signed = system.endpointA().originate(msg, now);
        List<MessageProof> proofs = system.endpointA().dispatchBatch(List.of(signed), system.endpointB());
        system.endpointB().submit(proofs.get(0), now + 10L);
        // 重复投递再记一条
        system.endpointB().submit(proofs.get(0), now + 20L);

        DecisionLog log = system.log();
        List<DecisionLog.Entry> entries = log.entriesFor("log-1");
        assertTrue(entries.size() >= 4);

        // 每条记录都必须含消息标识、原因与非空判定依据
        for (DecisionLog.Entry e : entries) {
            assertEquals("log-1", e.messageId());
            assertNotNull(e.reason());
            assertFalse(e.reason().isBlank());
            assertNotNull(e.basis());
            assertFalse(e.basis().isBlank());
            String rendered = e.render();
            assertTrue(rendered.contains("messageId=log-1"));
            assertTrue(rendered.contains("decision="));
            assertTrue(rendered.contains("reason="));
            assertTrue(rendered.contains("basis="));
        }

        // 关键判定均可从日志中分辨
        assertTrue(entries.stream().anyMatch(e -> e.decision().equals("EXECUTED")
                && e.reason().equals("NONE")));
        assertTrue(entries.stream().anyMatch(e -> e.decision().equals("REJECTED")
                && e.reason().equals("DUPLICATE_MESSAGE")));
    }

    @Test
    void distinctFailurePathsProduceDistinctReasonsInLog() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 5_000L);

        // 证明不覆盖
        BridgeMessage m1 = TestMessages.transfer("d-proof", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10, now);
        SignedMessage s1 = system.endpointA().originate(m1, now);
        BridgeMessage m1x = TestMessages.transfer("d-proof-x", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "zoe", 10, now);
        SignedMessage s1x = system.endpointA().originate(m1x, now);
        List<MessageProof> p1 = system.endpointA().dispatchBatch(List.of(s1, s1x), system.endpointB());
        system.endpointB().submit(TamperedProofs.withBrokenMerklePath(p1.get(0)), now + 5L);

        // 资源超限
        BridgeMessage m2 = TestMessages.withGasAndFee("d-gas", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "transfer:alice,bob,10", 1_000L, 10L, 1, 1_000L, now);
        SignedMessage s2 = system.endpointA().originate(m2, now);
        List<MessageProof> p2 = system.endpointA().dispatchBatch(List.of(s2), system.endpointB());
        system.endpointB().submit(p2.get(0), now + 6L);

        DecisionLog log = system.log();
        assertTrue(log.entriesFor("d-proof").stream()
                .anyMatch(e -> e.reason().equals("PROOF_NOT_COVERING_MESSAGE")));
        assertTrue(log.entriesFor("d-gas").stream()
                .anyMatch(e -> e.reason().equals("RESOURCE_LIMIT_EXCEEDED")
                        && e.decision().equals("RETRYABLE")));
    }

    @Test
    void reorgDecisionsAreLoggedWithDistinction() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 1_000L);

        BridgeMessage msg = TestMessages.transfer("d-reorg", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10, now);
        SignedMessage signed = system.endpointA().originate(msg, now);
        List<MessageProof> proofs = system.endpointA().dispatchBatch(List.of(signed), system.endpointB());
        system.endpointB().submit(proofs.get(0), now + 10L);

        system.chainA().reorg(0L, List.of(List.of(
                com.github.lechandonga.crosschainbridge.crypto.HashUtils.sha256("x"))));
        system.lightClientOnB().ingestHeader(system.chainA().headerAt(1L));
        system.endpointB().handleReorg(0L, now + 20L);

        DecisionLog log = system.log();
        assertTrue(log.entriesFor("d-reorg").stream()
                .anyMatch(e -> e.decision().equals("REVOKED-EXECUTED")
                        && e.reason().equals("REORG_REVOKED_UNFINALIZED")));
        // 全局日志中也记录了重组判定（messageId 允许为 null 的汇总条目）
        assertTrue(log.entries().stream().noneMatch(e -> e.basis() == null || e.basis().isBlank()));
    }
}
