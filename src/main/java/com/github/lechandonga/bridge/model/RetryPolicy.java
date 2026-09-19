package com.github.lechandonga.bridge.model;

/** 重试策略：最大重试次数。 */
public record RetryPolicy(int maxRetries) {
    public static RetryPolicy none() { return new RetryPolicy(0); }
}
