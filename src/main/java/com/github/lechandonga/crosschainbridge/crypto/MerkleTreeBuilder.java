package com.github.lechandonga.crosschainbridge.crypto;

import com.github.lechandonga.crosschainbridge.model.MerkleProof;

import java.util.ArrayList;
import java.util.List;

/** 构造 Merkle 包含证明并校验证明是否匹配给定根。 */
public final class MerkleTreeBuilder {

    private final List<byte[]> leaves;
    /** levels.get(0) 为叶子层，最后一层只有根。 */
    private final List<List<byte[]>> levels = new ArrayList<>();

    public MerkleTreeBuilder(List<byte[]> leaves) {
        this.leaves = List.copyOf(leaves);
        buildLevels();
    }

    private void buildLevels() {
        if (leaves.isEmpty()) {
            levels.add(List.of(HashUtils.EMPTY_ROOT.clone()));
            return;
        }
        List<byte[]> current = new ArrayList<>(leaves);
        levels.add(current);
        while (current.size() > 1) {
            List<byte[]> next = new ArrayList<>((current.size() + 1) / 2);
            for (int i = 0; i < current.size(); i += 2) {
                byte[] left = current.get(i);
                byte[] right = (i + 1 < current.size()) ? current.get(i + 1) : current.get(i);
                next.add(HashUtils.concatHash(left, right));
            }
            levels.add(next);
            current = next;
        }
    }

    public byte[] root() {
        return levels.get(levels.size() - 1).get(0);
    }

    public int leafCount() {
        return leaves.size();
    }

    /**
     * 为指定叶子生成包含证明。
     * pathDirection.get(i)==true 表示该层兄弟节点在右侧（当前计算值在左）。
     */
    public MerkleProof proofFor(int leafIndex, long blockHeight) {
        if (leafIndex < 0 || leafIndex >= leaves.size()) {
            throw new IndexOutOfBoundsException("leaf index " + leafIndex + " out of range " + leaves.size());
        }
        List<byte[]> path = new ArrayList<>();
        List<Boolean> direction = new ArrayList<>();
        int index = leafIndex;
        for (int level = 0; level < levels.size() - 1; level++) {
            List<byte[]> current = levels.get(level);
            boolean siblingOnRight = (index % 2 == 0);
            int siblingIndex;
            if (siblingOnRight) {
                // 当前在左，兄弟 index+1；若越界则兄弟为自身复制
                siblingIndex = Math.min(index + 1, current.size() - 1);
            } else {
                siblingIndex = index - 1;
            }
            path.add(current.get(siblingIndex).clone());
            direction.add(siblingOnRight);
            index /= 2;
        }
        return new MerkleProof(leaves.get(leafIndex).clone(), path, direction, blockHeight, leafIndex);
    }

    /** 用证明重算根并与 expectedRoot 比较。 */
    public static boolean verify(MerkleProof proof, byte[] expectedRoot) {
        if (proof == null || expectedRoot == null) {
            return false;
        }
        byte[] computed = proof.leafHash().clone();
        for (int i = 0; i < proof.path().size(); i++) {
            byte[] sibling = proof.path().get(i);
            if (Boolean.TRUE.equals(proof.pathDirection().get(i))) {
                computed = HashUtils.concatHash(computed, sibling);
            } else {
                computed = HashUtils.concatHash(sibling, computed);
            }
        }
        return java.security.MessageDigest.isEqual(computed, expectedRoot);
    }
}
