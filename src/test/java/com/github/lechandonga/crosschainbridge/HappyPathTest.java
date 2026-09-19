package com.github.lechandonga.crosschainbridge;

import com.github.lechandonga.crosschainbridge.bridge.*;
import com.github.lechandonga.crosschainbridge.model.*;
import com.github.lechandonga.crosschainbridge.support.TestMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HappyPathTest {

    @Test
    void messageFlowsFromAToBAndIsExecutedExactlyOnce() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;

        system.stateA().credit("alice", 1_000L);
        system.stateB().credit("alice", 1_000L);
        system.stateB().credit("bob", 50L);

        BridgeMessage msg = TestMessages.transfer("m-1", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 200L, now);
        SignedMessage signed = system.endpointA().originate(msg, now);
        List<MessageProof> proofs = system.endpointA().dispatchBatch(List.of(signed), system.endpointB());

        SubmitResult result = system.endpointB().submit(proofs.get(0), now + 10L);

        assertTrue(result.accepted());
        assertEquals(MessageStatus.EXECUTED, result.status());
        assertEquals(800L, system.stateB().balanceOf("alice"));
        assertEquals(250L, system.stateB().balanceOf("bob"));
        assertEquals(MessageStatus.EXECUTED, system.endpointB().stateOf("m-1").status());

        // 重复投递：幂等拒绝，不再次执行
        SubmitResult duplicate = system.endpointB().submit(proofs.get(0), now + 20L);
        assertFalse(duplicate.accepted());
        assertEquals(FailureReason.DUPLICATE_MESSAGE, duplicate.failureReason());
        assertEquals(800L, system.stateB().balanceOf("alice"));
        assertEquals(250L, system.stateB().balanceOf("bob"));
    }

    @Test
    void messageAlsoFlowsFromBToA() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateA().credit("carol", 100L);

        BridgeMessage msg = TestMessages.transfer("m-rev", ChainId.CHAIN_B, ChainId.CHAIN_A,
                "carol", "dave", 40L, now);
        SignedMessage signed = system.endpointB().originate(msg, now);
        List<MessageProof> proofs = system.endpointB().dispatchBatch(List.of(signed), system.endpointA());

        SubmitResult result = system.endpointA().submit(proofs.get(0), now + 5L);

        assertTrue(result.accepted());
        assertEquals(60L, system.stateA().balanceOf("carol"));
        assertEquals(40L, system.stateA().balanceOf("dave"));
    }
}
