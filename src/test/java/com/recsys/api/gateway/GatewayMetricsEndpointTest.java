package com.recsys.api.gateway;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMetricsEndpointTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            PrometheusMeterRegistry reg = PrometheusMeterRegistries.defaultRegistry();
            sb.service("/metrics", PrometheusExpositionService.of(reg.getPrometheusRegistry()));
        }
    };

    @Test
    void metricsEndpointReturns200() {
        AggregatedHttpResponse resp = server.blockingWebClient().get("/metrics");
        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
    }
}
