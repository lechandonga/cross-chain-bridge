package com.github.lechandonga.crosschainbridge.model;

/**
 * 一条跨链消息（签名前的有效载荷）。
 *
 * @param messageId       全局唯一消息标识
 * @param sourceChain     来源链
 * @param targetChain     目标链
 * @param payload         执行载荷（如 "transfer:from,to,amount"）
 * @param nonce           发送方序号，参与防重放
 * @param createdAtMillis 创建时间
 * @param validUntilMillis 有效期截止时间（绝对时间戳）
 * @param requiredGas     目标链执行所需资源单位
 * @param feeLimit        手续费/资源上限
 * @param retryPolicy     重试策略
 */
public record BridgeMessage(
        String messageId,
        ChainId sourceChain,
        ChainId targetChain,
        String payload,
        long nonce,
        long createdAtMillis,
        long validUntilMillis,
        long requiredGas,
        long feeLimit,
        RetryPolicy retryPolicy) {
}
