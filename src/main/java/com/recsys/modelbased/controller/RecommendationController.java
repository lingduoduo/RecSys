package com.recsys.modelbased.controller;

import com.recsys.modelbased.request.RecommendRequest;
import com.recsys.modelbased.response.RecommendResponse;
import com.recsys.modelbased.response.SubmitTokenResponse;
import java.util.Optional;
import com.recsys.modelbased.service.ABTestService;
import com.recsys.modelbased.service.InferenceMetricsService;
import com.recsys.modelbased.service.LoadShedder;
import com.recsys.modelbased.service.ModelRateLimiter;
import com.recsys.modelbased.exception.RateLimitExceededException;
import com.recsys.modelbased.exception.ServiceOverloadedException;
import com.recsys.modelbased.service.RecommendationService;
import com.recsys.modelbased.service.SubmitTokenService;
import jakarta.validation.Valid;
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

    public RecommendationController(RecommendationService recommendationService,
                                    InferenceMetricsService metricsService,
                                    ABTestService abTestService,
                                    LoadShedder loadShedder,
                                    ModelRateLimiter modelRateLimiter,
                                    SubmitTokenService submitTokenService) {
        this.recommendationService = recommendationService;
        this.metricsService = metricsService;
        this.abTestService = abTestService;
        this.loadShedder = loadShedder;
        this.modelRateLimiter = modelRateLimiter;
        this.submitTokenService = submitTokenService;
    }

    @GetMapping(
            value = "/token",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public SubmitTokenResponse getSubmitToken() {
        return new SubmitTokenResponse(submitTokenService.createToken(), submitTokenService.ttlSeconds());
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
        ModelRateLimiter.Decision rateDecision = modelRateLimiter.tryAcquire(request.getUserId());
        if (!rateDecision.allowed()) {
            int retryAfter = Math.max(1, (int) Math.ceil(rateDecision.retryAfter().toMillis() / 1000.0));
            throw new RateLimitExceededException(retryAfter);
        }
        submitTokenService.validateAndConsume(submitToken);
        // Compute A/B assignment once — reused by all downstream paths so the hash is not
        // recomputed on the failure recording path or the degraded-cache fallback.
        ABTestService.Assignment assignment = abTestService.getAssignmentForUser(request.getUserId());
        if (!loadShedder.tryAcquire()) {
            // Degradation (降级): serve stale cache or cold-start popular items before failing.
            Optional<RecommendResponse> fallback = recommendationService.tryServeFromCache(request, assignment);
            if (fallback.isPresent()) {
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Served-From", "degraded-cache");
                return ResponseEntity.ok().headers(headers).body(fallback.get());
            }
            metricsService.recordFailure(0L, assignment.variant());
            throw new ServiceOverloadedException(retryAfterSeconds(metricsService.snapshot()));
        }
        try {
            RecommendResponse response = recommendationService.recommend(request, assignment);
            metricsService.recordSuccess(elapsedMs(startNs), response.abTestVariant(), response.modelVersion());
            // Advertise current capacity so load balancers can adjust routing weight in real time
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
