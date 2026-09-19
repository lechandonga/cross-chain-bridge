package com.github.lechandonga.bridge.lightclient;

import java.util.List;
import java.util.Map;

/** 针对状态根的 Merkle 包含证明。 */
public record MerkleProof(String key, String value, List<String> siblingHashes, int leafIndex) {

    /** 由完整状态构建指定 key 的证明（骨架：暂返回 null）。 */
    public static MerkleProof build(Map<String, String> state, String key) { return null; }

    /** 根据证明计算根哈希（骨架：暂返回 null）。 */
    public String computeRoot() { return null; }
}
