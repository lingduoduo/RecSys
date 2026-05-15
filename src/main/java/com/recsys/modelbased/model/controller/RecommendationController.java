package com.recsys.modelbased.model.controller;

import com.recsys.modelbased.model.dto.RecommendRequest;
import com.recsys.modelbased.model.dto.RecommendResponse;
import com.recsys.modelbased.model.service.ABTestService;
import com.recsys.modelbased.model.service.InferenceMetricsService;
import com.recsys.modelbased.model.service.LoadShedder;
import com.recsys.modelbased.model.service.RecommendationService;
import com.recsys.modelbased.model.service.ServiceOverloadedException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final InferenceMetricsService metricsService;
    private final ABTestService abTestService;
    private final LoadShedder loadShedder;

    public RecommendationController(RecommendationService recommendationService,
                                    InferenceMetricsService metricsService,
                                    ABTestService abTestService,
                                    LoadShedder loadShedder) {
        this.recommendationService = recommendationService;
        this.metricsService = metricsService;
        this.abTestService = abTestService;
        this.loadShedder = loadShedder;
    }

    @PostMapping(
            value = "/recommend",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public RecommendResponse recommend(@Valid @RequestBody RecommendRequest request,
                                       HttpServletResponse httpResponse) {
        long startNs = System.nanoTime();
        if (!loadShedder.tryAcquire()) {
            metricsService.recordFailure(0L, abTestService.getVariantForUser(request.getUserId()));
            // Estimate how soon a slot is likely to free based on recent average latency.
            throw new ServiceOverloadedException(retryAfterSeconds(metricsService.snapshot()));
        }
        try {
            RecommendResponse response = recommendationService.recommend(request);
            metricsService.recordSuccess(elapsedMs(startNs), response.abTestVariant(), response.modelVersion());
            // Advertise current capacity so load balancers can adjust routing weight in real time
            // without waiting for the next /health/ready poll (e.g. Envoy, Consul, NGINX Plus).
            httpResponse.setIntHeader("X-Capacity-Weight", loadShedder.snapshot().suggestedWeight());
            return response;
        } catch (IllegalArgumentException e) {
            // service-level guard — not an inference failure
            throw e;
        } catch (RuntimeException e) {
            metricsService.recordFailure(elapsedMs(startNs), abTestService.getVariantForUser(request.getUserId()));
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
