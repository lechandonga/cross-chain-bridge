package com.github.lechandonga.bridge.lightclient;

import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.model.RejectionReason;

import java.util.Set;

/** 轻客户端：验证来源链区块头与状态包含证明。 */
public class LightClient {

    public record VerificationResult(boolean ok, RejectionReason reason) {
        public static VerificationResult success() { return new VerificationResult(true, null); }
        public static VerificationResult failure(RejectionReason r) { return new VerificationResult(false, r); }
    }

    /** 注册来源链的验证者集合与签名阈值。 */
    public void registerValidatorSet(String chainId, Set<String> validators, int threshold) {
    }

    /** 验证区块头：链标识、验证者签名数量与有效性。 */
    public VerificationResult verifyHeader(BlockHeader header) { return null; }

    /** 验证消息包含证明：证明覆盖目标消息且与区块头状态根一致。 */
    public VerificationResult verifyInclusion(BlockHeader header, MerkleProof proof, String expectedKey) {
        return null;
    }
}
