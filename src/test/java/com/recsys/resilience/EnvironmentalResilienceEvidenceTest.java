package com.recsys.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.application.retrieval.multichannel.RecallDegradationMetrics;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.loadshed.OnlineLoadShedder;
import com.recsys.ratelimit.RedisRateLimiter;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bounded environmental probe that writes the machine-readable measurements consumed by the
 * scheduled resilience evidence summarizer. It uses deterministic invariants; elapsed time is
 * observational only.
 */
@Tag("resilience-evidence")
class EnvironmentalResilienceEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(15)
    void writesTruthfulBoundedMeasurements() throws Exception {
        long startedNanos = System.nanoTime();

        OnlineLoadShedder admission = new OnlineLoadShedder(2, 1.0);
        int offered = 3;
        int accepted = 0;
        for (int i = 0; i < offered; i++) {
            if (admission.tryAcquire()) accepted++;
        }
        int admissionRejected = (int) admission.snapshot().rejectedRequests();
        for (int i = 0; i < accepted; i++) admission.release();

        long bulkheadRejected = exerciseBulkheadRejection();

        RecallDegradationMetrics degradation = new RecallDegradationMetrics();
        degradation.recordTotal();
        degradation.recordTotal();
        degradation.recordDegradedRequest();
        degradation.record("probe", RecallDegradationMetrics.Reason.TIMEOUT);
        RecallDegradationMetrics.Snapshot degradationSnapshot = degradation.snapshot();

        AtomicLong clock = new AtomicLong();
        CircuitBreaker breaker = new CircuitBreaker(1, 100, clock::get);
        CircuitBreaker.Permit timedPermit = breaker.tryAcquirePermit();
        int timeouts = 0;
        try {
            new CompletableFuture<>().get(10, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expected) {
            timeouts = 1;
            breaker.recordFailure(timedPermit);
        }
        clock.set(100);
        breaker.recordSuccess(breaker.tryAcquirePermit());
        boolean recovered = breaker.state() == CircuitBreaker.State.CLOSED;

        RedisBoundary redis = exerciseRedisBoundary();

        OnlineLoadShedder drain = new OnlineLoadShedder(1, 1.0);
        assertThat(drain.tryAcquire()).isTrue();
        drain.markShuttingDown();
        drain.release();
        OnlineLoadShedder.Snapshot drained = drain.snapshot();
        boolean drainCompleted = drained.shuttingDown() && drained.inFlightRequests() == 0;

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        Map<String, Object> measurements = new LinkedHashMap<>();
        measurements.put("concurrency", Map.of("offered", offered, "accepted", accepted));
        measurements.put("rejections", Map.of(
                "admission", admissionRejected, "bulkhead", bulkheadRejected));
        measurements.put("degradation", Map.of(
                "total", degradationSnapshot.totalRecalls(),
                "degraded", degradationSnapshot.degradedRecalls(),
                "ratio", degradationSnapshot.degradedRatio()));
        measurements.put("timeoutRecovery", Map.of("timeouts", timeouts, "recovered", recovered));
        measurements.put("redisBoundary", Map.of(
                "limit", redis.limit(), "attempted", redis.attempted(),
                "allowed", redis.allowed(), "rejected", redis.rejected()));
        measurements.put("gracefulDrain", Map.of(
                "completed", drainCompleted, "inFlightAfterDrain", drained.inFlightRequests()));
        measurements.put("performance", Map.of("elapsedMillis", elapsedMillis));

        Map<String, Boolean> invariants = new LinkedHashMap<>();
        invariants.put("admissionBounded", accepted == 2 && admissionRejected == 1);
        invariants.put("bulkheadRejected", bulkheadRejected == 1);
        invariants.put("degradationMeasured",
                degradationSnapshot.totalRecalls() == 2
                        && degradationSnapshot.degradedRecalls() == 1
                        && degradationSnapshot.degradedRatio() == 0.5);
        invariants.put("timeoutRecovered", timeouts == 1 && recovered);
        invariants.put("redisBoundaryEnforced",
                redis.allowed() == redis.limit() && redis.rejected() == 1);
        invariants.put("gracefulDrainCompleted", drainCompleted);
        assertThat(invariants).allSatisfy((name, passed) ->
                assertThat(passed).as(name).isTrue());

        String suite = System.getProperty("resilience.evidence.suite", "local");
        Path output = Path.of(System.getProperty(
                "resilience.evidence.output",
                "target/resilience-measurements-" + suite + ".json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "schemaVersion", 1,
                "suite", suite,
                "measurements", measurements,
                "invariants", invariants));
    }

    private static long exerciseBulkheadRejection() throws Exception {
        WorkerBulkhead bulkhead = new WorkerBulkhead("evidence", 1, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<Void> active = bulkhead.submit(() -> {
                running.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("bulkhead probe release deadline exceeded");
                }
                return null;
            });
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> queued = bulkhead.submit(() -> null);
            CompletableFuture<Void> rejected = bulkhead.submit(() -> null);
            assertThat(rejected).isCompletedExceptionally();
            long rejectedCount = bulkhead.snapshot().rejected();
            release.countDown();
            active.get(5, TimeUnit.SECONDS);
            queued.get(5, TimeUnit.SECONDS);
            return rejectedCount;
        } finally {
            release.countDown();
            bulkhead.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static RedisBoundary exerciseRedisBoundary() {
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        AtomicInteger calls = new AtomicInteger();
        when(commands.eval(
                any(String.class), any(ScriptOutputType.class),
                any(String[].class), any(String[].class)))
                .thenAnswer(ignored -> switch (calls.getAndIncrement()) {
                    case 0 -> List.of(1L, 1L, 0L);
                    case 1 -> List.of(1L, 0L, 0L);
                    default -> List.of(0L, 0L, 1L);
                });
        RedisExecutor executor = mock(RedisExecutor.class);
        when(executor.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, Function.class).apply(commands));
        RedisRateLimiter limiter = new RedisRateLimiter(
                executor, "rate:evidence:", 2, 1, 5, 1_000,
                1, 1, System::nanoTime, System::currentTimeMillis);

        int allowed = 0;
        int rejected = 0;
        for (int i = 0; i < 3; i++) {
            if (limiter.tryAcquire("probe").allowed()) allowed++;
            else rejected++;
        }
        return new RedisBoundary(2, 3, allowed, rejected);
    }

    private record RedisBoundary(int limit, int attempted, int allowed, int rejected) {}
}
