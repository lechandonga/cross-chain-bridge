package com.github.lechandonga.bridge.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 发送方/验证者数字签名工具（Ed25519）。
 * 凭证对消息内容哈希签名：仅掌握消息内容而无发送方私钥无法构造有效凭证。
 * 线上编码统一为 Base64（无换行）。
 */
public final class Signing {

    /** 本模拟使用的非对称签名算法。 */
    public static final String ALGORITHM = "Ed25519";

    private Signing() {}

    /** 生成新的签名密钥对。 */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 Ed25519 密钥对", e);
        }
    }

    /** 对消息内容哈希（十六进制字符串）签名，返回 Base64 凭证。 */
    public static String sign(PrivateKey privateKey, String contentHash) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(contentHash.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    /** 用公钥校验凭证是否为内容哈希的有效签名（任何失败都返回 false，不抛异常）。 */
    public static boolean verify(PublicKey publicKey, String contentHash, String credential) {
        if (publicKey == null || contentHash == null || credential == null || credential.isBlank()) {
            return false;
        }
        final byte[] raw;
        try {
            raw = Base64.getDecoder().decode(credential);
        } catch (IllegalArgumentException badEncoding) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(contentHash.getBytes(StandardCharsets.UTF_8));
            return signature.verify(raw);
        } catch (Exception e) {
            return false;
        }
    }

    /** 公钥 X.509 编码 -> Base64。 */
    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** Base64 -> 公钥（编码非法时返回 null）。 */
    public static PublicKey decodePublicKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(raw));
        } catch (Exception e) {
            return null;
        }
    }
}
