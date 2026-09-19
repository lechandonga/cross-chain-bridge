package com.github.lechandonga.bridge.bridge;

import com.github.lechandonga.bridge.chain.BlockHeader;
import com.github.lechandonga.bridge.chain.SimulatedChain;
import com.github.lechandonga.bridge.lightclient.LightClient;
import com.github.lechandonga.bridge.lightclient.MerkleProof;
import com.github.lechandonga.bridge.model.CrossChainMessage;
import com.github.lechandonga.bridge.model.MessageStatus;

import java.util.Optional;

/** 部署在目标链上的桥接端点：提交、验证、执行、重放防护与重试。 */
public class Bridge {

    public Bridge(SimulatedChain targetChain, LightClient lightClient) {
    }

    /**
     * 提交并尝试执行一条跨链消息（同步、可重入安全）。
     * 校验字段、链标识、有效期、签名、轻客户端证明与重放防护后执行。
     */
    public synchronized BridgeResult submit(CrossChainMessage message, MerkleProof proof, BlockHeader sourceHeader) {
        return null;
    }

    /** 重试处于 RETRYABLE 状态的消息。 */
    public synchronized BridgeResult retry(String messageId) {
        return null;
    }

    /** 查询消息当前状态。 */
    public Optional<MessageStatus> statusOf(String messageId) {
        return Optional.empty();
    }

    /** 处理目标链重组：撤销未最终确定的执行结果。 */
    public synchronized void onReorg(long revertedDepth) {
    }
}
