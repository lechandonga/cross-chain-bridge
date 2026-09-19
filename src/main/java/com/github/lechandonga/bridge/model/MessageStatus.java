package com.github.lechandonga.bridge.model;

/** 跨链消息生命周期状态。 */
public enum MessageStatus {
    /** 已发起，尚未提交到目标链 */
    INITIATED,
    /** 已提交到目标链，等待验证 */
    SUBMITTED,
    /** 轻客户端验证通过 */
    VERIFIED,
    /** 已在目标链执行（尚未最终确定） */
    EXECUTED,
    /** 已最终确定，不可回滚 */
    FINALIZED,
    /** 因链重组被撤销（可重新验证执行） */
    REVERTED,
    /** 执行资源超限，可重试 */
    RETRYABLE,
    /** 被拒绝（携带可区分的失败原因） */
    REJECTED,
    /** 重试耗尽后的确定终态 */
    FAILED_FINAL
}
