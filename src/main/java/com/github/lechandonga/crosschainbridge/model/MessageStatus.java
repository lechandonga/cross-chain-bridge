package com.github.lechandonga.crosschainbridge.model;

/**
 * 跨链消息生命周期状态。
 * CREATED -> INCLUDED -> SUBMITTED -> VERIFIED -> EXECUTED
 * 任意失败分支：REJECTED / RETRYABLE -> FAILED_FINAL / REVOKED / EXPIRED
 */
public enum MessageStatus {
    CREATED,
    INCLUDED,
    SUBMITTED,
    VERIFIED,
    EXECUTED,
    RETRYABLE,
    REVOKED,
    REJECTED,
    FAILED_FINAL,
    EXPIRED
}
