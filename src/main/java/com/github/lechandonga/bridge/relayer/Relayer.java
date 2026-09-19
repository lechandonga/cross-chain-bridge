package com.github.lechandonga.bridge.relayer;

import com.github.lechandonga.bridge.bridge.Bridge;
import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 中继器：在两条链之间搬运消息，支持多通道并发投递。 */
public class Relayer {

    public Relayer(Bridge bridge) {
    }

    /** 单条投递。 */
    public BridgeResult deliver(CrossChainMessage message, MerkleProof proof, BlockHeader header) {
        return null;
    }

    /** 并发投递一个通道的一批消息。 */
    public CompletableFuture<List<BridgeResult>> deliverChannelAsync(String channel, List<Delivery> deliveries) {
        return null;
    }

    /** 一次投递所需的全部材料。 */
    public record Delivery(CrossChainMessage message, MerkleProof proof, BlockHeader header) {
    }

    /** 关闭内部线程池。 */
    public void shutdown() {
    }
}
