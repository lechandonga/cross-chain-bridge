package com.github.lechandonga.crosschainbridge.bridge;

import com.github.lechandonga.crosschainbridge.crypto.MessageSigner;
import com.github.lechandonga.crosschainbridge.chain.ChainState;
import com.github.lechandonga.crosschainbridge.chain.SimulatedBlockchain;
import com.github.lechandonga.crosschainbridge.logging.DecisionLog;
import com.github.lechandonga.crosschainbridge.lightclient.LightClient;
import com.github.lechandonga.crosschainbridge.model.ChainId;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试辅助工厂：装配两条本地模拟链、端点、双向轻客户端与共享验证者集合。
 *
 * <p>每个端点都持有一个跟踪对端链的轻客户端：
 * <ul>
 *   <li>lightClientOnB：B 上的轻客户端，跟踪 A（验证 A -> B 消息）</li>
 *   <li>lightClientOnA：A 上的轻客户端，跟踪 B（验证 B -> A 消息）</li>
 * </ul>
 * 出块端点在 dispatchBatch 时把新头喂给对端轻客户端。
 */
public final class TwoChainSystem {

    private final SimulatedBlockchain chainA;
    private final SimulatedBlockchain chainB;
    private final ChainState stateA;
    private final ChainState stateB;
    private final List<KeyPair> validatorKeys;
    private final LightClient lightClientOnB;
    private final LightClient lightClientOnA;
    private final TargetMailbox mailboxA;
    private final TargetMailbox mailboxB;
    private final DecisionLog decisionLog;
    private final BridgeEndpoint endpointA;
    private final BridgeEndpoint endpointB;

    public TwoChainSystem(long quorumThreshold) {
        this(quorumThreshold, generateValidators(5));
    }

    public TwoChainSystem(long quorumThreshold, int validatorCount) {
        this(quorumThreshold, generateValidators(validatorCount));
    }

    public TwoChainSystem(long quorumThreshold, List<KeyPair> validatorKeys) {
        this.validatorKeys = List.copyOf(validatorKeys);
        this.chainA = new SimulatedBlockchain(ChainId.CHAIN_A);
        this.chainB = new SimulatedBlockchain(ChainId.CHAIN_B);
        this.stateA = new ChainState(ChainId.CHAIN_A);
        this.stateB = new ChainState(ChainId.CHAIN_B);
        this.lightClientOnB = new LightClient(this.validatorKeys, quorumThreshold);
        this.lightClientOnA = new LightClient(this.validatorKeys, quorumThreshold);
        this.mailboxA = new TargetMailbox();
        this.mailboxB = new TargetMailbox();
        this.decisionLog = new DecisionLog();
        this.endpointA = new BridgeEndpoint("endpointA", chainA, stateA, lightClientOnA,
                mailboxA, decisionLog, this.validatorKeys);
        this.endpointB = new BridgeEndpoint("endpointB", chainB, stateB, lightClientOnB,
                mailboxB, decisionLog, this.validatorKeys);
    }

    private static List<KeyPair> generateValidators(int count) {
        List<KeyPair> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(MessageSigner.generateKeyPair());
        }
        return keys;
    }

    public BridgeEndpoint endpointA() {
        return endpointA;
    }

    public BridgeEndpoint endpointB() {
        return endpointB;
    }

    public SimulatedBlockchain chainA() {
        return chainA;
    }

    public SimulatedBlockchain chainB() {
        return chainB;
    }

    public ChainState stateA() {
        return stateA;
    }

    public ChainState stateB() {
        return stateB;
    }

    public DecisionLog log() {
        return decisionLog;
    }

    public List<KeyPair> validatorKeys() {
        return validatorKeys;
    }

    public LightClient lightClientOnB() {
        return lightClientOnB;
    }

    public LightClient lightClientOnA() {
        return lightClientOnA;
    }

    public TargetMailbox mailboxA() {
        return mailboxA;
    }

    public TargetMailbox mailboxB() {
        return mailboxB;
    }

    public ChainId targetOf(ChainId source) {
        return source.counterpart();
    }
}
