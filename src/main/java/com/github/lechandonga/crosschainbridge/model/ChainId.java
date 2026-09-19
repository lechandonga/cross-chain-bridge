package com.github.lechandonga.crosschainbridge.model;

/** 模拟链标识。系统中只有两条本地链 A / B。 */
public enum ChainId {
    CHAIN_A,
    CHAIN_B;

    /** 返回对端链标识。 */
    public ChainId counterpart() {
        return this == CHAIN_A ? CHAIN_B : CHAIN_A;
    }
}
