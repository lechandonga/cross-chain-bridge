package com.github.lechandonga.bridge.crypto;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 身份登记本：主体标识（发送方/验证者）与其公钥绑定。
 *
 * <p>凭证必须由登记在该主体名下的公钥对应的私钥签发，且凭证内声明的主体一致，
 * 才能通过校验。因此仅凭消息内容无法伪造出绑定他人身份的有效凭证。</p>
 */
public class IdentityRegistry {

    private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();

    /** 登记主体公钥；重复登记同一主体以最新登记为准。 */
    public void register(String subject, PublicKey key) {
        keys.put(subject, key);
    }

    public boolean isRegistered(String subject) {
        return keys.containsKey(subject);
    }

    public Optional<PublicKey> keyOf(String subject) {
        return Optional.ofNullable(keys.get(subject));
    }

    /**
     * 校验凭证是否由该主体签发：凭证声明主体一致且其登记公钥验证通过。
     */
    public boolean verifyFor(String subject, String content, String credential) {
        Signatures.ParsedCredential parsed = Signatures.parse(credential);
        if (parsed == null || !parsed.subject().equals(subject)) {
            return false;
        }
        PublicKey key = keys.get(subject);
        return key != null
                && Signatures.verifyRaw(key, content.getBytes(StandardCharsets.UTF_8), parsed.rawSignature());
    }

    /**
     * 查找凭证实际归属的已登记主体（声明主体与签发公钥同时匹配）。
     * 用于区分「凭证被篡改」与「凭证有效但与声明发送方不一致」。
     */
    public Optional<String> findSigner(String content, String credential) {
        Signatures.ParsedCredential parsed = Signatures.parse(credential);
        if (parsed == null) {
            return Optional.empty();
        }
        PublicKey key = keys.get(parsed.subject());
        if (key != null
                && Signatures.verifyRaw(key, content.getBytes(StandardCharsets.UTF_8), parsed.rawSignature())) {
            return Optional.of(parsed.subject());
        }
        return Optional.empty();
    }
}
