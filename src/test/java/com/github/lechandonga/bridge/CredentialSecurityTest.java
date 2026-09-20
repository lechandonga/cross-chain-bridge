package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.crypto.Signing;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 凭证安全：仅掌握消息内容无法构造有效凭证；凭证与发送方身份密码学绑定。
 * 四类失败可区分：篡改 / 身份不一致 / 有效授权不足 / 同一授权重复计入。
 */
class CredentialSecurityTest {

    @Test
    void contentOnlyCannotForgeCredential() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage legitimate = f.signedMessage("s1", "transfer:bob:100");
        var delivery = f.prepareDelivery(legitimate);

        // 攻击者只知道消息内容与"sig:<sender>:<hash>"式的旧套路，无法拿到 alice 私钥
        CrossChainMessage guessed = copyWith(legitimate, "sig:alice:" + legitimate.contentHash());
        BridgeResult result = f.bridge.submit(guessed, delivery.proof(), delivery.header());
        assertEquals(MessageStatus.REJECTED, result.status());
        assertEquals(RejectionReason.CREDENTIAL_MALFORMED, result.reason());
        assertEquals(0, f.balanceOf("bob"), "伪造凭证不得记账");
        assertTrue(f.chainB.stateView().isEmpty());
    }

    @Test
    void tamperedCredentialIsMalformed() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage legitimate = f.signedMessage("s2", "transfer:bob:100");
        var delivery = f.prepareDelivery(legitimate);

        // 在合法 Base64 凭证上翻转若干字节，密码学校验必然失败
        byte[] raw = Base64.getDecoder().decode(legitimate.signature());
        raw[0] ^= 0x01;
        CrossChainMessage tampered = copyWith(legitimate,
                Base64.getEncoder().encodeToString(raw));
        BridgeResult result = f.bridge.submit(tampered, delivery.proof(), delivery.header());
        assertEquals(RejectionReason.CREDENTIAL_MALFORMED, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void garbageCredentialIsMalformed() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage legitimate = f.signedMessage("s3", "transfer:bob:100");
        var delivery = f.prepareDelivery(legitimate);

        CrossChainMessage garbage = copyWith(legitimate, "not-a-real-credential");
        BridgeResult result = f.bridge.submit(garbage, delivery.proof(), delivery.header());
        assertEquals(RejectionReason.CREDENTIAL_MALFORMED, result.reason());
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void credentialFromAnotherRegisteredSenderIsMismatch() {
        BridgeTestFixture f = new BridgeTestFixture();
        f.registerSender("mallory");
        CrossChainMessage legitimate = f.signedMessage("s4", "transfer:bob:100");
        var delivery = f.prepareDelivery(legitimate);

        // mallory 用自己的私钥为声明发送方 alice 的消息签名
        CrossChainMessage mismatched = f.resignAs(legitimate, "mallory", "alice");
        BridgeResult result = f.bridge.submit(mismatched, delivery.proof(), delivery.header());
        assertEquals(RejectionReason.CREDENTIAL_SENDER_MISMATCH, result.reason());
        assertEquals(0, f.balanceOf("bob"), "冒充发送方不得记账");
    }

    @Test
    void unknownSenderKeyIsMalformedNotMismatch() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage legitimate = f.signedMessage("s5", "transfer:bob:100");
        var delivery = f.prepareDelivery(legitimate);

        // 声明一个未注册的发送方，并为其生成密钥签名
        var stranger = Signing.generateKeyPair();
        CrossChainMessage unsigned = stripSignature(legitimate, "ghost");
        String credential = Signing.sign(stranger.getPrivate(), unsigned.contentHash());
        CrossChainMessage fromGhost = copyWith(unsigned, credential);
        BridgeResult result = f.bridge.submit(fromGhost, delivery.proof(), delivery.header());
        assertEquals(RejectionReason.CREDENTIAL_MALFORMED, result.reason(),
                "声明发送方未注册且凭证不属于任何已知身份 -> 篡改/伪造口径");
    }

    @Test
    void legitimateCredentialStillExecutes() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("s6", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);
        BridgeResult result = f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(MessageStatus.EXECUTED, result.status());
        assertEquals(100, f.balanceOf("bob"));
    }

    @Test
    void reusedCredentialDoesNotValidateDifferentContent() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage m1 = f.signedMessage("s7a", "transfer:bob:100");
        // 同发送方的另一条内容，复用 m1 的凭证 -> 内容哈希不同，校验必须失败
        CrossChainMessage m2 = f.signedMessage("s7b", "transfer:bob:999");
        CrossChainMessage replayedCredential = copyWith(m2, m1.signature());
        var d2 = f.prepareDelivery(m2);
        BridgeResult result = f.bridge.submit(replayedCredential, d2.proof(), d2.header());
        assertEquals(RejectionReason.CREDENTIAL_MALFORMED, result.reason(),
                "凭证只对被签名的内容有效，不能复用到其他消息");
        assertEquals(0, f.balanceOf("bob"));
    }

    private static CrossChainMessage copyWith(CrossChainMessage m, String signature) {
        return new CrossChainMessage(m.id(), m.sourceChain(), m.targetChain(), m.sender(),
                m.nonce(), m.payload(), m.fee(), m.gasLimit(), m.validFrom(), m.validUntil(),
                m.retryPolicy(), signature);
    }

    private static CrossChainMessage stripSignature(CrossChainMessage m, String newSender) {
        return new CrossChainMessage(m.id(), m.sourceChain(), m.targetChain(), newSender,
                m.nonce(), m.payload(), m.fee(), m.gasLimit(), m.validFrom(), m.validUntil(),
                m.retryPolicy(), null);
    }
}
