package com.github.lechandonga.crosschainbridge.support;

import com.github.lechandonga.crosschainbridge.crypto.HashUtils;
import com.github.lechandonga.crosschainbridge.model.BlockHeader;
import com.github.lechandonga.crosschainbridge.model.MessageProof;
import com.github.lechandonga.crosschainbridge.model.MerkleProof;
import com.github.lechandonga.crosschainbridge.model.SignedMessage;

import java.util.ArrayList;
import java.util.List;

/** 构造各类被篡改/不合法证明的测试辅助。 */
public final class TamperedProofs {

    private TamperedProofs() {
    }

    /** 篡改 Merkle 路径第一个节点：叶子无法重算出区块交易根。 */
    public static MessageProof withBrokenMerklePath(MessageProof original) {
        MerkleProof p = original.merkleProof();
        List<byte[]> newPath = new ArrayList<>(p.path());
        byte[] corrupted = p.path().get(0).clone();
        corrupted[0] ^= (byte) 0xFF;
        newPath.set(0, corrupted);
        MerkleProof broken = new MerkleProof(p.leafHash(), newPath, p.pathDirection(),
                p.blockHeight(), p.leafIndex());
        return new MessageProof(original.signedMessage(), broken, original.sourceHeader());
    }

    /** 证明叶子与被签名消息哈希不一致（证明覆盖的是另一条消息）。 */
    public static MessageProof withForeignLeaf(MessageProof original) {
        MerkleProof p = original.merkleProof();
        byte[] foreignLeaf = HashUtils.sha256("a-different-message");
        MerkleProof foreign = new MerkleProof(foreignLeaf, p.path(), p.pathDirection(),
                p.blockHeight(), p.leafIndex());
        return new MessageProof(original.signedMessage(), foreign, original.sourceHeader());
    }

    /** 提交区块头的状态根与轻客户端已同步头不一致。 */
    public static MessageProof withStateRootMismatch(MessageProof original) {
        BlockHeader h = original.sourceHeader();
        byte[] fakeRoot = HashUtils.sha256("fake-state-root");
        BlockHeader fake = new BlockHeader(h.height(), h.prevHash(), fakeRoot, h.transactionsRoot());
        fake.setHash(h.hash());
        fake.setFinalized(h.finalized());
        return new MessageProof(original.signedMessage(), original.merkleProof(), fake);
    }

    /** 提交区块头的哈希与内容不一致（头部本身被篡改）。 */
    public static MessageProof withHeaderHashMismatch(MessageProof original) {
        BlockHeader h = original.sourceHeader();
        BlockHeader fake = new BlockHeader(h.height(), h.prevHash(), h.stateRoot(), h.transactionsRoot());
        fake.setHash(HashUtils.sha256("forged-header-hash"));
        fake.setFinalized(h.finalized());
        return new MessageProof(original.signedMessage(), original.merkleProof(), fake);
    }

}
