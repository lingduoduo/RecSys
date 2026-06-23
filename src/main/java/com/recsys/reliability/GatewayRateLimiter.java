package com.recsys.reliability;
import com.recsys.config.EnvVars;
import com.recsys.application.gateway.MicroserviceRoute;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class GatewayRateLimiter {
    private static final GatewayRateLimiter DISABLED = new GatewayRateLimiter(Map.of());

    private final Map<String, TokenBucket> buckets;

    private GatewayRateLimiter(Map<String, TokenBucket> buckets) {
        this.buckets = Map.copyOf(buckets);
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
        Map<String, TokenBucket> buckets = new HashMap<>();

        for (MicroserviceRoute route : routes) {
            String suffix = route.name().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            double rate = EnvVars.readDouble(env, "GATEWAY_RATE_LIMIT_" + suffix + "_RPS", defaultRate);
            int burst = EnvVars.readInt(env, "GATEWAY_RATE_LIMIT_" + suffix + "_BURST", defaultBurst);
            if (rate > 0.0 && burst > 0) {
                buckets.put(route.name(), new TokenBucket(rate, burst, tickerNanos));
            }
        }

        return buckets.isEmpty() ? disabled() : new GatewayRateLimiter(buckets);
    }

    public TokenBucket.Decision tryAcquire(String routeName) {
        TokenBucket bucket = buckets.get(routeName);
        if (bucket == null) {
            return TokenBucket.Decision.unlimited();
        }
        return bucket.tryAcquire();
    }

    public boolean isEnabled() {
        return !buckets.isEmpty();
    }
}
