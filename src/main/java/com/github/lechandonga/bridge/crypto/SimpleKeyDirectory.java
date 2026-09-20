package com.github.lechandonga.bridge.crypto;

import java.security.PublicKey;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的内存公钥注册表。 */
public final class SimpleKeyDirectory implements KeyDirectory {

    private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();

    @Override
    public void register(String identity, PublicKey key) {
        if (identity == null || identity.isBlank() || key == null) {
            throw new IllegalArgumentException("身份与公钥均不能为空");
        }
        PublicKey previous = keys.putIfAbsent(identity, key);
        if (previous != null && !previous.equals(key)) {
            throw new IllegalStateException("身份公钥已注册且不可替换: " + identity);
        }
    }

    @Override
    public Optional<PublicKey> keyOf(String identity) {
        return Optional.ofNullable(keys.get(identity));
    }

    @Override
    public boolean knows(String identity) {
        return keys.containsKey(identity);
    }

    @Override
    public Set<String> identities() {
        return Set.copyOf(keys.keySet());
    }
}
