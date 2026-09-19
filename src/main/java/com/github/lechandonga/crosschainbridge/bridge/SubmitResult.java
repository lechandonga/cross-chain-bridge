package com.github.lechandonga.crosschainbridge.bridge;

import com.github.lechandonga.crosschainbridge.model.FailureReason;
import com.github.lechandonga.crosschainbridge.model.MessageStatus;

/** 目标端提交/重试的确定性结果。 */
public record SubmitResult(
        String messageId,
        boolean accepted,
        MessageStatus status,
        FailureReason failureReason,
        int retryCount,
        String detail) {

    public static SubmitResult accepted(String messageId, MessageStatus status) {
        return new SubmitResult(messageId, true, status, FailureReason.NONE, 0, "accepted");
    }

    public static SubmitResult rejected(String messageId, FailureReason reason, String detail) {
        return new SubmitResult(messageId, false, null, reason, 0, detail);
    }
}
