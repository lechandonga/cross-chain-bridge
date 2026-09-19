package com.github.lechandonga.crosschainbridge.model;

/** 轻客户端证明包：消息签名 + Merkle 包含证明 + 来源区块头。 */
public record MessageProof(
        SignedMessage signedMessage,
        MerkleProof merkleProof,
        BlockHeader sourceHeader) {
}
