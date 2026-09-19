package com.github.lechandonga.crosschainbridge.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** SHA-256 / Merkle 树工具。 */
public final class HashUtils {

    /** 空数据的约定根（32 字节全零）。 */
    public static final byte[] EMPTY_ROOT = new byte[32];

    private HashUtils() {
    }

    public static byte[] sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static byte[] sha256(String input) {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    /** 串联后哈希：H(left || right)。 */
    public static byte[] concatHash(byte[] left, byte[] right) {
        byte[] joined = new byte[left.length + right.length];
        System.arraycopy(left, 0, joined, 0, left.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return sha256(joined);
    }

    public static String hex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 用叶子列表构造 Merkle 根。
     * 规则：叶子本身已经是哈希值；每一层两两配对 H(a||b)，
     * 奇数个节点时复制最后一个节点；单个叶子的根即其自身；空列表为 EMPTY_ROOT。
     */
    public static byte[] merkleRoot(List<byte[]> leaves) {
        if (leaves == null || leaves.isEmpty()) {
            return EMPTY_ROOT.clone();
        }
        List<byte[]> layer = new ArrayList<>(leaves);
        while (layer.size() > 1) {
            List<byte[]> next = new ArrayList<>((layer.size() + 1) / 2);
            for (int i = 0; i < layer.size(); i += 2) {
                byte[] left = layer.get(i);
                byte[] right = (i + 1 < layer.size()) ? layer.get(i + 1) : layer.get(i);
                next.add(concatHash(left, right));
            }
            layer = next;
        }
        return layer.get(0).clone();
    }
}
