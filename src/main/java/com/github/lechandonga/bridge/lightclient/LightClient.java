package com.github.lechandonga.bridge.lightclient;

import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.crypto.IdentityRegistry;
import com.github.lechandonga.bridge.crypto.Signatures;
import com.github.lechandonga.bridge.model.RejectionReason;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 轻客户端：验证来源链区块头与状态包含证明。
 *
 * <p>区块头授权凭证格式为 {@code cred:v1:<validator>:<sig(headerHash)>}，
 * 用验证者登记公钥校验，与消息凭证同一口径：</p>
 * <ul>
 *   <li>凭证无法解析/签名被篡改：{@link RejectionReason#INVALID_SIGNATURE}；</li>
 *   <li>凭证有效但签发者不在验证者集合：{@link RejectionReason#AUTHORIZER_NOT_AUTHORIZED}；</li>
 *   <li>同一验证者被重复计入：{@link RejectionReason#DUPLICATE_AUTHORIZER}；</li>
 *   <li>去重后的有效授权数仍不足阈值：{@link RejectionReason#INSUFFICIENT_VALIDATORS}。</li>
 * </ul>
 *
 * <p>此外只接受落在来源链当前正统链上的区块头；被重组取代的区块头
 * 返回 {@link RejectionReason#SOURCE_HEADER_SUPERSEDED}，重复或伪造授权都不能凑门槛。</p>
 */
public class LightClient {

    public record VerificationResult(boolean ok, RejectionReason reason) {
        public static VerificationResult success() { return new VerificationResult(true, null); }
        public static VerificationResult failure(RejectionReason r) { return new VerificationResult(false, r); }
    }

    /** 授权计数结果：原因非空表示失败。 */
    private record AuthorizationResult(RejectionReason failure, int validCount) {
        boolean ok() { return failure == null; }
    }

    private record ValidatorSet(Set<String> validators, int threshold) {}

    /** 一条来源链在轻客户端侧的视图：已知区块头（按哈希）+ 正统头哈希 + 创世哈希。 */
    private static final class ChainView {
        final Map<String, BlockHeader> known = new HashMap<>();
        String canonicalHeadHash;
        String genesisHash;
    }

    private final IdentityRegistry identities;
    private final Map<String, ValidatorSet> validatorSets = new HashMap<>();
    private final Map<String, ChainView> views = new HashMap<>();

    public LightClient(IdentityRegistry identities) {
        this.identities = identities;
    }

    /** 注册来源链的验证者集合与签名阈值。 */
    public synchronized void registerValidatorSet(String chainId, Set<String> validators, int threshold) {
        if (threshold <= 0 || threshold > validators.size()) {
            throw new IllegalArgumentException("阈值必须在 1..验证者数量之间");
        }
        validatorSets.put(chainId, new ValidatorSet(Set.copyOf(validators), threshold));
    }

    /**
     * 登记一个来源链区块头为正统链上的区块头。
     *
     * <p>调用方（观察来源链的中继/测试夹具）在来源链出块或重组后，
     * 沿新链从创世开始逐个把区块头喂给轻客户端。区块头本身必须通过授权门槛校验；
     * 同高度出现不同哈希时正统头随之切换，旧分叉头被视为已被取代。</p>
     */
    public synchronized VerificationResult observeCanonical(BlockHeader header) {
        ValidatorSet vs = validatorSets.get(header.chainId());
        if (vs == null) {
            return VerificationResult.failure(RejectionReason.INSUFFICIENT_VALIDATORS);
        }
        ChainView view = views.computeIfAbsent(header.chainId(), k -> new ChainView());

        if (header.height() == 0) {
            view.genesisHash = header.hash();
            view.known.put(header.hash(), header);
            if (view.canonicalHeadHash == null) {
                view.canonicalHeadHash = header.hash();
            }
            return advanceIfBetter(view, header);
        }
        AuthorizationResult auth = countAuthorizations(vs, header);
        if (!auth.ok()) {
            return VerificationResult.failure(auth.failure());
        }
        if (view.genesisHash == null) {
            // 未登记创世，无法确认父链
            return VerificationResult.failure(RejectionReason.SOURCE_HEADER_SUPERSEDED);
        }
        BlockHeader parent = view.known.get(header.parentHash());
        if (parent == null || parent.height() != header.height() - 1) {
            return VerificationResult.failure(RejectionReason.SOURCE_HEADER_SUPERSEDED);
        }
        view.known.put(header.hash(), header);
        return advanceIfBetter(view, header);
    }

    /**
     * 来源链发生重组后，把正统头显式回退到新链的末端。
     *
     * <p>与 {@link #observeCanonical} 的区别：新头高度可能低于旧头，
     * 因此不做"只增不减"的推进，而是在区块头授权有效、父链可连回创世的前提下，
     * 把正统头直接指向该区块头；旧分叉上的区块头随后被判定为已取代。</p>
     */
    public synchronized VerificationResult resetCanonicalHead(BlockHeader header) {
        ValidatorSet vs = validatorSets.get(header.chainId());
        if (vs == null) {
            return VerificationResult.failure(RejectionReason.INSUFFICIENT_VALIDATORS);
        }
        ChainView view = views.get(header.chainId());
        if (view == null || view.genesisHash == null) {
            return VerificationResult.failure(RejectionReason.SOURCE_HEADER_SUPERSEDED);
        }
        if (header.height() > 0) {
            AuthorizationResult auth = countAuthorizations(vs, header);
            if (!auth.ok()) {
                return VerificationResult.failure(auth.failure());
            }
        }
        // 父链必须经已知区块头一直连到创世，拒绝凭空给出的头
        BlockHeader cursor = header;
        while (cursor.height() > 0) {
            BlockHeader parent = view.known.get(cursor.parentHash());
            if (parent == null) {
                return VerificationResult.failure(RejectionReason.SOURCE_HEADER_SUPERSEDED);
            }
            cursor = parent;
        }
        if (!cursor.hash().equals(view.genesisHash)) {
            return VerificationResult.failure(RejectionReason.SOURCE_HEADER_SUPERSEDED);
        }
        view.known.put(header.hash(), header);
        view.canonicalHeadHash = header.hash();
        return VerificationResult.success();
    }

    /** 同高度分叉：以新观察到的头切换正统头；高度不大于当前头则仅登记不切换。 */
    private VerificationResult advanceIfBetter(ChainView view, BlockHeader header) {
        BlockHeader current = view.canonicalHeadHash == null ? null : view.known.get(view.canonicalHeadHash);
        if (current == null || header.height() > current.height()
                || (header.height() == current.height() && !header.hash().equals(current.hash()))) {
            view.canonicalHeadHash = header.hash();
        }
        return VerificationResult.success();
    }

    /** 生成验证者授权凭证（测试与来源链出块使用）。 */
    public static String sign(java.security.PrivateKey validatorKey, String validator, BlockHeader header) {
        return Signatures.issue(validatorKey, validator, header.hash());
    }

    /**
     * 验证区块头：链已注册、授权有效且达阈值，并且区块头位于当前正统链上。
     */
    public synchronized VerificationResult verifyHeader(BlockHeader header) {
        if (header == null) {
            return VerificationResult.failure(RejectionReason.PROOF_NOT_COVERING);
        }
        ValidatorSet vs = validatorSets.get(header.chainId());
        if (vs == null) {
            return VerificationResult.failure(RejectionReason.INSUFFICIENT_VALIDATORS);
        }
        AuthorizationResult auth = countAuthorizations(vs, header);
        if (!auth.ok()) {
            return VerificationResult.failure(auth.failure());
        }
        if (!isCanonical(header)) {
            return VerificationResult.failure(RejectionReason.SOURCE_HEADER_SUPERSEDED);
        }
        return VerificationResult.success();
    }

    /** 区块头是否在当前正统链上：自身或其祖先链能一直连到正统头。 */
    private boolean isCanonical(BlockHeader header) {
        ChainView view = views.get(header.chainId());
        if (view == null || view.canonicalHeadHash == null) {
            return false;
        }
        BlockHeader cursor = view.known.get(view.canonicalHeadHash);
        String target = header.hash();
        while (cursor != null) {
            if (cursor.hash().equals(target)) {
                return true;
            }
            if (cursor.height() == 0) {
                return false;
            }
            cursor = view.known.get(cursor.parentHash());
        }
        return false;
    }

    /**
     * 按统一口径统计区块头授权。
     *
     * <p>优先级：凭证伪造/格式损坏 → 越权主体 → 重复授权 → 数量不足。
     * 伪造、越权或重复的授权一律不计入门槛。</p>
     */
    private AuthorizationResult countAuthorizations(ValidatorSet vs, BlockHeader header) {
        Set<String> counted = new HashSet<>();
        boolean duplicate = false;
        boolean forged = false;
        boolean outsider = false;
        for (String credential : header.validatorSignatures()) {
            Signatures.ParsedCredential parsed = Signatures.parse(credential);
            if (parsed == null) {
                forged = true;
                continue;
            }
            String validator = parsed.subject();
            boolean validSignature = identities.verifyFor(validator, header.hash(), credential);
            if (!validSignature) {
                forged = true; // 篡改、用错密钥或主体与凭证声明不一致
                continue;
            }
            if (!vs.validators().contains(validator)) {
                outsider = true;
                continue;
            }
            if (!counted.add(validator)) {
                duplicate = true; // 同一验证者重复出现，重复的那份不得凑门槛
            }
        }
        if (counted.size() >= vs.threshold()) {
            return new AuthorizationResult(null, counted.size());
        }
        if (forged) {
            return new AuthorizationResult(RejectionReason.INVALID_SIGNATURE, counted.size());
        }
        if (outsider) {
            return new AuthorizationResult(RejectionReason.AUTHORIZER_NOT_AUTHORIZED, counted.size());
        }
        if (duplicate) {
            return new AuthorizationResult(RejectionReason.DUPLICATE_AUTHORIZER, counted.size());
        }
        return new AuthorizationResult(RejectionReason.INSUFFICIENT_VALIDATORS, counted.size());
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
