package com.recsys.application.outbox;

import java.time.Instant;
import java.util.Objects;

public record DeliveryReceipt(Instant acknowledgedAt) {
    public DeliveryReceipt { Objects.requireNonNull(acknowledgedAt, "acknowledgedAt"); }
}
