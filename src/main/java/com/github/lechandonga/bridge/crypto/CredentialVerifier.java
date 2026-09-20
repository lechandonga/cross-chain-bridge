package com.github.lechandonga.bridge.crypto;

import com.github.lechandonga.bridge.model.RejectionReason;

import java.security.PublicKey;

/**
 * 消息凭证校验：凭证必须与发送方身份密码学绑定。
 *
 * <p>失败口径：
 * <ul>
 *   <li>{@link RejectionReason#CREDENTIAL_SENDER_MISMATCH}：凭证有效，但由另一个已注册身份的私钥作出；</li>
 *   <li>{@link RejectionReason#CREDENTIAL_MALFORMED}：凭证被篡改、无法解析，或不属于任何已注册身份。</li>
 * </ul>
 */
public final class CredentialVerifier {

    private final KeyDirectory directory;

    public CredentialVerifier(KeyDirectory directory) {
        this.directory = directory;
    }

    /**
     * 校验凭证。
     *
     * @return null 表示通过；否则返回可区分的失败原因
     */
    public RejectionReason verify(String sender, String contentHash, String credential) {
        if (credential == null || credential.isBlank()) {
            return RejectionReason.CREDENTIAL_MALFORMED;
        }

        // 1. 与声明发送方绑定的公钥校验
        PublicKey senderKey = directory.keyOf(sender).orElse(null);
        if (senderKey != null && Signing.verify(senderKey, contentHash, credential)) {
            return null;
        }

        // 2. 凭证是否由另一个已注册身份作出 -> 身份不一致
        for (String identity : directory.identities()) {
            if (identity.equals(sender)) {
                continue;
            }
            PublicKey other = directory.keyOf(identity).orElse(null);
            if (other != null && Signing.verify(other, contentHash, credential)) {
                return RejectionReason.CREDENTIAL_SENDER_MISMATCH;
            }
        }

        // 3. 伪造/篡改：不属于任何已注册身份
        return RejectionReason.CREDENTIAL_MALFORMED;
    }
}
