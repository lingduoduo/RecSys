package com.recsys.ratelimit;
import com.recsys.config.EnvVars;
import com.recsys.application.gateway.MicroserviceRoute;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Per-(route, principal) token-bucket rate limiter. Each principal gets the route's
 * configured rate/burst independently; buckets live in a bounded Caffeine cache so a
 * flood of distinct principals cannot exhaust memory.
 */
public final class GatewayRateLimiter {
    private static final GatewayRateLimiter DISABLED =
            new GatewayRateLimiter(Map.of(), () -> 0L, 100_000);

    private record RouteLimit(double rate, int burst) {
    }

    private final Map<String, RouteLimit> routeLimits;
    private final LongSupplier tickerNanos;
    private final Cache<String, TokenBucket> buckets;

    private GatewayRateLimiter(Map<String, RouteLimit> routeLimits, LongSupplier tickerNanos, long maxPrincipals) {
        this.routeLimits = Map.copyOf(routeLimits);
        this.tickerNanos = tickerNanos;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(maxPrincipals)
                .expireAfterAccess(60, TimeUnit.MINUTES)
                .build();
    }

    public static GatewayRateLimiter disabled() {
        return DISABLED;
    }

    public static GatewayRateLimiter fromEnvironment(List<MicroserviceRoute> routes) {
        return fromEnvironment(routes, System::getenv, System::nanoTime);
    }

    public static GatewayRateLimiter fromEnvironment(List<MicroserviceRoute> routes,
                                              EnvVars.EnvReader env,
                                              LongSupplier tickerNanos) {
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(tickerNanos, "tickerNanos");

        double defaultRate = EnvVars.readDouble(env, "GATEWAY_RATE_LIMIT_RPS", 0.0);
        int defaultBurst = EnvVars.readInt(env, "GATEWAY_RATE_LIMIT_BURST", 0);
        Map<String, RouteLimit> routeLimits = new HashMap<>();

        for (MicroserviceRoute route : routes) {
            String suffix = route.name().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            double rate = EnvVars.readDouble(env, "GATEWAY_RATE_LIMIT_" + suffix + "_RPS", defaultRate);
            int burst = EnvVars.readInt(env, "GATEWAY_RATE_LIMIT_" + suffix + "_BURST", defaultBurst);
            if (rate > 0.0 && burst > 0) {
                routeLimits.put(route.name(), new RouteLimit(rate, burst));
            }
        }

        if (routeLimits.isEmpty()) {
            return disabled();
        }
        long maxPrincipals = EnvVars.readInt(env, "GATEWAY_RL_MAX_PRINCIPALS", 100_000);
        return new GatewayRateLimiter(routeLimits, tickerNanos, maxPrincipals);
    }

    /**
     * Consume one token for (routeName, principalKey). Unconfigured routes are unlimited.
     * Each (route, principal) pair gets its own bucket using the route's configured rate/burst.
     */
    public TokenBucket.Decision tryAcquire(String routeName, String principalKey) {
        RouteLimit limit = routeLimits.get(routeName);
        if (limit == null) {
            return TokenBucket.Decision.unlimited();
        }
        TokenBucket bucket = buckets.get(routeName + "|" + principalKey,
                k -> new TokenBucket(limit.rate(), limit.burst(), tickerNanos));
        return bucket.tryAcquire();
    }

    public boolean isEnabled() {
        return !routeLimits.isEmpty();
    }
}
