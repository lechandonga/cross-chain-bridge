package com.github.lechandonga.crosschainbridge.bridge;

import com.github.lechandonga.crosschainbridge.model.FailureReason;

import java.util.List;

/**
 * 重组处理结果，明确区分两类情形：
 * - revoked：已提交但未最终确定，被撤销/待重新验证的消息
 * - finalizedKept：已最终确定而保留、不得回滚的消息
 * rollbackAttempted=true 时表示有人试图回滚最终区块（被拒绝）。
 */
public record ReorgResult(
        long forkHeight,
        List<String> revoked,
        List<String> finalizedKept,
        boolean rollbackAttempted,
        FailureReason failureReason) {
}
