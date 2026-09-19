package com.github.lechandonga.bridge.lightclient;

import com.github.lechandonga.bridge.util.Hashing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 针对状态根的 Merkle 包含证明。
 * 状态按键排序，叶子为 sha256(key=value)，逐层两两哈希，不足时复制最后一个节点。
 */
public record MerkleProof(String key, String value, List<String> siblingHashes, int leafIndex) {

    public static String leafHash(String key, String value) {
        return Hashing.sha256(key + "=" + value);
    }

    /** 计算整棵状态树的根。 */
    public static String stateRoot(Map<String, String> state) {
        List<String> level = leaves(state);
        if (level.isEmpty()) {
            return Hashing.sha256("<empty>");
        }
        while (level.size() > 1) {
            level = parentLevel(level);
        }
        return level.get(0);
    }

    /** 由完整状态构建指定 key 的证明；key 不存在时返回 null。 */
    public static MerkleProof build(Map<String, String> state, String key) {
        if (!state.containsKey(key)) {
            return null;
        }
        TreeMap<String, String> sorted = new TreeMap<>(state);
        List<String> keys = new ArrayList<>(sorted.keySet());
        int index = keys.indexOf(key);

        List<String> level = leaves(state);
        List<String> siblings = new ArrayList<>();
        int idx = index;
        while (level.size() > 1) {
            List<String> padded = pad(level);
            int siblingIdx = (idx % 2 == 0) ? idx + 1 : idx - 1;
            siblings.add(padded.get(siblingIdx));
            level = parentLevel(level);
            idx /= 2;
        }
        return new MerkleProof(key, state.get(key), siblings, index);
    }

    /** 根据证明重算根哈希。 */
    public String computeRoot() {
        String current = leafHash(key, value);
        int idx = leafIndex;
        for (String sibling : siblingHashes) {
            current = (idx % 2 == 0)
                    ? Hashing.sha256(current + sibling)
                    : Hashing.sha256(sibling + current);
            idx /= 2;
        }
        return current;
    }

    private static List<String> leaves(Map<String, String> state) {
        TreeMap<String, String> sorted = new TreeMap<>(state);
        List<String> leaves = new ArrayList<>();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            leaves.add(leafHash(e.getKey(), e.getValue()));
        }
        return leaves;
    }

    private static List<String> pad(List<String> level) {
        if (level.size() % 2 == 0) {
            return level;
        }
        List<String> padded = new ArrayList<>(level);
        padded.add(level.get(level.size() - 1));
        return padded;
    }

    private static List<String> parentLevel(List<String> level) {
        List<String> padded = pad(level);
        List<String> parent = new ArrayList<>();
        for (int i = 0; i < padded.size(); i += 2) {
            parent.add(Hashing.sha256(padded.get(i) + padded.get(i + 1)));
        }
        return parent;
    }
}
