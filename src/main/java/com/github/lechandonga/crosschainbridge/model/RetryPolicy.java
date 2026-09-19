package com.github.lechandonga.crosschainbridge.model;

/** 重试策略：最大重试次数与重试时间窗口（毫秒）。 */
public record RetryPolicy(int maxRetries, long retryWindowMillis) {
    public static final RetryPolicy DEFAULT = new RetryPolicy(3, 60_000L);
}
