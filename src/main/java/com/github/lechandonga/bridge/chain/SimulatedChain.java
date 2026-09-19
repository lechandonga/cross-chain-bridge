package com.github.lechandonga.bridge.chain;

import java.util.Map;
import java.util.Optional;

/** 本地模拟链：提交区块、查询状态、重组与最终性。 */
public class SimulatedChain {

    public SimulatedChain(String chainId) {
    }

    public String chainId() { return null; }

    /** 当前链头高度。 */
    public long headHeight() { return -1; }

    /** 当前已最终确定的高度。 */
    public long finalizedHeight() { return -1; }

    /** 读取当前状态。 */
    public Optional<String> get(String key) { return Optional.empty(); }

    /** 写入 pending 状态（下一次 commitBlock 生效）。 */
    public void put(String key, String value) {
    }

    /** 提交新区块，返回区块头。 */
    public BlockHeader commitBlock() { return null; }

    /** 将指定高度及以前的区块标记为最终确定。 */
    public void finalizeUpTo(long height) {
    }

    /**
     * 重组：回滚最近 depth 个未最终确定的区块。
     * @return 实际回滚的区块数；若触及已最终确定区块则返回 -1 且不改变状态。
     */
    public long reorg(long depth) { return -1; }

    /** 查询指定高度的区块头。 */
    public Optional<BlockHeader> headerAt(long height) { return Optional.empty(); }

    /** 当前状态（只读视图）。 */
    public Map<String, String> stateView() { return Map.of(); }
}
