package com.recsys.application.saga;
import com.recsys.domain.saga.SagaStep;

import java.util.List;
import java.util.Objects;

public record SagaDefinition(String name, List<SagaStep> steps) {
    public SagaDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("saga name is required");
        }
        Objects.requireNonNull(steps, "steps are required");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("saga requires at least one step");
        }
        steps = List.copyOf(steps);
    }
}
