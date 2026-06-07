package com.recsys.service.retrieval;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class ChannelHealthMonitor {

    private static final int  DEFAULT_FAILURE_THRESHOLD = 3;
    private static final long DEFAULT_BASE_BACKOFF_MS   = 5_000L;
    private static final long DEFAULT_MAX_BACKOFF_MS    = 60_000L;

    private final int failureThreshold;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, ChannelState> states = new ConcurrentHashMap<>();

    public ChannelHealthMonitor() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_BASE_BACKOFF_MS, DEFAULT_MAX_BACKOFF_MS,
                System::currentTimeMillis);
    }

    public ChannelHealthMonitor(int failureThreshold, long baseBackoffMs, long maxBackoffMs,
                                LongSupplier clock) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        if (baseBackoffMs < 1)    throw new IllegalArgumentException("baseBackoffMs must be >= 1");
        if (maxBackoffMs < baseBackoffMs)
            throw new IllegalArgumentException(
                "maxBackoffMs (" + maxBackoffMs + ") must be >= baseBackoffMs (" + baseBackoffMs + ")");
        this.failureThreshold = failureThreshold;
        this.baseBackoffMs    = baseBackoffMs;
        this.maxBackoffMs     = maxBackoffMs;
        this.clock            = Objects.requireNonNull(clock, "clock");
    }

    public boolean isAvailable(String channelName) {
        Objects.requireNonNull(channelName, "channelName");
        ChannelState state = states.get(channelName);
        if (state == null) return true;
        return state.backoffUntilMs() < 0 || clock.getAsLong() >= state.backoffUntilMs();
    }

    public void recordSuccess(String channelName) {
        Objects.requireNonNull(channelName, "channelName");
        states.put(channelName, ChannelState.HEALTHY);
    }

    public void recordFailure(String channelName) {
        Objects.requireNonNull(channelName, "channelName");
        states.compute(channelName, (name, existing) -> {
            int failures = (existing == null ? 0 : existing.consecutiveFailures()) + 1;
            if (failures < failureThreshold) {
                return new ChannelState(failures, -1L);
            }
            // Exponential backoff: base * 2^(failures - threshold), capped at max
            int exponent = failures - failureThreshold;
            long backoff = Math.min(maxBackoffMs,
                    baseBackoffMs * (1L << Math.min(exponent, 30)));
            return new ChannelState(failures, clock.getAsLong() + backoff);
        });
    }

    public Map<String, ChannelState> snapshot() {
        return Map.copyOf(states);
    }

    public record ChannelState(int consecutiveFailures, long backoffUntilMs) {
        static final ChannelState HEALTHY = new ChannelState(0, -1L);
    }
}
