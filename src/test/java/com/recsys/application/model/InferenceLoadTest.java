package com.recsys.application.model;
import com.recsys.application.experiment.ABTestService;
import com.recsys.application.model.ModelArtifactLocator;
import com.recsys.application.model.ModelRuntimeProvider;
import com.recsys.application.recommendation.RecommendationService;
import com.recsys.application.retrieval.UserTowerInferenceService;
import com.recsys.config.RecommendationCacheProperties;

import com.recsys.api.request.RecommendRequest;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent characterization of the full recommendation pipeline that PROVES ONNX ran.
 *
 * <p>The earlier version of this test reused ten user IDs with the response cache on, so after
 * warm-up almost every timed request was a cache hit and the "inference" numbers it printed were
 * cache-lookup numbers. Nothing in it could tell. It now runs with both caches disabled, against
 * users the bundled feature config actually knows (so the user tower is encoded, not collapsed
 * to {@code __UNK__}), with per-request exclusion lists so no two requests are equivalent, and
 * asserts on {@link UserTowerInferenceService#runCount()}: the native run count must grow by at
 * least one per successful request. {@link #cachedRepeatedRequests_neverReachTheOnnxSession}
 * pins the other side — the exact setup the old test had produces zero runs — so the assertion
 * is known to discriminate.
 *
 * <p>Latency is reported, not asserted against a universal threshold: the number depends on the
 * CPU shape and on whether Redis is reachable (each recall channel pays its timeout when it is
 * not). A generous completion/success gate keeps the run honest without claiming capacity.
 *
 * Run with: mvn test -DexcludedGroups= -Dgroups=load
 * Excluded from normal test runs (see surefire excludedGroups in pom.xml).
 */
@Tag("load")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InferenceLoadTest {

    private static final int CONCURRENCY      = 10;
    private static final int TOTAL_REQUESTS   = 100;
    private static final long TIMEOUT_SECONDS = 120;
    private static final double MIN_SUCCESS_RATE = 0.99;

    /** Users present in artifacts/model/training/feature_config.json; anything else encodes to __UNK__. */
    private static final String[] KNOWN_USER_IDS = {"123", "124", "125", "126", "127"};
    /** The demo item vocabulary; one is excluded per request so no two requests share a cache key. */
    private static final String[] ITEM_IDS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"};

    private ModelRuntimeProvider runtimeProvider;
    private RecommendationService uncached;
    private RecommendationService cached;
    private UserTowerInferenceService inference;

    @BeforeAll
    void setUp() {
        var locator = new ModelArtifactLocator("", "");
        var abTestConfig = new com.recsys.config.ABTestConfig();
        runtimeProvider = new ModelRuntimeProvider(locator, abTestConfig);
        var abTestService = new ABTestService(abTestConfig);

        RecommendationCacheProperties cachesOff = new RecommendationCacheProperties();
        cachesOff.setEnabled(false);
        cachesOff.setColdStartEnabled(false);
        uncached = new RecommendationService(runtimeProvider, abTestService, cachesOff);
        cached = new RecommendationService(runtimeProvider, abTestService, new RecommendationCacheProperties());

        // Mirror ModelRuntimeProvider.afterSingletonsInstantiated() so the timed loop measures
        // steady-state serving rather than cold first-build latency.
        runtimeProvider.warmUp();
        inference = runtimeProvider.getRuntime(abTestConfig.getDefaultVariant()).inferenceService();

        // warmUp() builds the runtime and runs exactly one smoke inference. The first real
        // requests still pay JIT and ONNX first-run costs, and because the timed loop releases
        // all threads at once that cold cohort would land in the P95 (a dev-machine run measured
        // avg 281ms against P95 2756ms — ~5 cold outliers, not a regression). Two passes over the
        // known users, results discarded, so the timed loop measures the steady state.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < KNOWN_USER_IDS.length; i++) {
                uncached.recommend(buildRequest(KNOWN_USER_IDS[i], 10, ITEM_IDS[i % ITEM_IDS.length]));
            }
        }
    }

    @AfterAll
    void tearDown() {
        if (runtimeProvider != null) runtimeProvider.close();
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS + 10)
    void concurrentUncachedRequests_executeOnnxInferencePerRequest() throws InterruptedException {
        var latenciesMs = new ConcurrentLinkedQueue<Long>();
        var errors      = new AtomicInteger();
        // A load test that reports "9% failed" without saying why is not actionable — the
        // failure tells you to look, and then there is nothing to look at. Keep one sample
        // per distinct failure so the assertion message names the actual cause.
        var failureSamples = new ConcurrentHashMap<String, String>();
        var startGate   = new CountDownLatch(1);
        var doneLatch   = new CountDownLatch(TOTAL_REQUESTS);
        var pool        = Executors.newFixedThreadPool(CONCURRENCY);

        long runsBefore = inference.runCount();
        boolean allDone;
        long wallMs;
        try {
            for (int i = 0; i < TOTAL_REQUESTS; i++) {
                final String userId = KNOWN_USER_IDS[i % KNOWN_USER_IDS.length];
                final String excluded = ITEM_IDS[i % ITEM_IDS.length];
                pool.submit(() -> {
                    try {
                        startGate.await(); // all threads launch simultaneously
                        long t0 = System.nanoTime();
                        try {
                            uncached.recommend(buildRequest(userId, 10, excluded));
                            latenciesMs.add(toMs(System.nanoTime() - t0));
                        } catch (RuntimeException e) {
                            latenciesMs.add(toMs(System.nanoTime() - t0));
                            errors.incrementAndGet();
                            Throwable root = e;
                            while (root.getCause() != null && root.getCause() != root) {
                                root = root.getCause();
                            }
                            failureSamples.putIfAbsent(
                                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                                    "userId=" + userId + " root=" + root.getClass().getName()
                                            + ": " + root.getMessage());
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
            allDone = doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            wallMs = toMs(System.nanoTime() - wallStartNs);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).as("load pool terminated").isTrue();
        }
        long runDelta = inference.runCount() - runsBefore;

        assertThat(allDone)
                .as("all %d requests completed within %ds", TOTAL_REQUESTS, TIMEOUT_SECONDS)
                .isTrue();

        // --- characterization: reported, not asserted ---
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        int n = sorted.size();
        long avgMs      = (long) sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long p95Ms      = percentile(sorted, 0.95);
        double rps      = n * 1000.0 / Math.max(1, wallMs);
        int successes   = n - errors.get();
        double successRate = (double) successes / n;

        printReport(n, CONCURRENCY, avgMs, p95Ms, rps, successRate, errors.get(), runDelta);

        // --- assert ---
        assertThat(successRate)
                .as("success rate (%d/%d failed). Distinct failures: %s",
                        errors.get(), n, failureSamples)
                .isGreaterThanOrEqualTo(MIN_SUCCESS_RATE);
        assertThat(runDelta)
                .as("ONNX runs during the timed section: every uncached request must reach "
                        + "OrtSession.run; a smaller delta means caches or out-of-vocab fallback "
                        + "ranking served requests and this test measured something other than inference")
                .isGreaterThanOrEqualTo(successes);
    }

    @Test
    void cachedRepeatedRequests_neverReachTheOnnxSession() {
        // The trap the timed test is built to avoid, pinned so nobody re-introduces it: with the
        // response cache on and the same request repeated, the session is never entered.
        RecommendRequest repeated = buildRequest("123", 10, null);
        cached.recommend(repeated);   // populate
        long before = inference.runCount();

        for (int i = 0; i < 20; i++) {
            cached.recommend(repeated);
        }

        assertThat(inference.runCount() - before)
                .as("repeated identical requests are served from cache; a load test built on them measures the cache")
                .isZero();
    }

    // --- helpers ---

    private static RecommendRequest buildRequest(String userId, int k, String excludedItemId) {
        var req = new RecommendRequest();
        req.setUserId(userId);
        req.setK(k);
        if (excludedItemId != null) {
            req.setExcludeItemIds(List.of(excludedItemId));
        }
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
                                    double rps, double successRate, int errorCount, long onnxRuns) {
        System.out.printf("%n┌─ Inference load test (%d requests, %d threads, caches OFF) ──┐%n", n, threads);
        System.out.printf("│  Avg latency    : %6d ms                           │%n", avgMs);
        System.out.printf("│  P95 latency    : %6d ms                           │%n", p95Ms);
        System.out.printf("│  Throughput     : %8.1f req/s                     │%n", rps);
        System.out.printf("│  Success rate   : %8.1f%%  (%d errors)            │%n", successRate * 100, errorCount);
        System.out.printf("│  ONNX runs      : %6d  (>= %d required)           │%n", onnxRuns, n - errorCount);
        System.out.printf("└─────────────────────────────────────────────────────┘%n%n");
    }
}
