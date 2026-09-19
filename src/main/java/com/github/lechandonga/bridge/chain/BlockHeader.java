package com.github.lechandonga.bridge.chain;

import java.util.List;

/** 区块头：高度、父哈希、状态根、时间戳与验证者签名。 */
public record BlockHeader(
        String chainId,
        long height,
        String parentHash,
        String stateRoot,
        long timestamp,
        List<String> validatorSignatures) {

    public String hash() {
        return com.github.lechandonga.bridge.util.Hashing.sha256(
                chainId + "|" + height + "|" + parentHash + "|" + stateRoot + "|" + timestamp);
    }
}
