package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重新执行前的来源验证：无论是 RETRYABLE 重试，还是重组撤销后的重新提交，
 * 每一次都必须重新完成完整来源验证；失效/被替换的证明必须被拒绝且目标链状态不变。
 */
class ReexecutionVerificationTest {

    private CrossChainMessage gasLimited(BridgeTestFixture f, String id) {
        return f.signedMessage(id, "alice", "transfer:bob:100", 10, 50, 1_000, 3);
    }

    @Test
    void retryRechecksStoredSourceHeader_revoked() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = gasLimited(f, "x1");
        var delivery = f.prepareDelivery(msg);
        f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(MessageStatus.RETRYABLE, f.bridge.statusOf(msg.id()).orElseThrow());

        // 来源链区块头在首次提交之后被撤销；不带新材料的重试必须重新验证并拒绝
        f.lightClient.markHeaderRevoked(BridgeTestFixture.CHAIN_A, delivery.header());
        BridgeResult retry = f.bridge.retry(msg.id());
        assertEquals(RejectionReason.SOURCE_HEADER_REVOKED, retry.reason());
        assertEquals(MessageStatus.RETRYABLE, f.bridge.statusOf(msg.id()).orElseThrow(),
                "验证失败不得改变消息状态");
        assertEquals(0, f.balanceOf("bob"));
        assertEquals(0, f.collectedFees());
    }

    @Test
    void revokedHeaderCannotBeReusedButFreshCanonicalHeaderRetries() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = gasLimited(f, "x2");
        var delivery = f.prepareDelivery(msg);
        f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        f.lightClient.markHeaderRevoked(BridgeTestFixture.CHAIN_A, delivery.header());

        // 来源链在新的规范链上重新登记 outbox 并出块：新头哈希不在撤销名单中
        BlockHeader freshHeader = f.commitOnSource(msg);
        MerkleProof freshProof = f.proofFor(msg);
        // 携带仍然撤销的旧材料 -> 拒绝
        assertEquals(RejectionReason.SOURCE_HEADER_REVOKED,
                f.bridge.retry(msg.id(), delivery.proof(), delivery.header()).reason());
        // 携带新的规范材料 -> 重新验证通过（gas 仍超限，保持 RETRYABLE，且预算被扣减一次）
        BridgeResult retry = f.bridge.retry(msg.id(), freshProof, freshHeader);
        assertEquals(MessageStatus.RETRYABLE, retry.status());
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void retryWithForgedFreshHeaderRejectedWithoutStateChange() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = gasLimited(f, "x3");
        var delivery = f.prepareDelivery(msg);
        f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());

        // 伪造的新区块头签名（非验证者私钥）
        BlockHeader base = delivery.header();
        String fakeSig = com.github.lechandonga.bridge.lightclient.LightClient.sign(
                com.github.lechandonga.bridge.crypto.Signing.generateKeyPair().getPrivate(), base);
        BlockHeader forged = new BlockHeader(base.chainId(), base.height(), base.parentHash(),
                base.stateRoot(), base.timestamp(), java.util.List.of(fakeSig, fakeSig));
        BridgeResult retry = f.bridge.retry(msg.id(), delivery.proof(), forged);
        assertEquals(RejectionReason.CREDENTIAL_MALFORMED, retry.reason());
        assertEquals(MessageStatus.RETRYABLE, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(0, f.balanceOf("bob"));
    }

    @Test
    void revertedMessageResubmissionRequiresValidSource() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("x4", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);
        f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        // 目标链重组撤销执行
        f.bridge.handleReorg(1);
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(msg.id()).orElseThrow());

        // 重组撤销后重新提交，但来源头此刻已失效 -> 必须重新完整验证并拒绝，目标链不变
        f.lightClient.markHeaderRevoked(BridgeTestFixture.CHAIN_A, delivery.header());
        BridgeResult blocked = f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        assertEquals(RejectionReason.SOURCE_HEADER_REVOKED, blocked.reason());
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(0, f.balanceOf("bob"));
        assertEquals(0, f.collectedFees());
    }

    @Test
    void revertedMessageWithFreshSourceExecutesExactlyOnce() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = f.signedMessage("x5", "transfer:bob:100");
        var delivery = f.prepareDelivery(msg);
        f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        f.bridge.handleReorg(1);
        assertEquals(MessageStatus.REVERTED, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(0, f.balanceOf("bob"));

        // 来源链在新的规范链上重新登记该消息并出块（新的有效区块头）
        BlockHeader newHeader = f.commitOnSource(msg);
        MerkleProof newProof = f.proofFor(msg);
        BridgeResult again = f.bridge.submit(delivery.message(), newProof, newHeader);
        assertEquals(MessageStatus.EXECUTED, again.status());
        assertEquals(100, f.balanceOf("bob"));
        assertEquals(10, f.collectedFees());

        // 之后重复投递仍是重放
        assertEquals(RejectionReason.REPLAY,
                f.bridge.submit(delivery.message(), newProof, newHeader).reason());
    }

    @Test
    void failedSourceVerificationOnRetryKeepsRetryBudget() {
        BridgeTestFixture f = new BridgeTestFixture();
        CrossChainMessage msg = gasLimited(f, "x6");
        var delivery = f.prepareDelivery(msg);
        f.bridge.submit(delivery.message(), delivery.proof(), delivery.header());
        f.lightClient.markHeaderRevoked(BridgeTestFixture.CHAIN_A, delivery.header());

        // 连续两次来源验证失败：重试预算都不得被扣减
        assertEquals(RejectionReason.SOURCE_HEADER_REVOKED, f.bridge.retry(msg.id()).reason());
        assertEquals(RejectionReason.SOURCE_HEADER_REVOKED, f.bridge.retry(msg.id()).reason());
        assertEquals(MessageStatus.RETRYABLE, f.bridge.statusOf(msg.id()).orElseThrow());
        assertEquals(0, f.balanceOf("bob"));
    }
}
