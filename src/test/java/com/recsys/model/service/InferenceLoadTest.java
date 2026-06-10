package com.recsys.model.service;

import com.recsys.model.request.RecommendRequest;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent load test for the full recommendation pipeline.
 *
 * Run with: mvn test -Dgroups=load
 * Excluded from normal test runs (see surefire excludedGroups in pom.xml).
 */
@Tag("load")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InferenceLoadTest {

    private static final int CONCURRENCY      = 10;
    private static final int TOTAL_REQUESTS   = 100;
    private static final long TIMEOUT_SECONDS = 60;

    // Thresholds — conservative for CPU-only CI hardware
    private static final double MIN_SUCCESS_RATE   = 0.99;
    private static final long   MAX_P95_LATENCY_MS = 2000L;

    private static final String[] USER_IDS = {"1", "10", "50", "100", "123", "200", "300", "400", "500", "600"};

    private RecommendationService service;
    private ModelRuntimeProvider runtimeProvider;

    @BeforeAll
    void setUp() throws Exception {
        var locator = new ModelArtifactLocator("", "");
        runtimeProvider = new ModelRuntimeProvider(locator, new com.recsys.config.ABTestConfig());
        service = new RecommendationService(
                runtimeProvider,
                new ABTestService(new com.recsys.config.ABTestConfig())
        );
    }

    @AfterAll
    void tearDown() throws Exception {
        if (runtimeProvider != null) runtimeProvider.close();
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS + 10)
    void concurrentRequests_meetsLatencyAndThroughputTargets() throws InterruptedException {
        var latenciesMs = new ConcurrentLinkedQueue<Long>();
        var errors      = new AtomicInteger();
        var startGate   = new CountDownLatch(1);
        var doneLatch   = new CountDownLatch(TOTAL_REQUESTS);
        var pool        = Executors.newFixedThreadPool(CONCURRENCY);

        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            final String userId = USER_IDS[i % USER_IDS.length];
            pool.submit(() -> {
                try {
                    startGate.await(); // all threads launch simultaneously
                    long t0 = System.nanoTime();
                    try {
                        service.recommend(buildRequest(userId, 10));
                        latenciesMs.add(toMs(System.nanoTime() - t0));
                    } catch (RuntimeException e) {
                        latenciesMs.add(toMs(System.nanoTime() - t0));
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long wallStartNs = System.nanoTime();
        startGate.countDown();
        boolean allDone = doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long wallMs = toMs(System.nanoTime() - wallStartNs);
        pool.shutdown();

        assertThat(allDone)
                .as("all %d requests completed within %ds", TOTAL_REQUESTS, TIMEOUT_SECONDS)
                .isTrue();

        // --- compute stats ---
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        int n = sorted.size();

        long avgMs      = (long) sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long p95Ms      = percentile(sorted, 0.95);
        double rps      = n * 1000.0 / wallMs;
        double successRate = 1.0 - (double) errors.get() / n;

        printReport(n, CONCURRENCY, avgMs, p95Ms, rps, successRate, errors.get());

        // --- assert ---
        assertThat(successRate)
                .as("success rate")
                .isGreaterThanOrEqualTo(MIN_SUCCESS_RATE);
        assertThat(p95Ms)
                .as("P95 latency ms")
                .isLessThanOrEqualTo(MAX_P95_LATENCY_MS);
    }

    // --- helpers ---

    private static RecommendRequest buildRequest(String userId, int k) {
        var req = new RecommendRequest();
        req.setUserId(userId);
        req.setK(k);
        return req;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, idx));
    }

    private static long toMs(long nanos) {
        return nanos / 1_000_000L;
    }

    private static void printReport(int n, int threads, long avgMs, long p95Ms,
                                    double rps, double successRate, int errorCount) {
        System.out.printf("%n┌─ Inference load test (%d requests, %d threads) ─────┐%n", n, threads);
        System.out.printf("│  Avg latency    : %6d ms                           │%n", avgMs);
        System.out.printf("│  P95 latency    : %6d ms                           │%n", p95Ms);
        System.out.printf("│  Throughput     : %8.1f req/s                     │%n", rps);
        System.out.printf("│  Success rate   : %8.1f%%  (%d errors)            │%n", successRate * 100, errorCount);
        System.out.printf("└─────────────────────────────────────────────────────┘%n%n");
    }
}
