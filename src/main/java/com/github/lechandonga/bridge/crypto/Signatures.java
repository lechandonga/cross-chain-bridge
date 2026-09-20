package com.github.lechandonga.bridge.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 不可伪造凭证的密码学工具：基于 Ed25519 的非对称签名。
 *
 * <p>凭证格式：{@code cred:v1:<subject>:<base64url(签名)>}，
 * 签名内容为消息内容（内容哈希）的 UTF-8 字节。</p>
 *
 * <p>仅凭消息内容无法构造有效凭证——必须持有声明主体的私钥；
 * 公钥与主体身份（发送方/验证者）绑定登记，凭证因此与身份强绑定。</p>
 */
public final class Signatures {

    /** 凭证前缀，用于与旧的模拟串及任意伪造数据区分。 */
    public static final String PREFIX = "cred:v1:";

    /** 解析后的凭证：声明主体 + 原始签名字节。 */
    public record ParsedCredential(String subject, byte[] rawSignature) {}

    private Signatures() {}

    /** 生成一把 Ed25519 密钥（一个主体对应一把）。 */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("当前 JRE 不支持 Ed25519", e);
        }
    }

    /** 对内容签发凭证串：cred:v1:&lt;subject&gt;:&lt;base64url(sign(content))&gt;。 */
    public static String issue(PrivateKey key, String subject, String content) {
        if (subject == null || subject.isBlank() || subject.indexOf(':') >= 0) {
            throw new IllegalArgumentException("主体标识不能为空且不能包含 ':'");
        }
        byte[] sig = sign(key, content.getBytes(StandardCharsets.UTF_8));
        return PREFIX + subject + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    }

    /** 对任意字节做 Ed25519 签名。 */
    public static byte[] sign(PrivateKey key, byte[] content) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(content);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    /**
     * 用公钥验证凭证串是否覆盖给定内容。
     * @return true 仅当凭证格式合法且签名通过该公钥校验
     */
    public static boolean verify(PublicKey key, String content, String credential) {
        ParsedCredential parsed = parse(credential);
        if (parsed == null) {
            return false;
        }
        return verifyRaw(key, content.getBytes(StandardCharsets.UTF_8), parsed.rawSignature());
    }

    /** 凭证是否为结构合法的可解析凭证（前缀/主体/Base64 均正确）。 */
    public static boolean isWellFormed(String credential) {
        return parse(credential) != null;
    }

    /** 解析凭证；格式非法、主体缺失或 Base64 损坏时返回 null。 */
    public static ParsedCredential parse(String credential) {
        if (credential == null || !credential.startsWith(PREFIX)) {
            return null;
        }
        String body = credential.substring(PREFIX.length());
        int sep = body.indexOf(':');
        if (sep <= 0 || sep == body.length() - 1) {
            return null;
        }
        String subject = body.substring(0, sep);
        String encoded = body.substring(sep + 1);
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            if (raw.length == 0) {
                return null;
            }
            return new ParsedCredential(subject, raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 用公钥校验原始签名。 */
    public static boolean verifyRaw(PublicKey key, byte[] content, byte[] rawSignature) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(content);
            return sig.verify(rawSignature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    // ---- 密钥序列化（身份登记本以编码字符串保存公钥） ----

    public static String encodePublicKey(PublicKey key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getEncoded());
    }

    public static String encodePrivateKey(PrivateKey key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublicKey(String encoded) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(raw));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("公钥编码无效", e);
        }
    }

    public static PrivateKey decodePrivateKey(String encoded) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(raw));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("私钥编码无效", e);
        }
    }
}
