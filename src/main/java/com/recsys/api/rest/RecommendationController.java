package com.recsys.api.rest;

import com.recsys.api.request.RecommendRequest;
import com.recsys.api.response.RecommendResponse;
import com.recsys.api.response.SubmitTokenResponse;
import java.util.Optional;
import com.recsys.application.experiment.ABTestService;
import com.recsys.application.experiment.AbExposureLogger;
import com.recsys.metrics.InferenceMetricsService;
import com.recsys.loadshed.LoadShedder;
import com.recsys.ratelimit.ModelRateLimiter;
import com.recsys.exception.RateLimitExceededException;
import com.recsys.exception.ServiceOverloadedException;
import com.recsys.application.recommendation.RecommendationService;
import com.recsys.application.retrieval.multichannel.RecallDegradationMetrics;
import com.recsys.application.retrieval.multichannel.RecallResult;
import com.recsys.application.auth.SubmitTokenService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final InferenceMetricsService metricsService;
    private final ABTestService abTestService;
    private final LoadShedder loadShedder;
    private final ModelRateLimiter modelRateLimiter;
    private final SubmitTokenService submitTokenService;
    private final AbExposureLogger abExposureLogger;
    private final RecallDegradationMetrics recallDegradationMetrics;

    public RecommendationController(RecommendationService recommendationService,
                                    InferenceMetricsService metricsService,
                                    ABTestService abTestService,
                                    LoadShedder loadShedder,
                                    ModelRateLimiter modelRateLimiter,
                                    SubmitTokenService submitTokenService,
                                    AbExposureLogger abExposureLogger,
                                    RecallDegradationMetrics recallDegradationMetrics) {
        this.recommendationService = recommendationService;
        this.metricsService = metricsService;
        this.abTestService = abTestService;
        this.loadShedder = loadShedder;
        this.modelRateLimiter = modelRateLimiter;
        this.submitTokenService = submitTokenService;
        this.abExposureLogger = abExposureLogger;
        this.recallDegradationMetrics = recallDegradationMetrics;
    }

    @GetMapping(
            value = "/token",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<SubmitTokenResponse> getSubmitToken() {
        // Single-use CSRF token: a shared cache handing the same token to two clients would
        // break single-use semantics. Never cache, at any layer.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SubmitTokenResponse(submitTokenService.createToken(),
                        submitTokenService.ttlSeconds()));
    }

    @PostMapping(
            value = "/recommend",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<RecommendResponse> recommend(@Valid @RequestBody RecommendRequest request,
                                                       @RequestHeader(value = SubmitTokenService.HEADER_NAME, required = false)
                                                       String submitToken) {
        long startNs = System.nanoTime();
        // Per-user rate check runs before the global semaphore so a single user can't burn
        // concurrency slots that other users need.
        //
        // Keyed intentionally on the SERVED userId, not the authenticated caller. Callers here are
        // trusted backends (API key / machine JWT) that legitimately request for many users; this
        // limiter enforces fairness *between served users*, while per-caller abuse throttling is the
        // gateway's GatewayRateLimiter (keyed on the principal). Re-keying this on the caller would
        // collapse a high-volume backend into one bucket and throttle legitimate traffic.
        ModelRateLimiter.Decision rateDecision = modelRateLimiter.tryAcquire(request.getUserId());
        if (!rateDecision.allowed()) {
            int retryAfter = Math.max(1, (int) Math.ceil(rateDecision.retryAfter().toMillis() / 1000.0));
            throw new RateLimitExceededException(retryAfter);
        }
        submitTokenService.validateAndConsume(submitToken);
        // Compute A/B assignment once — reused by all downstream paths so the hash is not
        // recomputed on the failure recording path or the degraded-cache fallback. Keyed on the
        // served userId by design: the experiment subject is the user being recommended for, so
        // exposure logging and bucketing must follow userId, never the calling principal.
        ABTestService.Assignment assignment = abTestService.getAssignmentForUser(request.getUserId());
        if (!loadShedder.tryAcquire()) {
            // Degradation: serve stale cache or cold-start popular items before failing.
            Optional<RecommendResponse> fallback = recommendationService.tryServeFromCache(request, assignment);
            if (fallback.isPresent()) {
                RecommendResponse degraded = fallback.get();
                abExposureLogger.log(request.getUserId(), assignment, degraded.abTestVariant(),
                        !degraded.abTestVariant().equals(assignment.variant()), degraded.modelVersion());
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Served-From", "degraded-cache");
                headers.set("X-Recall-Degradation-Reason", "fallback");
                recallDegradationMetrics.recordOutcome(RecallResult.DegradationOutcome.FALLBACK);
                return ResponseEntity.ok().headers(headers).body(degraded);
            }
            metricsService.recordFailure(0L, assignment.variant());
            throw new ServiceOverloadedException(retryAfterSeconds(metricsService.snapshot()));
        }
        try {
            RecommendResponse response = recommendationService.recommend(request, assignment);
            metricsService.recordSuccess(elapsedMs(startNs), response.abTestVariant(), response.modelVersion());
            abExposureLogger.log(request.getUserId(), assignment, response.abTestVariant(),
                    !response.abTestVariant().equals(assignment.variant()), response.modelVersion());
            // Publish current capacity so load balancers can adjust routing weight in real time
            // without waiting for the next /health/ready poll (e.g. Envoy, Consul, NGINX Plus).
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Capacity-Weight", String.valueOf(loadShedder.snapshot().suggestedWeight()));
            return ResponseEntity.ok().headers(headers).body(response);
        } catch (IllegalArgumentException e) {
            // service-level guard — not an inference failure
            throw e;
        } catch (RuntimeException e) {
            metricsService.recordFailure(elapsedMs(startNs), assignment.variant());
            throw e;
        } finally {
            loadShedder.release();
        }
    }

    /**
     * Estimates a sensible Retry-After value from the rolling-average inference latency.
     * Clients that back off for roughly one inference cycle avoid piling up retries while
     * the instance is still processing the current batch of requests.
     */
    private static int retryAfterSeconds(InferenceMetricsService.Snapshot metrics) {
        if (metrics == null) return 1;
        double avgMs = metrics.recentAvgLatencyMs();
        if (avgMs > 0) {
            return Math.min(10, Math.max(1, (int) Math.ceil(avgMs / 1000.0)));
        }
        return 1;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
