package com.recsys.microservice;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class GatewayRateLimiter {
    private static final GatewayRateLimiter DISABLED = new GatewayRateLimiter(Map.of());

    private final Map<String, TokenBucket> buckets;

    private GatewayRateLimiter(Map<String, TokenBucket> buckets) {
        this.buckets = Map.copyOf(buckets);
    }

    static GatewayRateLimiter disabled() {
        return DISABLED;
    }

    static GatewayRateLimiter fromEnvironment(List<MicroserviceRoute> routes) {
        return fromEnvironment(routes, System::getenv, System::nanoTime);
    }

    static GatewayRateLimiter fromEnvironment(List<MicroserviceRoute> routes,
                                              EnvReader env,
                                              LongSupplier tickerNanos) {
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(tickerNanos, "tickerNanos");

        double defaultRate = readDouble(env, "GATEWAY_RATE_LIMIT_RPS", 0.0);
        int defaultBurst = readInt(env, "GATEWAY_RATE_LIMIT_BURST", 0);
        Map<String, TokenBucket> buckets = new HashMap<>();

        for (MicroserviceRoute route : routes) {
            String suffix = route.name().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            double rate = readDouble(env, "GATEWAY_RATE_LIMIT_" + suffix + "_RPS", defaultRate);
            int burst = readInt(env, "GATEWAY_RATE_LIMIT_" + suffix + "_BURST", defaultBurst);
            if (rate > 0.0 && burst > 0) {
                buckets.put(route.name(), new TokenBucket(rate, burst, tickerNanos));
            }
        }

        return buckets.isEmpty() ? disabled() : new GatewayRateLimiter(buckets);
    }

    Decision tryAcquire(String routeName) {
        TokenBucket bucket = buckets.get(routeName);
        if (bucket == null) {
            return Decision.unlimited();
        }
        return bucket.tryAcquire();
    }

    boolean isEnabled() {
        return !buckets.isEmpty();
    }

    private static double readDouble(EnvReader env, String name, double defaultValue) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid decimal: " + raw);
        }
    }

    private static int readInt(EnvReader env, String name, int defaultValue) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid integer: " + raw);
        }
    }

    @FunctionalInterface
    interface EnvReader {
        String get(String name);
    }

    record Decision(boolean allowed,
                    int limit,
                    int remaining,
                    Duration retryAfter) {
        static Decision unlimited() {
            return new Decision(true, 0, 0, Duration.ZERO);
        }
    }

    private static final class TokenBucket {
        private final double refillPerNano;
        private final int burst;
        private final LongSupplier tickerNanos;
        private double tokens;
        private long lastRefillNanos;

        private TokenBucket(double ratePerSecond, int burst, LongSupplier tickerNanos) {
            this.refillPerNano = ratePerSecond / TimeUnit.SECONDS.toNanos(1);
            this.burst = burst;
            this.tickerNanos = tickerNanos;
            this.tokens = burst;
            this.lastRefillNanos = tickerNanos.getAsLong();
        }

        synchronized Decision tryAcquire() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Decision(true, burst, (int) Math.floor(tokens), Duration.ZERO);
            }
            long waitNanos = (long) Math.ceil((1.0 - tokens) / refillPerNano);
            return new Decision(false, burst, 0, Duration.ofNanos(Math.max(0L, waitNanos)));
        }

        private void refill() {
            long now = tickerNanos.getAsLong();
            long elapsed = Math.max(0L, now - lastRefillNanos);
            if (elapsed == 0L) {
                return;
            }
            tokens = Math.min(burst, tokens + elapsed * refillPerNano);
            lastRefillNanos = now;
        }
    }
}
