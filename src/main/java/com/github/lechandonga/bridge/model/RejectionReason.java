package com.github.lechandonga.bridge.model;

/** 消息被拒绝/失败的可区分原因。 */
public enum RejectionReason {
    /** 必填字段缺失 */
    MISSING_FIELD,
    /** 目标链标识不匹配 */
    CHAIN_ID_MISMATCH,
    /** 超出有效期 */
    EXPIRED,
    /** 时间窗口尚未生效 */
    NOT_YET_VALID,
    /** 签名无效 */
    INVALID_SIGNATURE,
    /** 凭证无法解析或不是合法的签名数据（被篡改/格式损坏） */
    CREDENTIAL_MALFORMED,
    /** 凭证有效，但与声明的发送方身份不绑定（由他人密钥签发） */
    CREDENTIAL_SENDER_MISMATCH,
    /** 发送方身份未在桥接端点登记 */
    UNREGISTERED_SENDER,
    /** 授权凭证有效，但不属于注册验证者集合 */
    AUTHORIZER_NOT_AUTHORIZED,
    /** 同一个授权方被重复计入（重复签名不允许凑门槛） */
    DUPLICATE_AUTHORIZER,
    /** 来源区块头已被重组取代，不在当前正统链上 */
    SOURCE_HEADER_SUPERSEDED,
    /** 同一处理路径上的重入调用 */
    REENTRANT_CALL,
    /** 证明无法覆盖目标消息 */
    PROOF_NOT_COVERING,
    /** 区块头与状态根不一致 */
    STATE_ROOT_MISMATCH,
    /** 验证者集合不足（签名数低于阈值） */
    INSUFFICIENT_VALIDATORS,
    /** 重复投递（重放） */
    REPLAY,
    /** 消息标识碰撞（同 ID 不同内容） */
    ID_COLLISION,
    /** 执行消耗超过上限 */
    GAS_EXCEEDED,
    /** 重试次数耗尽 */
    RETRIES_EXHAUSTED,
    /** 重组深度触及已最终确定区块 */
    FINALITY_VIOLATION
}
