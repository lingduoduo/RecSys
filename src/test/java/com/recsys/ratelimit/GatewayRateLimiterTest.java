package com.recsys.ratelimit;
import com.recsys.application.gateway.MicroserviceRoute;
import com.recsys.ratelimit.GatewayRateLimiter;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayRateLimiterTest {

    @Test
    void disabledWhenNoLimitsConfigured() {
        GatewayRateLimiter limiter = GatewayRateLimiter.fromEnvironment(
                List.of(route("catalog")),
                Map.<String, String>of()::get,
                System::nanoTime
        );

        assertFalse(limiter.isEnabled());
        assertTrue(limiter.tryAcquire("catalog", "p1").allowed());
    }

    @Test
    void consumesBurstThenRateLimitsUntilRefill() {
        AtomicLong now = new AtomicLong(0L);
        GatewayRateLimiter limiter = GatewayRateLimiter.fromEnvironment(
                List.of(route("model")),
                Map.of(
                        "GATEWAY_RATE_LIMIT_RPS", "2",
                        "GATEWAY_RATE_LIMIT_BURST", "2"
                )::get,
                now::get
        );

        assertTrue(limiter.tryAcquire("model", "p1").allowed());
        assertTrue(limiter.tryAcquire("model", "p1").allowed());
        assertFalse(limiter.tryAcquire("model", "p1").allowed());

        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));

        assertTrue(limiter.tryAcquire("model", "p1").allowed());
    }

    @Test
    void routeSpecificLimitOverridesDefault() {
        AtomicLong now = new AtomicLong(0L);
        GatewayRateLimiter limiter = GatewayRateLimiter.fromEnvironment(
                List.of(route("catalog"), route("online")),
                Map.of(
                        "GATEWAY_RATE_LIMIT_RPS", "10",
                        "GATEWAY_RATE_LIMIT_BURST", "2",
                        "GATEWAY_RATE_LIMIT_ONLINE_RPS", "1",
                        "GATEWAY_RATE_LIMIT_ONLINE_BURST", "1"
                )::get,
                now::get
        );

        assertTrue(limiter.tryAcquire("catalog", "p1").allowed());
        assertTrue(limiter.tryAcquire("catalog", "p1").allowed());
        assertFalse(limiter.tryAcquire("catalog", "p1").allowed());

        assertTrue(limiter.tryAcquire("online", "p1").allowed());
        assertFalse(limiter.tryAcquire("online", "p1").allowed());
    }

    @Test
    void isolatesBucketsPerPrincipalOnSameRoute() {
        AtomicLong now = new AtomicLong(0L);
        GatewayRateLimiter limiter = GatewayRateLimiter.fromEnvironment(
                List.of(route("model")),
                Map.of("GATEWAY_RATE_LIMIT_RPS", "1", "GATEWAY_RATE_LIMIT_BURST", "1")::get,
                now::get);

        assertTrue(limiter.tryAcquire("model", "user:a").allowed());
        assertFalse(limiter.tryAcquire("model", "user:a").allowed());   // A exhausted
        assertTrue(limiter.tryAcquire("model", "user:b").allowed());    // B independent
    }

    @Test
    void samePrincipalIndependentAcrossRoutes() {
        AtomicLong now = new AtomicLong(0L);
        GatewayRateLimiter limiter = GatewayRateLimiter.fromEnvironment(
                List.of(route("model"), route("catalog")),
                Map.of("GATEWAY_RATE_LIMIT_RPS", "1", "GATEWAY_RATE_LIMIT_BURST", "1")::get,
                now::get);

        assertTrue(limiter.tryAcquire("model", "user:a").allowed());
        assertFalse(limiter.tryAcquire("model", "user:a").allowed());    // model exhausted for A
        assertTrue(limiter.tryAcquire("catalog", "user:a").allowed());   // catalog independent for A
    }

    private static MicroserviceRoute route(String name) {
        return new MicroserviceRoute(
                name,
                "/api/" + name,
                name.toUpperCase() + "_SERVICE_URL",
                URI.create("http://" + name + ":8080"),
                "/health"
        );
    }
}
