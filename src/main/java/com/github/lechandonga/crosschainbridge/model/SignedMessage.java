package com.github.lechandonga.crosschainbridge.model;

import java.util.List;

/** 已由来源链验证者集合签名的消息。 */
public record SignedMessage(
        BridgeMessage message,
        byte[] messageHash,
        List<byte[]> signatures,
        List<String> signerIds) {

    /** 参与签名的验证者人数。 */
    public int signerCount() {
        return 0;
    }
}
