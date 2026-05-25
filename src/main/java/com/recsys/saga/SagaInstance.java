package com.recsys.saga;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SagaInstance {
    private final String sagaId;
    private final String sagaType;
    private final String correlationId;
    private final String payloadJson;
    private final Instant createdAt;
    private Instant updatedAt;
    private SagaStatus status;
    private String currentStep;
    private String failureReason;
    private final List<String> completedSteps = new ArrayList<>();
    private final List<String> compensatedSteps = new ArrayList<>();
    private final List<String> triedSteps = new ArrayList<>();
    private final List<String> confirmedSteps = new ArrayList<>();
    private final List<String> cancelledSteps = new ArrayList<>();
    private int version = 0;

    public SagaInstance(String sagaId, String sagaType, String correlationId, String payloadJson, Instant now) {
        this.sagaId = requireText(sagaId, "sagaId");
        this.sagaType = requireText(sagaType, "sagaType");
        this.correlationId = requireText(correlationId, "correlationId");
        this.payloadJson = payloadJson == null ? "{}" : payloadJson;
        this.createdAt = Objects.requireNonNull(now, "now is required");
        this.updatedAt = now;
        this.status = SagaStatus.STARTED;
    }

    public String sagaId() {
        return sagaId;
    }

    public String sagaType() {
        return sagaType;
    }

    public String correlationId() {
        return correlationId;
    }

    public String payloadJson() {
        return payloadJson;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public SagaStatus status() {
        return status;
    }

    public String currentStep() {
        return currentStep;
    }

    public String failureReason() {
        return failureReason;
    }

    public List<String> completedSteps() {
        return Collections.unmodifiableList(completedSteps);
    }

    public List<String> compensatedSteps() {
        return Collections.unmodifiableList(compensatedSteps);
    }

    public List<String> triedSteps() {
        return Collections.unmodifiableList(triedSteps);
    }

    public List<String> confirmedSteps() {
        return Collections.unmodifiableList(confirmedSteps);
    }

    public List<String> cancelledSteps() {
        return Collections.unmodifiableList(cancelledSteps);
    }

    void mark(SagaStatus status, String currentStep, Instant now) {
        this.status = Objects.requireNonNull(status, "status is required");
        this.currentStep = currentStep;
        this.updatedAt = Objects.requireNonNull(now, "now is required");
    }

    void markStepCompleted(String step, Instant now) {
        if (!completedSteps.contains(step)) {
            completedSteps.add(step);
        }
        mark(SagaStatus.STEP_COMPLETED, step, now);
    }

    void markCompensated(String step, Instant now) {
        if (!compensatedSteps.contains(step)) {
            compensatedSteps.add(step);
        }
        mark(SagaStatus.COMPENSATED, step, now);
    }

    void markTried(String step, Instant now) {
        if (!triedSteps.contains(step)) {
            triedSteps.add(step);
        }
        mark(SagaStatus.TRIED, step, now);
    }

    void markConfirmed(String step, Instant now) {
        if (!confirmedSteps.contains(step)) {
            confirmedSteps.add(step);
        }
        mark(SagaStatus.CONFIRMED, step, now);
    }

    void markCancelled(String step, Instant now) {
        if (!cancelledSteps.contains(step)) {
            cancelledSteps.add(step);
        }
        mark(SagaStatus.CANCELLED, step, now);
    }

    int version() {
        return version;
    }

    void setVersion(int version) {
        this.version = version;
    }

    void fail(String reason, Instant now) {
        this.failureReason = reason == null || reason.isBlank() ? "unknown" : reason;
        mark(SagaStatus.FAILED, currentStep, now);
    }

    public SagaInstance copy() {
        SagaInstance copy = new SagaInstance(sagaId, sagaType, correlationId, payloadJson, createdAt);
        copy.updatedAt = updatedAt;
        copy.status = status;
        copy.currentStep = currentStep;
        copy.failureReason = failureReason;
        copy.completedSteps.addAll(completedSteps);
        copy.compensatedSteps.addAll(compensatedSteps);
        copy.triedSteps.addAll(triedSteps);
        copy.confirmedSteps.addAll(confirmedSteps);
        copy.cancelledSteps.addAll(cancelledSteps);
        copy.version = version;
        return copy;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
