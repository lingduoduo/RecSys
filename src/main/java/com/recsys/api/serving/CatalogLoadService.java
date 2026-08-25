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
 * auth. Note: {@code bulkhead.rejected} is intentionally omitted from this endpoint's
 * response, not because it can't fire on this path -- {@code WorkerBulkhead}'s
 * {@code RejectedExecutionHandler} counts a rejection regardless of whether it came
 * through {@code submit()} or (as recall does) {@code asExecutorService()}+{@code
 * supplyAsync} -- but because {@code channelDegraded[*].rejected} is the
 * per-channel breakdown an operator actually wants here; the raw bulkhead count is
 * still available via {@code recsys_queue_rejected_total{queue="recall-catalog"}}.
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
        bulkhead.put("queueCapacity", b.queueCapacity());

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
