package com.recsys.streaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;

public final class OnlineOpsServlet extends ApiServlet {
    private final OnlineServingMetricsService metricsService;
    private final OnlineLoadShedder loadShedder;
    private final OnlineCapacityService capacityService;
    private final RedisRateLimiter redisRateLimiter;

    public OnlineOpsServlet(OnlineServingMetricsService metricsService,
                            OnlineLoadShedder loadShedder,
                            OnlineCapacityService capacityService) {
        this(metricsService, loadShedder, capacityService, RedisRateLimiter.disabled());
    }

    public OnlineOpsServlet(OnlineServingMetricsService metricsService,
                            OnlineLoadShedder loadShedder,
                            OnlineCapacityService capacityService,
                            RedisRateLimiter redisRateLimiter) {
        this.metricsService = metricsService;
        this.loadShedder = loadShedder;
        this.capacityService = capacityService;
        this.redisRateLimiter = redisRateLimiter;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        OnlineServingMetricsService.Snapshot metrics = metricsService.snapshot();
        OnlineLoadShedder.Snapshot load = loadShedder.snapshot();
        OnlineCapacityService.Snapshot capacity = capacityService.snapshot(metrics, load);

        if (load.retryAfterSeconds() > 0) {
            response.setIntHeader("Retry-After", load.retryAfterSeconds());
        }

        writeJson(response, HttpServletResponse.SC_OK, new OnlineOpsResponse(
                Instant.now().toString(),
                metrics,
                load,
                redisRateLimiter.snapshot(),
                capacity
        ));
    }

    private record OnlineOpsResponse(
            String servedAt,
            OnlineServingMetricsService.Snapshot metrics,
            OnlineLoadShedder.Snapshot load,
            RedisRateLimiter.Snapshot rateLimit,
            OnlineCapacityService.Snapshot capacity
    ) {}
}
