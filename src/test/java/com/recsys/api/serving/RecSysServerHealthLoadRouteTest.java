package com.recsys.api.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.application.retrieval.multichannel.RecallDegradationMetrics;
import com.recsys.resilience.WorkerBulkhead;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that the same RecallDegradationMetrics instance recorded into by the recall
 * service is the one served by CatalogLoadService (the wiring contract). Full server
 * bootstrap needs Redis, so this asserts the shared-instance contract directly.
 */
class RecSysServerHealthLoadRouteTest {

    @Test
    void loadServiceReflectsMetricsRecordedElsewhere() throws Exception {
        RecallDegradationMetrics shared = new RecallDegradationMetrics();
        CatalogLoadService service = new CatalogLoadService(
                new WorkerBulkhead("recall-catalog", 2, 4), shared);

        // Simulate the recall service recording into the shared instance.
        shared.recordTotal();
        shared.record("trending", RecallDegradationMetrics.Reason.REJECTED);
        shared.recordDegradedRequest();

        HttpRequest req = HttpRequest.of(HttpMethod.GET, "/health/load");
        AggregatedHttpResponse res = service.serve(ServiceRequestContext.of(req), req).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).contains("\"degradedRatio\":1.0");
        assertThat(res.contentUtf8()).contains("trending");
    }
}
