package com.recsys.api.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.application.pagination.RecommendationPaginationRuntime;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecSysServerMetricsEndpointTest {

    private static final PrometheusMeterRegistry REGISTRY =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder builder) {
            RecommendationPaginationRuntime pagination =
                    RecommendationPaginationRuntime.fromEnvironment(
                            name -> name.equals("RECOMMENDATION_CURSOR_SIGNING_KEY")
                                    ? "metrics-endpoint-test-signing-key-0001"
                                    : null,
                            REGISTRY,
                            Clock.fixed(
                                    Instant.parse("2026-07-27T12:00:00Z"),
                                    ZoneOffset.UTC));
            pagination.coordinator().page(
                    new RecommendationQuery("u1", 1, Set.of(), null),
                    List.of(
                            new RankedMovie("a", 0.9, 1, Map.of()),
                            new RankedMovie("b", 0.8, 2, Map.of())),
                    false);
            RecSysServer.registerMetricsEndpoint(builder, REGISTRY);
        }
    };

    @Test
    void metricsEndpointExposesPaginationCountersFromTheServingRegistry() {
        AggregatedHttpResponse response = server.blockingWebClient().get("/metrics");

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8())
                .contains("recsys_pagination_page_returned_total")
                .contains("terminal=\"false\"");
    }
}
