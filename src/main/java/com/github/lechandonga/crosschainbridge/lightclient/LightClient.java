package com.github.lechandonga.crosschainbridge.lightclient;

import com.github.lechandonga.crosschainbridge.crypto.MerkleTreeBuilder;
import com.github.lechandonga.crosschainbridge.crypto.MessageSigner;
import com.github.lechandonga.crosschainbridge.model.BlockHeader;
import com.github.lechandonga.crosschainbridge.model.FailureReason;
import com.github.lechandonga.crosschainbridge.model.MessageProof;
import com.github.lechandonga.crosschainbridge.model.SignedMessage;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 轻客户端：保存来源链区块头与验证者公钥集合，
 * 校验消息包含证明、区块头状态根一致性与验证者仲裁。
 *
 * <p>三类拒绝路径互相独立、返回不同的 {@link FailureReason}：
 * <ol>
 *   <li>区块头未知或状态根/哈希与已同步头不一致 → HEADER_STATE_ROOT_MISMATCH</li>
 *   <li>Merkle 证明无法把消息叶子关联到区块交易根 → PROOF_NOT_COVERING_MESSAGE</li>
 *   <li>合法验证者有效签名数低于仲裁阈值 → INSUFFICIENT_VALIDATOR_QUORUM</li>
 * </ol>
 */
public final class LightClient {

    private final List<KeyPair> validatorKeys;
    private final long quorumThreshold;
    private final Map<Long, BlockHeader> headersByHeight = new TreeMap<>();

    public LightClient(List<KeyPair> validatorKeys, long quorumThreshold) {
        if (quorumThreshold < 1 || quorumThreshold > validatorKeys.size()) {
            throw new IllegalArgumentException("invalid quorum: " + quorumThreshold
                    + " of " + validatorKeys.size());
        }
        this.validatorKeys = new ArrayList<>(validatorKeys);
        this.quorumThreshold = quorumThreshold;
    }

    /** 同步/替换一个区块头（重组时同高度旧头被替换）。 */
    public synchronized void ingestHeader(BlockHeader header) {
        headersByHeight.put(header.height(), header);
    }

    /** 直接按字段同步一个区块头（测试构造场景使用）。 */
    public synchronized void ingestHeader(long height, byte[] stateRoot, byte[] transactionsRoot,
                                          byte[] headerHash, byte[] prevHash, boolean finalized) {
        BlockHeader header = new BlockHeader(height, prevHash, stateRoot, transactionsRoot);
        header.setHash(headerHash);
        header.setFinalized(finalized);
        headersByHeight.put(height, header);
    }

    public synchronized BlockHeader knownHeaderAt(long height) {
        return headersByHeight.get(height);
    }

    public long quorumThreshold() {
        return quorumThreshold;
    }

    public int validatorCount() {
        return validatorKeys.size();
    }

    /**
     * 完整验证一条入站消息证明。
     *
     * @return null 表示验证通过；否则返回可区分的失败原因
     */
    public synchronized FailureReason verify(MessageProof proof) {
        if (proof == null || proof.signedMessage() == null || proof.merkleProof() == null
                || proof.sourceHeader() == null) {
            return FailureReason.PROOF_NOT_COVERING_MESSAGE;
        }
        BlockHeader submitted = proof.sourceHeader();

        // —— 检查 1：区块头与状态根一致性 ——
        BlockHeader known = headersByHeight.get(submitted.height());
        if (known == null) {
            return FailureReason.HEADER_STATE_ROOT_MISMATCH;
        }
        if (!submitted.commitsSameValues(known)
                || !java.security.MessageDigest.isEqual(submitted.hash(), known.hash())) {
            return FailureReason.HEADER_STATE_ROOT_MISMATCH;
        }

        // —— 检查 2：证明是否覆盖目标消息 ——
        SignedMessage signed = proof.signedMessage();
        if (!java.security.MessageDigest.isEqual(proof.merkleProof().leafHash(), signed.messageHash())) {
            return FailureReason.PROOF_NOT_COVERING_MESSAGE;
        }
        if (proof.merkleProof().blockHeight() != submitted.height()) {
            return FailureReason.PROOF_NOT_COVERING_MESSAGE;
        }
        if (!MerkleTreeBuilder.verify(proof.merkleProof(), known.transactionsRoot())) {
            return FailureReason.PROOF_NOT_COVERING_MESSAGE;
        }

        // —— 检查 3：验证者仲裁 ——
        long validSignatures = countValidSignatures(signed);
        if (validSignatures < quorumThreshold) {
            return FailureReason.INSUFFICIENT_VALIDATOR_QUORUM;
        }
        return null;
    }

    private long countValidSignatures(SignedMessage signed) {
        List<byte[]> signatures = signed.signatures();
        long valid = 0;
        for (KeyPair validatorKey : validatorKeys) {
            for (byte[] signature : signatures) {
                if (MessageSigner.verifySignatureHash(signed.messageHash(), signature,
                        validatorKey.getPublic())) {
                    valid++;
                    break; // 每个验证者最多计一票
                }
            }
        }
        return valid;
    }
}
