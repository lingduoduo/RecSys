package com.recsys.streaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;

public final class OnlineOpsServlet extends ApiServlet {
    private final OnlineServingMetricsService metricsService;
    private final OnlineLoadShedder loadShedder;
    private final OnlineCapacityService capacityService;

    public OnlineOpsServlet(OnlineServingMetricsService metricsService,
                            OnlineLoadShedder loadShedder,
                            OnlineCapacityService capacityService) {
        this.metricsService = metricsService;
        this.loadShedder = loadShedder;
        this.capacityService = capacityService;
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
                capacity
        ));
    }

    private record OnlineOpsResponse(
            String servedAt,
            OnlineServingMetricsService.Snapshot metrics,
            OnlineLoadShedder.Snapshot load,
            OnlineCapacityService.Snapshot capacity
    ) {}
}
