package com.github.lechandonga.bridge.crypto;

import java.security.PublicKey;
import java.util.Optional;
import java.util.Set;

/**
 * 身份 -> 公钥 注册表（发送方或来源链验证者）。
 * 凭证身份绑定判定以此为准：只有用该身份对应私钥作出的签名才有效。
 */
public interface KeyDirectory {

    /** 注册身份公钥；重复注册将被拒绝（防止身份被偷换）。 */
    void register(String identity, PublicKey key);

    /** 查询身份公钥。 */
    Optional<PublicKey> keyOf(String identity);

    /** 是否已注册该身份。 */
    boolean knows(String identity);

    /** 全部已注册身份（区块头多签归属判定使用）。 */
    Set<String> identities();
}
