package com.github.lechandonga.crosschainbridge;

import com.github.lechandonga.crosschainbridge.bridge.*;
import com.github.lechandonga.crosschainbridge.model.*;
import com.github.lechandonga.crosschainbridge.support.TestMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollisionTest {

    @Test
    void sameIdWithDifferentNonceIsRejectedAsCollision() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 5_000L);

        // 第一条消息占住 id=same-id（nonce 由工厂唯一分配）
        BridgeMessage first = TestMessages.transfer("same-id", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10L, now);
        SignedMessage s1 = system.endpointA().originate(first, now);
        List<MessageProof> p1 = system.endpointA().dispatchBatch(List.of(s1), system.endpointB());
        SubmitResult r1 = system.endpointB().submit(p1.get(0), now + 10L);
        assertTrue(r1.accepted());

        // 第二条消息复用同一 id 但显式使用不同 nonce、不同载荷
        BridgeMessage colliding = new BridgeMessage("same-id", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "transfer:alice,attacker,999", 77777L,
                now, now + 100_000L, 10L, 100L,
                new com.github.lechandonga.crosschainbridge.model.RetryPolicy(1, 1_000L));
        SignedMessage s2 = system.endpointA().originate(colliding, now);
        List<MessageProof> p2 = system.endpointA().dispatchBatch(List.of(s2), system.endpointB());

        SubmitResult r2 = system.endpointB().submit(p2.get(0), now + 20L);
        assertFalse(r2.accepted());
        assertEquals(FailureReason.MESSAGE_ID_COLLISION, r2.failureReason());

        // 已执行的第一条结果不受影响，攻击者没得账
        assertEquals(MessageStatus.EXECUTED, system.endpointB().stateOf("same-id").status());
        assertEquals(0L, system.stateB().balanceOf("attacker"));
        assertEquals(10L, system.stateB().balanceOf("bob"));
    }

    @Test
    void sameIdFromDifferentSourceChainIsCollision() {
        TwoChainSystem system = new TwoChainSystem(3);
        long now = TestMessages.T0;
        system.stateB().credit("alice", 5_000L);

        BridgeMessage first = TestMessages.transfer("x-id", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 10L, now);
        SignedMessage s1 = system.endpointA().originate(first, now);
        List<MessageProof> p1 = system.endpointA().dispatchBatch(List.of(s1), system.endpointB());
        assertTrue(system.endpointB().submit(p1.get(0), now + 10L).accepted());

        // 伪造一条 source=CHAIN_B 的消息却仍投给 B（target 不匹配会先被拦），
        // 因此碰撞测试直接验证信箱的来源指纹判定。
        TargetMailbox mailbox = system.mailboxB();
        TargetMailbox.ReserveOutcome outcome = mailbox.reserve("x-id",
                new TargetMailbox.Origin(ChainId.CHAIN_B.name(), 42L));
        assertEquals(TargetMailbox.ReserveOutcome.COLLISION, outcome);
    }
}
