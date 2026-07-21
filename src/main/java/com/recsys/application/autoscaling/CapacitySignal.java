package com.recsys.application.autoscaling;

/** A capacity reading: current utilization (>= 0) and whether the system is overloaded (surge). */
public record CapacitySignal(double utilization, boolean surge) {}
