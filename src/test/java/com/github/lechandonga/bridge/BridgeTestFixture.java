package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.Bridge;
import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.chain.SimulatedChain;
import com.github.lechandonga.bridge.crypto.CredentialVerifier;
import com.github.lechandonga.bridge.crypto.KeyDirectory;
import com.github.lechandonga.bridge.crypto.Signing;
import com.github.lechandonga.bridge.crypto.SimpleKeyDirectory;
import com.github.lechandonga.bridge.lightclient.LightClient;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.RetryPolicy;
import com.github.lechandonga.bridge.relayer.Relayer;

import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 测试夹具：两条本地模拟链 + 轻客户端 + 桥接端点 + 中继器；全部凭证使用 Ed25519 真实签名。 */
public class BridgeTestFixture {

    public static final String CHAIN_A = "chain-a";
    public static final String CHAIN_B = "chain-b";
    public static final List<String> VALIDATORS = List.of("val1", "val2", "val3");

    public final SimulatedChain chainA = new SimulatedChain(CHAIN_A);
    public final SimulatedChain chainB = new SimulatedChain(CHAIN_B);
    public final LightClient lightClient = new LightClient();
    /** 发送方身份 -> 密钥对（含公钥注册），未出现过的发送方在首次签名时自动建档。 */
    private final Map<String, KeyPair> senderKeys = new ConcurrentHashMap<>();
    /** 验证者身份 -> 密钥对。 */
    private final Map<String, KeyPair> validatorKeys = new ConcurrentHashMap<>();
    public final KeyDirectory senderDirectory = new SimpleKeyDirectory();
    public final KeyDirectory validatorDirectory = new SimpleKeyDirectory();
    public final Bridge bridge;
    public final Relayer relayer;

    public BridgeTestFixture() {
        for (String validator : VALIDATORS) {
            KeyPair pair = Signing.generateKeyPair();
            validatorKeys.put(validator, pair);
            validatorDirectory.register(validator, pair.getPublic());
        }
        lightClient.registerValidatorSet(CHAIN_A, validatorDirectory, 2);
        // alice 是默认合法发送方
        registerSender("alice");
        bridge = new Bridge(chainB, lightClient, new CredentialVerifier(senderDirectory));
        relayer = new Relayer(bridge);
    }

    /** 注册一个发送方身份并生成其密钥对，返回私钥持有者（测试可用于签名/伪造场景）。 */
    public KeyPair registerSender(String sender) {
        KeyPair pair = senderKeys.computeIfAbsent(sender, s -> {
            KeyPair fresh = Signing.generateKeyPair();
            senderDirectory.register(s, fresh.getPublic());
            return fresh;
        });
        return pair;
    }

    /** 获取已注册发送方的密钥对（未注册时先建档）。 */
    public KeyPair keyPairOf(String sender) {
        return registerSender(sender);
    }

    /** 获取来源链验证者的密钥对（必须已在构造器中注册）。 */
    public KeyPair validatorKeyOf(String validator) {
        KeyPair pair = validatorKeys.get(validator);
        if (pair == null) {
            throw new IllegalArgumentException("未注册的验证者: " + validator);
        }
        return pair;
    }

    /** 用发送方私钥对消息内容哈希作出真实数字签名。 */
    public String credentialOf(String sender, CrossChainMessage unsigned) {
        return Signing.sign(keyPairOf(sender).getPrivate(), unsigned.contentHash());
    }

    /** 构造一条由 sender 真实签名的合法消息。 */
    public CrossChainMessage signedMessage(String id, String sender, String payload,
                                           long fee, long gasLimit, long validUntil, int maxRetries) {
        CrossChainMessage unsigned = new CrossChainMessage(id, CHAIN_A, CHAIN_B, sender, 1,
                payload, fee, gasLimit, 0, validUntil, new RetryPolicy(maxRetries), null);
        String credential = credentialOf(sender, unsigned);
        return new CrossChainMessage(id, CHAIN_A, CHAIN_B, sender, 1,
                payload, fee, gasLimit, 0, validUntil, new RetryPolicy(maxRetries), credential);
    }

    public CrossChainMessage signedMessage(String id, String payload) {
        return signedMessage(id, "alice", payload, 10, 10_000, 1_000, 3);
    }

    /** 用指定身份的私钥重新签名一条消息（用于"凭证与发送方不一致"等场景）。 */
    public CrossChainMessage resignAs(CrossChainMessage original, String signer,
                                     String declaredSender) {
        CrossChainMessage unsigned = new CrossChainMessage(original.id(), original.sourceChain(),
                original.targetChain(), declaredSender, original.nonce(), original.payload(),
                original.fee(), original.gasLimit(), original.validFrom(), original.validUntil(),
                original.retryPolicy(), null);
        String credential = Signing.sign(keyPairOf(signer).getPrivate(), unsigned.contentHash());
        return new CrossChainMessage(unsigned.id(), unsigned.sourceChain(), unsigned.targetChain(),
                declaredSender, unsigned.nonce(), unsigned.payload(), unsigned.fee(),
                unsigned.gasLimit(), unsigned.validFrom(), unsigned.validUntil(),
                unsigned.retryPolicy(), credential);
    }

    /** 在来源链 A 上登记 outbox 并出块（带验证者真实签名），返回区块头。 */
    public BlockHeader commitOnSource(CrossChainMessage message) {
        return commitOnSource(message, VALIDATORS);
    }

    /** 在来源链 A 上登记 outbox 并出块，仅由给定验证者集合签名。 */
    public BlockHeader commitOnSource(CrossChainMessage message, List<String> signers) {
        chainA.applyNow("outbox:" + message.id(), message.contentHash());
        BlockHeader header = chainA.commitBlock();
        return signedHeader(header, signers);
    }

    /** 为一个未带签名的来源链区块头补签。 */
    public BlockHeader signedHeader(BlockHeader header, List<String> signers) {
        List<String> signatures = signers.stream()
                .map(v -> LightClient.sign(validatorKeys.get(v).getPrivate(), header))
                .toList();
        return new BlockHeader(header.chainId(), header.height(), header.parentHash(),
                header.stateRoot(), header.timestamp(), signatures);
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

    public long balanceOf(String account) {
        return chainB.get("balance:" + account).map(Long::parseLong).orElse(0L);
    }

    public long collectedFees() {
        return chainB.get("fees:collector").map(Long::parseLong).orElse(0L);
    }
}
