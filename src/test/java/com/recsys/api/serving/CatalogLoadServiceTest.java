package com.recsys.api.serving;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.application.retrieval.multichannel.RecallDegradationMetrics;
import com.recsys.resilience.WorkerBulkhead;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogLoadServiceTest {

    @Test
    void reportsBulkheadAndDegradationSnapshot() throws Exception {
        WorkerBulkhead bulkhead = new WorkerBulkhead("recall-catalog", 4, 16);
        RecallDegradationMetrics metrics = new RecallDegradationMetrics();
        metrics.recordTotal();
        metrics.recordTotal();
        metrics.record("trending", RecallDegradationMetrics.Reason.REJECTED);

        CatalogLoadService service = new CatalogLoadService(bulkhead, metrics);

        HttpRequest req = HttpRequest.of(HttpMethod.GET, "/health/load");
        ServiceRequestContext ctx = ServiceRequestContext.of(req);
        AggregatedHttpResponse res = service.serve(ctx, req).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        JsonNode root = new ObjectMapper().readTree(res.contentUtf8());
        JsonNode recall = root.get("recall");
        assertThat(recall.get("bulkhead").get("poolSize").asInt()).isEqualTo(4);
        assertThat(recall.get("bulkhead").has("rejected")).isFalse();
        assertThat(recall.get("channelDegraded").get("trending").get("REJECTED").asLong())
                .isEqualTo(1L);
        assertThat(recall.get("degradedRatio").asDouble()).isEqualTo(0.5);

        bulkhead.close();
    }

    @Test
    void zeroTrafficRatioIsZero() throws Exception {
        CatalogLoadService service = new CatalogLoadService(
                new WorkerBulkhead("recall-catalog", 2, 4), new RecallDegradationMetrics());
        HttpRequest req = HttpRequest.of(HttpMethod.GET, "/health/load");
        AggregatedHttpResponse res = service.serve(ServiceRequestContext.of(req), req).aggregate().join();
        JsonNode root = new ObjectMapper().readTree(res.contentUtf8());
        assertThat(root.get("recall").get("degradedRatio").asDouble()).isEqualTo(0.0);
    }
}
