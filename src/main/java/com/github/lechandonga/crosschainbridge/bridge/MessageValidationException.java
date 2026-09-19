package com.github.lechandonga.crosschainbridge.bridge;

import com.github.lechandonga.crosschainbridge.model.FailureReason;

/** 发起端校验失败时抛出，携带可区分的失败原因。 */
public final class MessageValidationException extends RuntimeException {

    private final FailureReason reason;

    public MessageValidationException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
