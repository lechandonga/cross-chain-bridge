package com.github.lechandonga.crosschainbridge.chain;

import com.github.lechandonga.crosschainbridge.model.ChainId;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** 链上账务状态：账户 -> 余额；执行载荷的确定性应用与回滚。 */
public final class ChainState {

    /** 载荷应用结果，三种路径可区分。 */
    public enum ApplyOutcome {
        APPLIED,
        INSUFFICIENT_FUNDS,
        MALFORMED_PAYLOAD
    }

    private final ChainId chainId;
    private final ConcurrentHashMap<String, Long> balances = new ConcurrentHashMap<>();

    public ChainState(ChainId chainId) {
        this.chainId = chainId;
    }

    public ChainId chainId() {
        return chainId;
    }

    public long balanceOf(String account) {
        return balances.getOrDefault(account, 0L);
    }

    public Map<String, Long> snapshotBalances() {
        synchronized (balances) {
            return Map.copyOf(new TreeMap<>(balances));
        }
    }

    public void credit(String account, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative credit");
        }
        balances.merge(account, amount, Long::sum);
    }

    public void debit(String account, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative debit");
        }
        synchronized (balances) {
            long current = balanceOf(account);
            if (current < amount) {
                throw new IllegalStateException("insufficient funds for " + account);
            }
            balances.put(account, current - amount);
        }
    }

    /**
     * 在单个临界区内原子执行载荷。
     * 支持的载荷格式：{@code transfer:<from>,<to>,<amount>}。
     * 任何前置条件不满足都不写入余额（无半更新中间状态）。
     */
    public ApplyOutcome applyPayloadAtomically(String payload) {
        if (payload == null || payload.isBlank()) {
            return ApplyOutcome.MALFORMED_PAYLOAD;
        }
        String[] parts = payload.split(":");
        if (parts.length != 2 || !"transfer".equals(parts[0])) {
            return ApplyOutcome.MALFORMED_PAYLOAD;
        }
        String[] args = parts[1].split(",");
        if (args.length != 3) {
            return ApplyOutcome.MALFORMED_PAYLOAD;
        }
        final String from = args[0].trim();
        final String to = args[1].trim();
        final long amount;
        try {
            amount = Long.parseLong(args[2].trim());
        } catch (NumberFormatException e) {
            return ApplyOutcome.MALFORMED_PAYLOAD;
        }
        if (from.isEmpty() || to.isEmpty() || amount <= 0) {
            return ApplyOutcome.MALFORMED_PAYLOAD;
        }
        synchronized (balances) {
            long fromBalance = balanceOf(from);
            if (fromBalance < amount) {
                return ApplyOutcome.INSUFFICIENT_FUNDS;
            }
            // 全部校验通过后一次性落账
            balances.put(from, fromBalance - amount);
            balances.merge(to, amount, Long::sum);
            return ApplyOutcome.APPLIED;
        }
    }
}
