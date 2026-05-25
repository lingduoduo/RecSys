package com.recsys.saga;

public class SagaConflictException extends RuntimeException {
    private final String sagaId;
    private final int expectedVersion;
    private final int actualVersion;

    public SagaConflictException(String sagaId, int expectedVersion, int actualVersion) {
        super("optimistic lock conflict for saga " + sagaId
                + ": expected version " + expectedVersion + " but found " + actualVersion);
        this.sagaId = sagaId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String sagaId() {
        return sagaId;
    }

    public int expectedVersion() {
        return expectedVersion;
    }

    public int actualVersion() {
        return actualVersion;
    }
}
