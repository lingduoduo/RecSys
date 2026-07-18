package com.recsys.application.consistency;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConsistencyToken(UUID eventId, int userId, Instant issuedAt, Instant expiresAt) {
    public ConsistencyToken {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (userId < 0) throw new IllegalArgumentException("userId cannot be negative");
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("expiresAt must follow issuedAt");
    }
}
