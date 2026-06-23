package com.recsys.reliability;
import com.recsys.observability.OnlineServingMetricsService;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.api.online.ApiService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class OnlineHealthService extends ApiService {

    private final OnlineServingMetricsService metricsService;
    private final OnlineLoadShedder loadShedder;

    public OnlineHealthService() {
        this(new OnlineServingMetricsService(), new OnlineLoadShedder());
    }

    public OnlineHealthService(OnlineServingMetricsService metricsService, OnlineLoadShedder loadShedder) {
        this.metricsService = metricsService;
        this.loadShedder = loadShedder;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            OnlineLoadShedder.Snapshot load = loadShedder.snapshot();
            OnlineServingMetricsService.Snapshot metrics = metricsService.snapshot();
            boolean ready = !loadShedder.shouldDrain();
            return writeJson(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE, Map.of(
                    "ok", ready,
                    "ready", ready,
                    "service", "online-serving",
                    "qps", metrics.qps(),
                    "inFlightRequests", load.inFlightRequests(),
                    "maxConcurrentRequests", load.maxConcurrentRequests(),
                    "suggestedWeight", load.suggestedWeight()
            ));
        }, ctx.blockingTaskExecutor()));
    }
}
