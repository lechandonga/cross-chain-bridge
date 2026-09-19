package com.github.lechandonga.crosschainbridge.model;

import java.util.List;

/**
 * 消息包含证明：消息所在区块高度、叶子索引、Merkle 路径及路径方向。
 * direction[i] == true 表示路径上第 i 个哈希位于右侧（计算时 leaf 在左）。
 */
public record MerkleProof(
        byte[] leafHash,
        List<byte[]> path,
        List<Boolean> pathDirection,
        long blockHeight,
        int leafIndex) {
}
