package com.github.lechandonga.bridge.model;

/** 跨链消息：发起方声明目标链、有效期与重试策略。 */
public record CrossChainMessage(
        String id,
        String sourceChain,
        String targetChain,
        String sender,
        long nonce,
        String payload,
        long fee,
        long gasLimit,
        long validFrom,
        long validUntil,
        RetryPolicy retryPolicy,
        String signature) {

    /** 消息内容哈希（不含签名），用于标识碰撞检测。 */
    public String contentHash() {
        return com.github.lechandonga.bridge.util.Hashing.sha256(
                String.join("|", id, sourceChain, targetChain, sender,
                        String.valueOf(nonce), payload, String.valueOf(fee),
                        String.valueOf(gasLimit), String.valueOf(validFrom),
                        String.valueOf(validUntil)));
    }
}
