package com.github.lechandonga.bridge.relayer;

import com.github.lechandonga.bridge.bridge.Bridge;
import com.github.lechandonga.bridge.bridge.BridgeResult;
import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * 中继器：在两条链之间搬运消息，支持多通道并发投递。
 * 桥接端点本身是同步原子的，因此并发投递不会产生半更新状态。
 */
public class Relayer {

    private static final Logger LOG = Logger.getLogger(Relayer.class.getName());

    private final Bridge bridge;
    private final ExecutorService executor;

    public Relayer(Bridge bridge) {
        this(bridge, Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2)));
    }

    public Relayer(Bridge bridge, ExecutorService executor) {
        this.bridge = bridge;
        this.executor = executor;
    }

    /** 单条投递。 */
    public BridgeResult deliver(CrossChainMessage message, MerkleProof proof, BlockHeader header) {
        BridgeResult result = bridge.submit(message, proof, header);
        LOG.info("通道投递 msg=" + message.id() + " 结果=" + result.status()
                + (result.reason() != null ? " 原因=" + result.reason() : ""));
        return result;
    }

    /** 并发投递一个通道的一批消息。 */
    public CompletableFuture<List<BridgeResult>> deliverChannelAsync(String channel, List<Delivery> deliveries) {
        return CompletableFuture.supplyAsync(() -> {
            LOG.info("通道=" + channel + " 开始投递 " + deliveries.size() + " 条消息");
            return deliveries.stream()
                    .map(d -> deliver(d.message(), d.proof(), d.header()))
                    .toList();
        }, executor);
    }

    /** 一次投递所需的全部材料。 */
    public record Delivery(CrossChainMessage message, MerkleProof proof, BlockHeader header) {
    }

    /** 关闭内部线程池。 */
    public void shutdown() {
        executor.shutdown();
    }
}
