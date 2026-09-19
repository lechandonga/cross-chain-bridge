package com.github.lechandonga.crosschainbridge.chain;

import com.github.lechandonga.crosschainbridge.crypto.HashUtils;
import com.github.lechandonga.crosschainbridge.crypto.MerkleTreeBuilder;
import com.github.lechandonga.crosschainbridge.model.BlockHeader;
import com.github.lechandonga.crosschainbridge.model.ChainId;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地模拟链：维护区块历史与状态根。
 * 支持出块、最终性推进、重组（回滚到分叉点后接新链）。
 *
 * <p>每个区块体即一批消息哈希；状态根 = H(height || 消息哈希序列)，
 * 与 Merkle 根相互独立，模拟“区块头提交状态根、消息通过独立证明包含在块内”。
 */
public final class SimulatedBlockchain {

    private final ChainId chainId;
    /** headers.get(h-1) 即高度 h 的区块头（高度从 1 开始）。 */
    private final List<BlockHeader> headers = new ArrayList<>();
    private final List<List<byte[]>> blockBodies = new ArrayList<>();
    private volatile long finalizedHeight = 0L;

    public SimulatedBlockchain(ChainId chainId) {
        this.chainId = chainId;
    }

    public ChainId chainId() {
        return chainId;
    }

    public synchronized long headHeight() {
        return (long) headers.size();
    }

    public synchronized long finalizedHeight() {
        return finalizedHeight;
    }

    public synchronized BlockHeader head() {
        return headers.isEmpty() ? null : headers.get(headers.size() - 1);
    }

    public synchronized BlockHeader headerAt(long height) {
        if (height < 1 || height > headers.size()) {
            return null;
        }
        return headers.get((int) height - 1);
    }

    public synchronized List<byte[]> bodyAt(long height) {
        if (height < 1 || height > blockBodies.size()) {
            return List.of();
        }
        return List.copyOf(blockBodies.get((int) height - 1));
    }

    /** 将一批消息哈希打包成新块，返回新区块头。 */
    public synchronized BlockHeader produceBlock(List<byte[]> messageHashes) {
        long height = (long) headers.size() + 1;
        byte[] prevHash = headers.isEmpty()
                ? HashUtils.EMPTY_ROOT.clone()
                : headers.get(headers.size() - 1).hash();
        byte[] stateRoot = computeStateRoot(height, messageHashes);
        byte[] transactionsRoot = new MerkleTreeBuilder(messageHashes).root();
        BlockHeader header = new BlockHeader(height, prevHash, stateRoot, transactionsRoot);
        header.setHash(computeHeaderHash(height, prevHash, stateRoot, transactionsRoot));
        header.setFinalized(false);
        headers.add(header);
        blockBodies.add(new ArrayList<>(messageHashes));
        return header;
    }

    private static byte[] computeStateRoot(long height, List<byte[]> messageHashes) {
        byte[] seed = HashUtils.sha256("state-root:" + height);
        byte[] acc = seed;
        for (byte[] messageHash : messageHashes) {
            acc = HashUtils.concatHash(acc, messageHash);
        }
        return acc;
    }

    private static byte[] computeHeaderHash(long height, byte[] prevHash, byte[] stateRoot, byte[] transactionsRoot) {
        byte[] heightBytes = new byte[] {
                (byte) (height >>> 56), (byte) (height >>> 48), (byte) (height >>> 40),
                (byte) (height >>> 32), (byte) (height >>> 24), (byte) (height >>> 16),
                (byte) (height >>> 8), (byte) height };
        byte[] roots = HashUtils.concatHash(stateRoot, transactionsRoot);
        return HashUtils.sha256(HashUtils.concatHash(HashUtils.concatHash(heightBytes, prevHash), roots));
    }

    /** 将指定高度（含）以下标记为最终。 */
    public synchronized void finalizeUpTo(long height) {
        if (height > headers.size()) {
            throw new IllegalArgumentException("cannot finalize beyond head: " + height + " > " + headers.size());
        }
        finalizedHeight = height;
        for (int i = 0; i < height; i++) {
            headers.get(i).setFinalized(true);
        }
    }

    /**
     * 在分叉点之后用新消息序列重组链。
     * 不允许重组已最终确定的区块：抛出 {@link FinalizedRollbackException}。
     *
     * @param forkHeight 分叉点高度，该高度及之前保留，之后的块全部替换
     * @return 重组产生的新区块头列表
     */
    public synchronized List<BlockHeader> reorg(long forkHeight, List<List<byte[]>> newBlocks) {
        if (forkHeight < finalizedHeight) {
            throw new FinalizedRollbackException(finalizedHeight, forkHeight);
        }
        if (forkHeight > headers.size()) {
            throw new IllegalArgumentException("fork beyond head");
        }
        // 截断到分叉点
        while (headers.size() > forkHeight) {
            headers.remove(headers.size() - 1);
            blockBodies.remove(blockBodies.size() - 1);
        }
        // 被截断区间若包含已最终区块理论上已被上面的检查拦住；
        // 最终性标记随保留高度收缩
        finalizedHeight = Math.min(finalizedHeight, forkHeight);
        List<BlockHeader> added = new ArrayList<>();
        for (List<byte[]> newBody : newBlocks) {
            added.add(produceBlock(newBody));
        }
        return List.copyOf(added);
    }

    /** 消息哈希是否仍在当前规范链的某个区块中。 */
    public synchronized boolean containsOnCanonicalChain(byte[] messageHash) {
        for (List<byte[]> body : blockBodies) {
            for (byte[] h : body) {
                if (java.security.MessageDigest.isEqual(h, messageHash)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 某高度区块在当前规范链上的状态根；不存在返回 null。 */
    public synchronized byte[] stateRootAt(long height) {
        BlockHeader header = headerAt(height);
        return header == null ? null : header.stateRoot();
    }
}
