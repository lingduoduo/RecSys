package com.recsys.streaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

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
        writeJson(response, HttpServletResponse.SC_OK, new OnlineOpsResponse(
                metrics,
                load,
                capacityService.snapshot(metrics, load)
        ));
    }

    private record OnlineOpsResponse(
            OnlineServingMetricsService.Snapshot metrics,
            OnlineLoadShedder.Snapshot load,
            OnlineCapacityService.Snapshot capacity
    ) {}
}
