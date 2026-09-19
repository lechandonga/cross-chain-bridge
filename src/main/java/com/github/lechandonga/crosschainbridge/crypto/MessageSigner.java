package com.github.lechandonga.crosschainbridge.crypto;

import com.github.lechandonga.crosschainbridge.model.BridgeMessage;
import com.github.lechandonga.crosschainbridge.model.RetryPolicy;
import com.github.lechandonga.crosschainbridge.model.SignedMessage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;

/** 验证者签名工具：计算消息哈希、用验证者密钥集合签名/验签。 */
public final class MessageSigner {

    private MessageSigner() {
    }

    /** 将消息的全部字段以长度前缀方式序列化为确定字节序列。 */
    public static byte[] serialize(BridgeMessage m) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        putString(buffer, m.messageId());
        putString(buffer, m.sourceChain() == null ? null : m.sourceChain().name());
        putString(buffer, m.targetChain() == null ? null : m.targetChain().name());
        putString(buffer, m.payload());
        buffer.putLong(m.nonce());
        buffer.putLong(m.createdAtMillis());
        buffer.putLong(m.validUntilMillis());
        buffer.putLong(m.requiredGas());
        buffer.putLong(m.feeLimit());
        RetryPolicy rp = m.retryPolicy();
        buffer.putInt(rp == null ? -1 : rp.maxRetries());
        buffer.putLong(rp == null ? -1L : rp.retryWindowMillis());
        buffer.flip();
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    private static void putString(ByteBuffer buffer, String s) {
        byte[] bytes = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    public static byte[] messageHash(BridgeMessage message) {
        return HashUtils.sha256(serialize(message));
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC key generation failed", e);
        }
    }

    /** 验证者标识：公钥编码的 SHA-256 hex。 */
    public static String signerId(KeyPair keyPair) {
        return HashUtils.hex(HashUtils.sha256(keyPair.getPublic().getEncoded())).substring(0, 16);
    }

    /** 用给定密钥对列表签名，返回签名列表与验证者标识列表。 */
    public static SignedMessage sign(BridgeMessage message, List<KeyPair> signerKeys) {
        byte[] hash = messageHash(message);
        List<byte[]> signatures = new ArrayList<>(signerKeys.size());
        List<String> signerIds = new ArrayList<>(signerKeys.size());
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            for (KeyPair keyPair : signerKeys) {
                signer.initSign(keyPair.getPrivate());
                signer.update(hash);
                signatures.add(signer.sign());
                signerIds.add(signerId(keyPair));
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("signing failed", e);
        }
        return new SignedMessage(message, hash, List.copyOf(signatures), List.copyOf(signerIds));
    }

    /** 校验签名与声明验证者公钥是否匹配。 */
    public static boolean verifySignature(BridgeMessage message, byte[] signature, KeyPair signerKey) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(signerKey.getPublic());
            verifier.update(messageHash(message));
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** 直接对消息哈希验签（证明路径上哈希已计算时使用）。 */
    public static boolean verifySignatureHash(byte[] messageHash, byte[] signature, PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(messageHash);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
