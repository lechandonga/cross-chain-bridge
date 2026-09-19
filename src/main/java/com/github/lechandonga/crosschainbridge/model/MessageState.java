package com.github.lechandonga.crosschainbridge.model;

/** 端点上跟踪的消息状态记录（可变状态机节点）。 */
public final class MessageState {
    private final String messageId;
    private volatile MessageStatus status;
    private volatile FailureReason failureReason;
    private volatile int retryCount;
    private volatile long updatedAtMillis;
    private volatile long blockHeight = -1L;
    private volatile boolean finalized;

    public MessageState(String messageId) {
        this.messageId = messageId;
        this.status = MessageStatus.CREATED;
        this.failureReason = FailureReason.NONE;
    }

    public String messageId() { return messageId; }
    public MessageStatus status() { return status; }
    public FailureReason failureReason() { return failureReason; }
    public int retryCount() { return retryCount; }
    public long updatedAtMillis() { return updatedAtMillis; }
    public long blockHeight() { return blockHeight; }
    public boolean finalized() { return finalized; }

    public void transitionTo(MessageStatus next, FailureReason reason, long now) {
        this.status = next;
        this.failureReason = reason == null ? FailureReason.NONE : reason;
        this.updatedAtMillis = now;
    }

    public void setBlockHeight(long height) { this.blockHeight = height; }
    public void setFinalized(boolean f) { this.finalized = f; }
    public void incrementRetry() { this.retryCount++; }
}
