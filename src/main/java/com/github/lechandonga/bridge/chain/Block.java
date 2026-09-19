package com.github.lechandonga.bridge.chain;

import java.util.Map;

/** 区块：区块头 + 该区块结束时的状态快照。 */
public record Block(BlockHeader header, Map<String, String> stateSnapshot) {
}
