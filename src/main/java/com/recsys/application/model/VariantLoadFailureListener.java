package com.recsys.application.model;

/**
 * Receives non-fatal variant load failures from {@link ModelRuntimeProvider#warmUp()} so the
 * request-path resolver can start its cooldown without the first live request re-paying a build
 * that is already known to fail. {@code phase} is a closed label set ({@code warmup}, {@code request}).
 */
@FunctionalInterface
public interface VariantLoadFailureListener {

    VariantLoadFailureListener NOOP = (variant, failure, phase) -> { };

    void onLoadFailure(String variant, RuntimeException failure, String phase);
}
