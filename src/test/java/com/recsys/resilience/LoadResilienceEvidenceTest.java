package com.recsys.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.recsys.application.retrieval.RecallChannel;
import com.recsys.application.retrieval.multichannel.ChannelHealthMonitor;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallDegradationMetrics;
import com.recsys.application.retrieval.multichannel.RecallResult;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.loadshed.OnlineAdmissionControl;
import com.recsys.loadshed.OnlineLoadShedder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs a bounded workload through real load-path components and publishes its observations. */
@Tag("load")
class LoadResilienceEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(20)
    void publishesServingIsolationRecoveryAndDrainEvidence() throws Exception {
        long started = System.nanoTime();
        ServingObservation serving = exerciseServingAdmissionAndDrain();
        RecallObservation recall = exerciseRecallIsolationAndRecovery();

        Map<String, Object> measurements = new LinkedHashMap<>();
        measurements.put("concurrency", Map.of(
                "offered", serving.offered(), "accepted", serving.accepted()));
        measurements.put("rejections", Map.of(
                "admission", serving.rejected(), "bulkhead", recall.bulkheadRejected()));
        measurements.put("degradation", Map.of(
                "total", recall.total(), "degraded", recall.degraded(),
                "ratio", recall.ratio()));
        measurements.put("timeoutRecovery", Map.of(
                "timeouts", recall.timeouts(), "recovered", recall.recovered()));
        measurements.put("gracefulDrain", Map.of(
                "completed", serving.drainCompleted(),
                "inFlightAfterDrain", serving.inFlightAfterDrain()));
        measurements.put("performance", Map.of(
                "elapsedMillis",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));

        Map<String, Boolean> invariants = new LinkedHashMap<>();
        invariants.put("admissionBounded",
                serving.accepted() == 2 && serving.rejected() == 1);
        invariants.put("bulkheadRejected", recall.bulkheadRejected() > 0);
        invariants.put("degradationMeasured",
                recall.total() == 2 && recall.degraded() == 1 && recall.ratio() == 0.5);
        invariants.put("timeoutRecovered", recall.timeouts() > 0 && recall.recovered());
        invariants.put("gracefulDrainCompleted",
                serving.drainCompleted() && serving.inFlightAfterDrain() == 0);

        writeSidecar("load-serving-and-recall", measurements, invariants);
        assertThat(invariants).allSatisfy((name, passed) ->
                assertThat(passed).as(name).isTrue());
    }

    private static ServingObservation exerciseServingAdmissionAndDrain() throws Exception {
        OnlineLoadShedder shedder = new OnlineLoadShedder(2, 1.0);
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(2);
        CompletableFuture<Void> release = new CompletableFuture<>();
        Server server = Server.builder()
                .http(0)
                .gracefulShutdownTimeoutMillis(0, 1_000)
                .service("/probe", new OnlineAdmissionControl((ctx, req) -> {
                    entered.countDown();
                    return HttpResponse.from(release.thenApply(ignored ->
                            HttpResponse.of(HttpStatus.OK)));
                }, shedder, rejected::incrementAndGet))
                .build();
        server.start().join();
        try {
            WebClient client = WebClient.of("http://127.0.0.1:" + server.activeLocalPort());
            CompletableFuture<HttpStatus> first = status(client);
            CompletableFuture<HttpStatus> second = status(client);
            boolean holdersEntered = entered.await(5, TimeUnit.SECONDS);
            CompletableFuture<HttpStatus> third = status(client);
            HttpStatus rejectedStatus = third.get(5, TimeUnit.SECONDS);
            release.complete(null);
            List<HttpStatus> statuses = List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS),
                    rejectedStatus);
            int accepted = (int) statuses.stream().filter(HttpStatus.OK::equals).count();
            int admissionRejected =
                    (int) statuses.stream().filter(HttpStatus.TOO_MANY_REQUESTS::equals).count();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (shedder.snapshot().inFlightRequests() != 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            shedder.markShuttingDown();
            HttpStatus drainStatus = client.get("/probe").aggregate()
                    .thenApply(response -> response.status()).get(5, TimeUnit.SECONDS);
            int inFlight = shedder.snapshot().inFlightRequests();
            return new ServingObservation(
                    3, accepted, admissionRejected,
                    holdersEntered
                            && rejected.get() >= 2
                            && drainStatus.equals(HttpStatus.TOO_MANY_REQUESTS)
                            && inFlight == 0,
                    inFlight);
        } finally {
            release.complete(null);
            server.stop().join();
        }
    }

    private static CompletableFuture<HttpStatus> status(WebClient client) {
        return client.get("/probe").aggregate().thenApply(response -> response.status());
    }

    private static RecallObservation exerciseRecallIsolationAndRecovery() throws Exception {
        long bulkheadRejected = exerciseBulkheadRejection();
        WorkerBulkhead bulkhead = new WorkerBulkhead("load-evidence-recall", 3, 3);
        try {
            FaultInjector faults = new FaultInjector();
            List<RecallChannel> channels = List.of(
                    channel("slow-a"), channel("slow-b"), channel("slow-c"));
            channels.forEach(channel -> faults.injectLatency("channel:" + channel.name(), 150));
            RecallDegradationMetrics metrics = new RecallDegradationMetrics();
            MultiChannelRecallService service = new MultiChannelRecallService(
                    channels, new ChannelHealthMonitor(), bulkhead.asExecutorService(),
                    25, faults, null,
                    com.recsys.application.retrieval.coldstart.QuotaPolicy.defaultMovie(),
                    metrics);
            RecommendationQuery query =
                    new RecommendationQuery("evidence-user", 10, Set.of(), null);
            RecallResult degraded = service.recallDetailed(query, 10);

            Thread.sleep(250);
            channels.forEach(channel -> faults.clear("channel:" + channel.name()));
            RecallResult recovered = service.recallDetailed(query, 10);
            RecallDegradationMetrics.Snapshot snapshot = metrics.snapshot();
            long timeouts = snapshot.byChannel().values().stream()
                    .mapToLong(reasons ->
                            reasons.getOrDefault(RecallDegradationMetrics.Reason.TIMEOUT, 0L))
                    .sum();
            return new RecallObservation(
                    bulkheadRejected,
                    snapshot.totalRecalls(), snapshot.degradedRecalls(),
                    snapshot.degradedRatio(), timeouts,
                    degraded.outcome() != RecallResult.DegradationOutcome.HEALTHY
                            && recovered.outcome() == RecallResult.DegradationOutcome.HEALTHY);
        } finally {
            bulkhead.close();
        }
    }

    private static long exerciseBulkheadRejection() throws Exception {
        WorkerBulkhead bulkhead = new WorkerBulkhead("load-evidence-saturation", 1, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<Void> active = bulkhead.submit(() -> {
                running.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("bulkhead evidence deadline exceeded");
                }
                return null;
            });
            boolean started = running.await(5, TimeUnit.SECONDS);
            CompletableFuture<Void> queued = bulkhead.submit(() -> null);
            CompletableFuture<Void> rejected = bulkhead.submit(() -> null);
            long rejectedCount = started && rejected.isCompletedExceptionally()
                    ? bulkhead.snapshot().rejected() : 0;
            release.countDown();
            active.get(5, TimeUnit.SECONDS);
            queued.get(5, TimeUnit.SECONDS);
            return rejectedCount;
        } finally {
            release.countDown();
            bulkhead.close();
        }
    }

    private static RecallChannel channel(String name) {
        return new RecallChannel() {
            @Override public String name() { return name; }
            @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                return List.of(new MovieCandidate(name, 1.0, name, Map.of()));
            }
        };
    }

    private static void writeSidecar(
            String source, Map<String, Object> measurements, Map<String, Boolean> invariants)
            throws Exception {
        String suite = System.getProperty("resilience.evidence.suite", "load");
        Path output = Path.of(System.getProperty(
                "resilience.evidence.output", "target/resilience-measurements-load.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(
                output.toAbsolutePath().getParent(), output.getFileName().toString(), ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), Map.of(
                "schemaVersion", 1,
                "suite", suite,
                "source", source,
                "applicability", Map.of(
                        "load", true,
                        "redisBoundary", false,
                        "redisBoundaryCoveredBy", "docker"),
                "measurements", measurements,
                "invariants", invariants));
        Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private record ServingObservation(
            int offered, int accepted, int rejected,
            boolean drainCompleted, int inFlightAfterDrain) {}
    private record RecallObservation(
            long bulkheadRejected, long total, long degraded,
            double ratio, long timeouts, boolean recovered) {}
}
