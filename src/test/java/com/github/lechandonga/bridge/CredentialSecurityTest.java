package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.crypto.Signatures;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息凭证安全：
 * 凭证基于 Ed25519 非对称签名并与发送方身份绑定，仅凭消息内容无法伪造；
 * 凭证被篡改、凭证与发送方不一致、发送方未登记三种失败可区分。
 */
class CredentialSecurityTest {

    /** 仅凭消息内容（不持有发送方私钥）无法构造能通过校验的凭证。 */
    @Test
    void contentAloneIsNotEnoughToForge() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("s1", "alice", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);

        // 攻击者只知道内容哈希，按旧的可猜测格式伪造，或瞎编一个 v1 凭证
        CrossChainMessage guess = withSignature(msg, "sig:alice:" + msg.contentHash());
        BridgeResult r1 = f.bridge.submit(guess, delivery.proof(), delivery.header());
        assertEquals(RejectionReason.INVALID_SIGNATURE, r1.reason());

        CrossChainMessage random = withSignature(msg, "cred:v1:alice:AAAAAAAAAAAAAAAA");
        BridgeResult r2 = f.bridge.submit(random, delivery.proof(), delivery.header());
        assertEquals(RejectionReason.INVALID_SIGNATURE, r2.reason());

        assertEquals(0, f.balanceOf("bob"), "伪造凭证不得执行");
    }

    /** 凭证内容被篡改（签名体损坏、无法解析或验签失败）→ INVALID_SIGNATURE。 */
    @Test
    void tamperedCredentialRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("s2", "alice", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);

        String valid = msg.signature();
        String tampered = valid.substring(0, valid.length() - 2)
                + (valid.endsWith("AA") ? "BB" : "AA");
        CrossChainMessage m = withSignature(msg, tampered);
        BridgeResult result = f.bridge.submit(m, delivery.proof(), delivery.header());
        assertEquals(RejectionReason.INVALID_SIGNATURE, result.reason());
        assertEquals(0, f.balanceOf("bob"));
        assertTrue(f.chainB.stateView().isEmpty());
    }

    /** Mallory 用自己的私钥为内容签名，却冒充 alice → CREDENTIAL_SENDER_MISMATCH。 */
    @Test
    void credentialFromOtherSenderIsMismatch() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("s3", "alice", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);

        KeyPair mallory = f.senderKeys.get("mallory");
        // 凭证内声明的仍是 mallory（真实有效），但消息发送方是 alice
        String malloryCred = Signatures.issue(mallory.getPrivate(), "mallory", msg.contentHash());
        CrossChainMessage forged = withSignature(msg, malloryCred);
        BridgeResult result = f.bridge.submit(forged, delivery.proof(), delivery.header());
        assertEquals(RejectionReason.CREDENTIAL_SENDER_MISMATCH, result.reason());
        assertEquals(0, f.balanceOf("bob"), "他人凭证不得冒充 alice 执行");

        // 即使把凭证前缀里的主体名改成 alice（内容不变），公钥验签也会失败 → INVALID_SIGNATURE
        String relabeled = malloryCred.replace("cred:v1:mallory:", "cred:v1:alice:");
        CrossChainMessage relabeledMsg = withSignature(msg, relabeled);
        BridgeResult r2 = f.bridge.submit(relabeledMsg, delivery.proof(), delivery.header());
        assertEquals(RejectionReason.INVALID_SIGNATURE, r2.reason());
    }

    /** 未登记发送方（无绑定公钥）→ UNREGISTERED_SENDER，优先于凭证本身判断。 */
    @Test
    void unregisteredSenderRejected() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("s4", "alice", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);

        KeyPair ghost = Signatures.generateKeyPair(); // 从未登记
        CrossChainMessage fromGhost = new CrossChainMessage(msg.id(), msg.sourceChain(),
                msg.targetChain(), "ghost", msg.nonce(), msg.payload(), msg.fee(),
                msg.gasLimit(), msg.validFrom(), msg.validUntil(), msg.retryPolicy(),
                Signatures.issue(ghost.getPrivate(), "ghost", msg.contentHash()));
        // 注意：内容随 sender 改变，证明值不再匹配；但发送方未登记应先被识别
        BridgeResult result = f.bridge.submit(fromGhost, delivery.proof(), delivery.header());
        assertEquals(RejectionReason.UNREGISTERED_SENDER, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }

    /** 合法凭证正常执行（回归保证）。 */
    @Test
    void validCredentialExecutes() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("s5", "alice", "transfer:bob:120");
        var delivery = f.prepareDelivery(msg);
        BridgeResult result = f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(MessageStatus.EXECUTED, result.status());
        assertEquals(120, f.balanceOf("bob"));
    }

    private static CrossChainMessage withSignature(CrossChainMessage m, String sig) {
        return new CrossChainMessage(m.id(), m.sourceChain(), m.targetChain(), m.sender(),
                m.nonce(), m.payload(), m.fee(), m.gasLimit(), m.validFrom(), m.validUntil(),
                m.retryPolicy(), sig);
    }
}
