package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.Bridge;
import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.chain.SimulatedChain;
import com.github.lechandonga.bridge.crypto.IdentityRegistry;
import com.github.lechandonga.bridge.crypto.Signatures;
import com.github.lechandonga.bridge.lightclient.LightClient;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.RetryPolicy;
import com.github.lechandonga.bridge.relayer.Relayer;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 测试夹具：两条本地模拟链 + 身份登记本 + 轻客户端 + 桥接端点 + 中继器。 */
public class BridgeTestFixture {

    public static final String CHAIN_A = "chain-a";
    public static final String CHAIN_B = "chain-b";
    public static final List<String> VALIDATORS = List.of("val1", "val2", "val3");

    public final SimulatedChain chainA = new SimulatedChain(CHAIN_A);
    public final SimulatedChain chainB = new SimulatedChain(CHAIN_B);
    public final IdentityRegistry identities = new IdentityRegistry();
    public final LightClient lightClient = new LightClient(identities);
    public final Bridge bridge;
    public final Relayer relayer;

    /** 验证者密钥：val1/val2/val3。 */
    public final Map<String, KeyPair> validatorKeys = new HashMap<>();
    /** 常用发送方密钥。 */
    public final Map<String, KeyPair> senderKeys = new HashMap<>();

    public BridgeTestFixture() {
        for (String v : VALIDATORS) {
            KeyPair kp = Signatures.generateKeyPair();
            validatorKeys.put(v, kp);
            identities.register(v, kp.getPublic());
        }
        lightClient.registerValidatorSet(CHAIN_A, Set.copyOf(VALIDATORS), 2);

        // 登记常用发送方
        registerSender("alice");
        registerSender("mallory");

        bridge = new Bridge(chainB, lightClient, identities);
        relayer = new Relayer(bridge);

        // 轻客户端观察来源链创世头
        BlockHeader genesis = chainA.headerAt(0).orElseThrow();
        lightClient.observeCanonical(genesis);
    }

    /** 登记一个发送方身份并生成其密钥。 */
    public KeyPair registerSender(String sender) {
        KeyPair kp = Signatures.generateKeyPair();
        senderKeys.put(sender, kp);
        identities.register(sender, kp.getPublic());
        return kp;
    }

    /** 登记一个外部主体（例如不在验证者集合里的签名者），返回其密钥。 */
    public KeyPair registerOutsider(String subject) {
        KeyPair kp = Signatures.generateKeyPair();
        identities.register(subject, kp.getPublic());
        return kp;
    }

    /** 用发送方已登记的私钥对消息内容签发不可伪造凭证。 */
    public String credentialFor(CrossChainMessage unsigned) {
        KeyPair kp = senderKeys.get(unsigned.sender());
        if (kp == null) {
            throw new IllegalStateException("发送方未登记: " + unsigned.sender());
        }
        return Signatures.issue(kp.getPrivate(), unsigned.sender(), unsigned.contentHash());
    }

    /** 构造一条已签名的合法消息。 */
    public CrossChainMessage signedMessage(String id, String sender, String payload,
                                           long fee, long gasLimit, long validUntil, int maxRetries) {
        if (!senderKeys.containsKey(sender)) {
            registerSender(sender);
        }
        CrossChainMessage unsigned = new CrossChainMessage(id, CHAIN_A, CHAIN_B, sender, 1,
                payload, fee, gasLimit, 0, validUntil, new RetryPolicy(maxRetries), null);
        String sig = credentialFor(unsigned);
        return new CrossChainMessage(id, CHAIN_A, CHAIN_B, sender, 1,
                payload, fee, gasLimit, 0, validUntil, new RetryPolicy(maxRetries), sig);
    }

    public CrossChainMessage signedMessage(String id, String payload) {
        return signedMessage(id, "alice", payload, 10, 10_000, 1_000, 3);
    }

    public CrossChainMessage signedMessage(String id, String sender, String payload) {
        return signedMessage(id, sender, payload, 10, 10_000, 1_000, 3);
    }

    /** 用指定验证者集合对区块头生成授权凭证（头哈希不含签名列表）。 */
    public List<String> validatorSignatures(BlockHeader header, List<String> signers) {
        return signers.stream()
                .map(v -> LightClient.sign(validatorKeys.get(v).getPrivate(), v, header))
                .toList();
    }

    /** 全部已登记验证者签名。 */
    public List<String> allValidatorSignatures(BlockHeader header) {
        return validatorSignatures(header, VALIDATORS);
    }

    /** 在来源链 A 上登记 outbox 并出块（带 2/3 验证者授权），返回已授权区块头。 */
    public BlockHeader commitOnSource(CrossChainMessage message) {
        chainA.applyNow("outbox:" + message.id(), message.contentHash());
        BlockHeader header = chainA.commitBlock();
        BlockHeader signed = signedHeader(header, List.of("val1", "val2"));
        lightClient.observeCanonical(signed);
        return signed;
    }

    /** 给一个已存在的来源链区块头附加验证者授权凭证。 */
    public BlockHeader signedHeader(BlockHeader header, List<String> signers) {
        return new BlockHeader(header.chainId(), header.height(), header.parentHash(),
                header.stateRoot(), header.timestamp(), validatorSignatures(header, signers));
    }

    /** 针对来源链当前状态构建消息证明。 */
    public MerkleProof proofFor(CrossChainMessage message) {
        return MerkleProof.build(chainA.stateView(), "outbox:" + message.id());
    }

    /** 一步到位：来源链登记 + 出块 + 构造证明。 */
    public Relayer.Delivery prepareDelivery(CrossChainMessage message) {
        BlockHeader header = commitOnSource(message);
        return new Relayer.Delivery(message, proofFor(message), header);
    }

    /**
     * 来源链发生重组后，把新正统链喂给轻客户端（创世除外，逐块重放并补授权）。
     */
    public void syncSourceCanonicalAfterReorg() {
        long head = chainA.headHeight();
        for (long h = 1; h <= head; h++) {
            BlockHeader bare = chainA.headerAt(h).orElseThrow();
            BlockHeader signed = signedHeader(bare, List.of("val1", "val2"));
            if (h < head) {
                lightClient.observeCanonical(signed);
            } else {
                lightClient.resetCanonicalHead(signed);
            }
        }
    }

    public long balanceOf(String account) {
        return chainB.get("balance:" + account).map(Long::parseLong).orElse(0L);
    }

    public long collectedFees() {
        return chainB.get("fees:collector").map(Long::parseLong).orElse(0L);
    }
}
