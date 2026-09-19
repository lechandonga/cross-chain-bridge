package com.github.lechandonga.bridge.bridge;

import com.github.lechandonga.bridge.model.MessageStatus;
import com.github.lechandonga.bridge.model.RejectionReason;

/** 桥接处理结果：状态 + 可区分原因 + 判定依据说明。 */
public record BridgeResult(String messageId, MessageStatus status, RejectionReason reason, String detail) {

    public boolean success() {
        return status == MessageStatus.EXECUTED || status == MessageStatus.FINALIZED;
    }

    public static BridgeResult of(String id, MessageStatus status, String detail) {
        return new BridgeResult(id, status, null, detail);
    }

    public static BridgeResult rejected(String id, RejectionReason reason, String detail) {
        return new BridgeResult(id, MessageStatus.REJECTED, reason, detail);
    }
}
