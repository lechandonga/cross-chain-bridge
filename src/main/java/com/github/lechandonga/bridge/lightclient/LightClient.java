package com.github.lechandonga.bridge.lightclient;

import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.crypto.KeyDirectory;
import com.github.lechandonga.bridge.crypto.Signing;
import com.github.lechandonga.bridge.model.RejectionReason;

import java.security.PrivateKey;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻客户端：验证来源链区块头与状态包含证明。
 *
 * <p>区块头授权口径：每个签名都必须通过验证者公钥的密码学校验；
 * 伪造签名（不属于任何验证者）→ {@link RejectionReason#CREDENTIAL_MALFORMED}；
 * 同一验证者重复签名 → {@link RejectionReason#DUPLICATE_AUTHORIZATION}；
 * 去重后的有效授权不足阈值 → {@link RejectionReason#INSUFFICIENT_VALIDATORS}；
 * 已被来源链重组撤销/替换的区块头 → {@link RejectionReason#SOURCE_HEADER_REVOKED}。
 */
public class LightClient {

    public record VerificationResult(boolean ok, RejectionReason reason) {
        public static VerificationResult success() { return new VerificationResult(true, null); }
        public static VerificationResult failure(RejectionReason r) { return new VerificationResult(false, r); }
    }

    private record ValidatorSet(KeyDirectory keys, int threshold) {}

    private final Map<String, ValidatorSet> validatorSets = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> revokedHeaders = new ConcurrentHashMap<>();

    /** 注册来源链的验证者公钥集合与签名阈值。 */
    public void registerValidatorSet(String chainId, KeyDirectory validatorKeys, int threshold) {
        if (threshold <= 0 || threshold > validatorKeys.identities().size()) {
            throw new IllegalArgumentException("阈值必须在 (0, 验证者数量] 区间内");
        }
        validatorSets.put(chainId, new ValidatorSet(validatorKeys, threshold));
    }

    /** 验证者用私钥对区块头签名，返回 Base64 凭证。 */
    public static String sign(PrivateKey validatorKey, BlockHeader header) {
        return Signing.sign(validatorKey, header.hash());
    }

    /**
     * 标记一个来源链区块头已被重组撤销/替换（之后任何提交携带它都会被拒绝）。
     */
    public void markHeaderRevoked(String chainId, BlockHeader obsolete) {
        revokedHeaders.computeIfAbsent(chainId, k -> ConcurrentHashMap.newKeySet()).add(obsolete.hash());
    }

    /** 该区块头是否已被撤销。 */
    public boolean isHeaderRevoked(BlockHeader header) {
        Set<String> revoked = revokedHeaders.get(header.chainId());
        return revoked != null && revoked.contains(header.hash());
    }

    /** 验证区块头：链已注册、头未被撤销、签名有效且去重后达到阈值。 */
    public VerificationResult verifyHeader(BlockHeader header) {
        ValidatorSet vs = validatorSets.get(header.chainId());
        if (vs == null) {
            return VerificationResult.failure(RejectionReason.INSUFFICIENT_VALIDATORS);
        }
        if (isHeaderRevoked(header)) {
            return VerificationResult.failure(RejectionReason.SOURCE_HEADER_REVOKED);
        }

        // 逐个签名：确定唯一归属验证者；伪造与重复分别计数，不参与门槛
        Set<String> authorised = new HashSet<>();
        for (String credential : header.validatorSignatures()) {
            String signer = resolveSigner(vs.keys(), header.hash(), credential);
            if (signer == null) {
                return VerificationResult.failure(RejectionReason.CREDENTIAL_MALFORMED);
            }
            if (!authorised.add(signer)) {
                return VerificationResult.failure(RejectionReason.DUPLICATE_AUTHORIZATION);
            }
        }
        if (authorised.size() < vs.threshold()) {
            return VerificationResult.failure(RejectionReason.INSUFFICIENT_VALIDATORS);
        }
        return VerificationResult.success();
    }

    /** 找出凭证对应的唯一验证者身份；无法通过任何验证者公钥校验时返回 null。 */
    private String resolveSigner(KeyDirectory keys, String headerHash, String credential) {
        for (String identity : keys.identities()) {
            if (keys.keyOf(identity)
                    .filter(publicKey -> Signing.verify(publicKey, headerHash, credential))
                    .isPresent()) {
                return identity;
            }
        }
        return null;
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
