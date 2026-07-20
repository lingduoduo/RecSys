package com.recsys.api.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.application.retrieval.multichannel.RecallDegradationMetrics;
import com.recsys.resilience.WorkerBulkhead;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * GET /health/load — catalog serving (6010) load snapshot: live recall-bulkhead
 * pressure plus cumulative silent-degradation counters. Read-only; normal gateway
 * auth. Note: the bulkhead's own {@code rejected} counter is always 0 on this path
 * (recall runs via asExecutorService()+supplyAsync, not submit()), so it is omitted;
 * the authoritative rejection count is channelDegraded[*].rejected.
 */
public final class CatalogLoadService extends BaseApiService {

    private final WorkerBulkhead recallBulkhead;
    private final RecallDegradationMetrics degradationMetrics;

    public CatalogLoadService(WorkerBulkhead recallBulkhead,
                              RecallDegradationMetrics degradationMetrics) {
        this.recallBulkhead = Objects.requireNonNull(recallBulkhead, "recallBulkhead");
        this.degradationMetrics = Objects.requireNonNull(degradationMetrics, "degradationMetrics");
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        WorkerBulkhead.Snapshot b = recallBulkhead.snapshot();
        RecallDegradationMetrics.Snapshot d = degradationMetrics.snapshot();

        Map<String, Object> bulkhead = new LinkedHashMap<>();
        bulkhead.put("poolSize", b.poolSize());
        bulkhead.put("active", b.active());
        bulkhead.put("queued", b.queued());

        Map<String, Object> channelDegraded = new LinkedHashMap<>();
        d.byChannel().forEach((channel, reasons) -> {
            Map<String, Long> byReason = new LinkedHashMap<>();
            reasons.forEach((reason, count) -> byReason.put(reason.name(), count));
            channelDegraded.put(channel, byReason);
        });

        Map<String, Object> recall = new LinkedHashMap<>();
        recall.put("bulkhead", bulkhead);
        recall.put("channelDegraded", channelDegraded);
        recall.put("degradedRatio", d.degradedRatio());

        return writeJson(HttpStatus.OK, Map.of("recall", recall));
    }
}
