package com.github.lechandonga.bridge.lightclient;

import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.model.RejectionReason;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻客户端：验证来源链区块头与状态包含证明。
 * 签名模拟格式："sig:&lt;validator&gt;:&lt;headerHash&gt;"，须属于已注册验证者集合。
 */
public class LightClient {

    public record VerificationResult(boolean ok, RejectionReason reason) {
        public static VerificationResult success() { return new VerificationResult(true, null); }
        public static VerificationResult failure(RejectionReason r) { return new VerificationResult(false, r); }
    }

    private record ValidatorSet(Set<String> validators, int threshold) {}

    private final Map<String, ValidatorSet> validatorSets = new ConcurrentHashMap<>();

    /** 注册来源链的验证者集合与签名阈值。 */
    public void registerValidatorSet(String chainId, Set<String> validators, int threshold) {
        validatorSets.put(chainId, new ValidatorSet(Set.copyOf(validators), threshold));
    }

    /** 生成模拟签名（测试与来源链出块使用）。 */
    public static String sign(String validator, BlockHeader header) {
        return "sig:" + validator + ":" + header.hash();
    }

    /** 验证区块头：链已注册、验证者签名数量达到阈值且签名有效。 */
    public VerificationResult verifyHeader(BlockHeader header) {
        ValidatorSet vs = validatorSets.get(header.chainId());
        if (vs == null) {
            return VerificationResult.failure(RejectionReason.INSUFFICIENT_VALIDATORS);
        }
        Set<String> signed = new java.util.HashSet<>();
        for (String sig : header.validatorSignatures()) {
            String[] parts = sig.split(":", 3);
            if (parts.length == 3
                    && vs.validators().contains(parts[1])
                    && parts[2].equals(header.hash())) {
                signed.add(parts[1]);
            }
        }
        if (signed.size() < vs.threshold()) {
            return VerificationResult.failure(RejectionReason.INSUFFICIENT_VALIDATORS);
        }
        return VerificationResult.success();
    }

    /** 验证消息包含证明：证明覆盖目标 key 且重算根与区块头状态根一致。 */
    public VerificationResult verifyInclusion(BlockHeader header, MerkleProof proof, String expectedKey) {
        if (proof == null || !proof.key().equals(expectedKey)) {
            return VerificationResult.failure(RejectionReason.PROOF_NOT_COVERING);
        }
        if (!proof.computeRoot().equals(header.stateRoot())) {
            return VerificationResult.failure(RejectionReason.STATE_ROOT_MISMATCH);
        }
        return VerificationResult.success();
    }
}
