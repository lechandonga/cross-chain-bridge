package com.github.lechandonga.bridge;

import com.github.lechandonga.bridge.bridge.Bridge;
import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.chain.SimulatedChain;
import com.github.lechandonga.bridge.lightclient.LightClient;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.RetryPolicy;
import com.github.lechandonga.bridge.relayer.Relayer;

import java.util.List;
import java.util.Set;

/** 测试夹具：两条本地模拟链 + 轻客户端 + 桥接端点 + 中继器。 */
public class BridgeTestFixture {

    public static final String CHAIN_A = "chain-a";
    public static final String CHAIN_B = "chain-b";
    public static final List<String> VALIDATORS = List.of("val1", "val2", "val3");

    public final SimulatedChain chainA = new SimulatedChain(CHAIN_A);
    public final SimulatedChain chainB = new SimulatedChain(CHAIN_B);
    public final LightClient lightClient = new LightClient();
    public final Bridge bridge = new Bridge(chainB, lightClient);
    public final Relayer relayer = new Relayer(bridge);

    public BridgeTestFixture() {
        lightClient.registerValidatorSet(CHAIN_A, Set.copyOf(VALIDATORS), 2);
    }

    /** 构造一条已签名的合法消息。 */
    public CrossChainMessage signedMessage(String id, String sender, String payload,
                                           long fee, long gasLimit, long validUntil, int maxRetries) {
        CrossChainMessage unsigned = new CrossChainMessage(id, CHAIN_A, CHAIN_B, sender, 1,
                payload, fee, gasLimit, 0, validUntil, new RetryPolicy(maxRetries), null);
        String sig = "sig:" + sender + ":" + unsigned.contentHash();
        return new CrossChainMessage(id, CHAIN_A, CHAIN_B, sender, 1,
                payload, fee, gasLimit, 0, validUntil, new RetryPolicy(maxRetries), sig);
    }

    public CrossChainMessage signedMessage(String id, String payload) {
        return signedMessage(id, "alice", payload, 10, 10_000, 1_000, 3);
    }

    /** 在来源链 A 上登记 outbox 并出块（带验证者签名），返回区块头。 */
    public BlockHeader commitOnSource(CrossChainMessage message) {
        chainA.applyNow("outbox:" + message.id(), message.contentHash());
        // 先出块再补签名（签名依赖 header hash；header hash 不含签名列表）
        BlockHeader header = chainA.commitBlock();
        BlockHeader signed = new BlockHeader(header.chainId(), header.height(), header.parentHash(),
                header.stateRoot(), header.timestamp(),
                VALIDATORS.stream().map(v -> LightClient.sign(v, header)).toList());
        return signed;
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
