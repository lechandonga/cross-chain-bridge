package com.github.lechandonga.crosschainbridge.chain;

/** 试图重组/回滚已最终确定区块时抛出，属于必须被拒绝的操作。 */
public final class FinalizedRollbackException extends RuntimeException {

    private final long finalizedHeight;
    private final long requestedForkHeight;

    public FinalizedRollbackException(long finalizedHeight, long requestedForkHeight) {
        super("cannot reorg at height " + requestedForkHeight
                + ": blocks up to " + finalizedHeight + " are finalized");
        this.finalizedHeight = finalizedHeight;
        this.requestedForkHeight = requestedForkHeight;
    }

    public long finalizedHeight() {
        return finalizedHeight;
    }

    public long requestedForkHeight() {
        return requestedForkHeight;
    }
}
