package com.github.lechandonga.bridge.chain;

import com.github.lechandonga.bridge.lightclient.MerkleProof;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 本地模拟链：提交区块、查询状态、重组与最终性。
 * 每个区块保存状态快照；重组回滚未最终确定的区块并恢复对应快照。
 */
public class SimulatedChain {

    private final String chainId;
    private final List<Block> blocks = new ArrayList<>();
    private final Map<String, String> state = new HashMap<>();
    private final Map<String, String> pending = new HashMap<>();
    private long finalizedHeight = 0;
    private long timestamp;

    public SimulatedChain(String chainId) {
        this.chainId = chainId;
        // 创世块
        BlockHeader genesis = new BlockHeader(chainId, 0, "0", MerkleProof.stateRoot(Map.of()), 0, List.of());
        blocks.add(new Block(genesis, Map.of()));
    }

    public String chainId() {
        return chainId;
    }

    public synchronized long headHeight() {
        return blocks.get(blocks.size() - 1).header().height();
    }

    public synchronized long finalizedHeight() {
        return finalizedHeight;
    }

    public synchronized Optional<String> get(String key) {
        return Optional.ofNullable(state.get(key));
    }

    /** 写入 pending 状态（下一次 commitBlock 生效）。 */
    public synchronized void put(String key, String value) {
        pending.put(key, value);
    }

    /** 直接写入当前状态（桥接执行用，随下一次 commitBlock 固化）。 */
    public synchronized void applyNow(String key, String value) {
        state.put(key, value);
        pending.put(key, value);
    }

    /** 提交新区块，返回区块头。 */
    public synchronized BlockHeader commitBlock() {
        state.putAll(pending);
        pending.clear();
        timestamp++;
        BlockHeader parent = blocks.get(blocks.size() - 1).header();
        BlockHeader header = new BlockHeader(chainId, parent.height() + 1, parent.hash(),
                MerkleProof.stateRoot(state), timestamp, List.of());
        blocks.add(new Block(header, Collections.unmodifiableMap(new TreeMap<>(state))));
        return header;
    }

    /** 使用给定验证者签名提交新区块（来源链出块，供轻客户端验证）。 */
    public synchronized BlockHeader commitBlock(List<String> validatorSignatures) {
        state.putAll(pending);
        pending.clear();
        timestamp++;
        BlockHeader parent = blocks.get(blocks.size() - 1).header();
        BlockHeader header = new BlockHeader(chainId, parent.height() + 1, parent.hash(),
                MerkleProof.stateRoot(state), timestamp, List.copyOf(validatorSignatures));
        blocks.add(new Block(header, Collections.unmodifiableMap(new TreeMap<>(state))));
        return header;
    }

    /** 将指定高度及以前的区块标记为最终确定。 */
    public synchronized void finalizeUpTo(long height) {
        if (height > finalizedHeight && height <= headHeight()) {
            finalizedHeight = height;
        }
    }

    /**
     * 重组：回滚最近 depth 个未最终确定的区块。
     * @return 实际回滚的区块数；若触及已最终确定区块则返回 -1 且不改变状态。
     */
    public synchronized long reorg(long depth) {
        long head = headHeight();
        if (depth <= 0 || depth > head) {
            return 0;
        }
        if (head - depth < finalizedHeight) {
            return -1; // 触及已最终确定的区块，拒绝重组
        }
        for (long i = 0; i < depth; i++) {
            blocks.remove(blocks.size() - 1);
        }
        Block newHead = blocks.get(blocks.size() - 1);
        state.clear();
        state.putAll(newHead.stateSnapshot());
        pending.clear();
        return depth;
    }

    public synchronized Optional<BlockHeader> headerAt(long height) {
        for (Block b : blocks) {
            if (b.header().height() == height) {
                return Optional.of(b.header());
            }
        }
        return Optional.empty();
    }

    /** 指定高度区块的状态快照（用于构建 Merkle 证明）。 */
    public synchronized Optional<Map<String, String>> stateAt(long height) {
        for (Block b : blocks) {
            if (b.header().height() == height) {
                return Optional.of(b.stateSnapshot());
            }
        }
        return Optional.empty();
    }

    public synchronized Map<String, String> stateView() {
        return Collections.unmodifiableMap(new TreeMap<>(state));
    }
}
