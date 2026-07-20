package com.recsys.application.autoscaling;

/** Supplies the current {@link CapacitySignal} for the controller to act on. */
@FunctionalInterface
public interface CapacitySignalSource {
    CapacitySignal read();
}
