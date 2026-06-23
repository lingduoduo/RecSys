package com.recsys.reliability;
import com.recsys.microservice.MicroserviceRoute;
import com.recsys.reliability.GatewayRateLimiter;

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
        assertTrue(limiter.tryAcquire("catalog").allowed());
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

        assertTrue(limiter.tryAcquire("model").allowed());
        assertTrue(limiter.tryAcquire("model").allowed());
        assertFalse(limiter.tryAcquire("model").allowed());

        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));

        assertTrue(limiter.tryAcquire("model").allowed());
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

        assertTrue(limiter.tryAcquire("catalog").allowed());
        assertTrue(limiter.tryAcquire("catalog").allowed());
        assertFalse(limiter.tryAcquire("catalog").allowed());

        assertTrue(limiter.tryAcquire("online").allowed());
        assertFalse(limiter.tryAcquire("online").allowed());
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
