package com.github.lechandonga.crosschainbridge.model;

import com.github.lechandonga.crosschainbridge.crypto.HashUtils;

/** 模拟区块头：高度、前块哈希、状态根、交易 Merkle 根、本块哈希。 */
public final class BlockHeader {
    private final long height;
    private final byte[] prevHash;
    private final byte[] stateRoot;
    private final byte[] transactionsRoot;
    private byte[] hash;
    private boolean finalized;

    public BlockHeader(long height, byte[] prevHash, byte[] stateRoot, byte[] transactionsRoot) {
        this.height = height;
        this.prevHash = prevHash;
        this.stateRoot = stateRoot;
        this.transactionsRoot = transactionsRoot;
    }

    public long height() { return height; }
    public byte[] prevHash() { return prevHash; }
    public byte[] stateRoot() { return stateRoot; }
    public byte[] transactionsRoot() { return transactionsRoot; }
    public byte[] hash() { return hash; }
    public void setHash(byte[] hash) { this.hash = hash; }
    public boolean finalized() { return finalized; }
    public void setFinalized(boolean finalized) { this.finalized = finalized; }

    /** 不携带本块哈希与最终性标记的字段比较（用于检测提交头与规范头不一致）。 */
    public boolean commitsSameValues(BlockHeader other) {
        return other != null
                && height == other.height
                && java.security.MessageDigest.isEqual(prevHash, other.prevHash)
                && java.security.MessageDigest.isEqual(stateRoot, other.stateRoot)
                && java.security.MessageDigest.isEqual(transactionsRoot, other.transactionsRoot);
    }

    /** 空链创世占位根。 */
    public static byte[] emptyRoot() {
        return HashUtils.EMPTY_ROOT.clone();
    }
}
