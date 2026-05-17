package com.recsys.streaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

public final class OnlineHealthServlet extends ApiServlet {
    private final OnlineServingMetricsService metricsService;
    private final OnlineLoadShedder loadShedder;

    public OnlineHealthServlet() {
        this(new OnlineServingMetricsService(), new OnlineLoadShedder());
    }

    public OnlineHealthServlet(OnlineServingMetricsService metricsService, OnlineLoadShedder loadShedder) {
        this.metricsService = metricsService;
        this.loadShedder = loadShedder;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        OnlineLoadShedder.Snapshot load = loadShedder.snapshot();
        OnlineServingMetricsService.Snapshot metrics = metricsService.snapshot();
        boolean ready = !loadShedder.shouldDrain();
        writeJson(response, ready ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE, Map.of(
                "ok", ready,
                "ready", ready,
                "service", "online-serving",
                "qps", metrics.qps(),
                "inFlightRequests", load.inFlightRequests(),
                "maxConcurrentRequests", load.maxConcurrentRequests(),
                "suggestedWeight", load.suggestedWeight()
        ));
    }
}
