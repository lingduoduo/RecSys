package com.recsys.api.online;

import com.recsys.application.online.OnlineBlendingPipeline;
import com.recsys.application.online.OnlineServices;
import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.pagination.RecommendationCursorCodec;
import com.recsys.application.pagination.RecommendationPaginationConfig;
import com.recsys.application.pagination.RecommendationPaginationCoordinator;
import com.recsys.application.pagination.RecommendationPaginationMetrics;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.application.online.OnlineRecommendationService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.domain.item.Movie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.user.User;
import com.recsys.application.recommendation.RecommendationPipeline;
import com.recsys.loadshed.OnlineAdmissionControl;
import com.recsys.loadshed.OnlineLoadShedder;
import com.recsys.metrics.OnlineServingMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnlineV2RecommendIntegrationTest {

    static final int MAX_CANDIDATES = 500;
    static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC);
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final OnlineRecommendationService mockService =
            mock(OnlineRecommendationService.class);

    static {
        when(mockService.recommend(any())).thenReturn(
                new OnlineRecommendationResult(
                        new User(1, "Alice"), "last_hour", "online+model",
                        List.of(), List.of(),
                        List.of(new Movie(10, "Inception", 2010, List.of("Sci-Fi")))));
    }

    /** Capacity 1 so a single held permit saturates it deterministically — no wall-clock timing. */
    static final OnlineLoadShedder LOAD_SHEDDER = new OnlineLoadShedder(1, 0.9);
    static final OnlineServingMetricsService METRICS = new OnlineServingMetricsService();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            RecommendationPipeline pipeline =
                    new OnlineBlendingPipeline(mockService, pagination(), MAX_CANDIDATES);
            // Mirrors OnlinePredictionServer: /v2/recommend is admission-controlled exactly as
            // /online/recommendation is. Registering it bare here would let the production wrap
            // be removed without any test noticing.
            sb.service("/v2/recommend",
                    new OnlineAdmissionControl(
                            new OnlineServices.RecommendV2(pipeline), LOAD_SHEDDER, METRICS));
        }
    };

    @Test
    void validQuery_returns200WithRecommendationResult() throws Exception {
        String body = MAPPER.writeValueAsString(
                new RecommendationQuery("1", 5, Set.of(), null));
        AggregatedHttpResponse r = server.blockingWebClient()
                .execute(HttpRequest.of(
                        RequestHeaders.of(
                                HttpMethod.POST, "/v2/recommend",
                                HttpHeaderNames.CONTENT_TYPE, "application/json"),
                        HttpData.ofUtf8(body)));

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"userId\":\"1\"");
        assertThat(r.contentUtf8()).contains("\"hasMore\":false");
        assertThat(r.contentUtf8()).contains("\"strategy\"");
        assertThat(r.contentUtf8()).contains("\"window\"");
    }

    @Test
    void nonNumericUserId_returns400() throws Exception {
        String body = MAPPER.writeValueAsString(
                new RecommendationQuery("not-a-number", 5, Set.of(), null));
        AggregatedHttpResponse r = server.blockingWebClient()
                .execute(HttpRequest.of(
                        RequestHeaders.of(
                                HttpMethod.POST, "/v2/recommend",
                                HttpHeaderNames.CONTENT_TYPE, "application/json"),
                        HttpData.ofUtf8(body)));

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidCursorReturnsGeneric400BeforeOnlineSourceWork() throws Exception {
        clearInvocations(mockService);
        String body = MAPPER.writeValueAsString(
                new RecommendationQuery("1", 5, Set.of(), "not-a-valid-cursor"));

        AggregatedHttpResponse response = server.blockingWebClient()
                .execute(HttpRequest.of(
                        RequestHeaders.of(
                                HttpMethod.POST, "/v2/recommend",
                                HttpHeaderNames.CONTENT_TYPE, "application/json"),
                        HttpData.ofUtf8(body)));

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8())
                .isEqualTo("{\"error\":\"Invalid recommendation cursor\"}");
        verify(mockService, never()).recommend(any());
    }

    private static RecommendationPaginationCoordinator pagination() {
        RecommendationPaginationConfig config = new RecommendationPaginationConfig(
                "online-v2-integration-signing-key".repeat(2),
                null,
                Duration.ofMinutes(15),
                false,
                MAX_CANDIDATES);
        return new RecommendationPaginationCoordinator(
                new RecommendationCursorCodec(config, FIXED_CLOCK),
                new CursorPaginationService(),
                new RecommendationPaginationMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void v2RecommendShedsWhenTheShedderIsSaturated() throws Exception {
        // /online/recommendation has been admission-controlled all along; /v2/recommend was not,
        // so the route the canonical POST /api/recommend reaches had unbounded concurrency here.
        // Goes red if the OnlineAdmissionControl wrap is removed from either the server or
        // this harness.
        assertThat(LOAD_SHEDDER.tryAcquire()).as("capacity 1 -> now saturated").isTrue();
        try {
            String body = MAPPER.writeValueAsString(
                    new RecommendationQuery("1", 5, Set.of(), null));
            AggregatedHttpResponse r = server.blockingWebClient()
                    .execute(HttpRequest.of(
                            RequestHeaders.of(
                                    HttpMethod.POST, "/v2/recommend",
                                    HttpHeaderNames.CONTENT_TYPE, "application/json"),
                            HttpData.ofUtf8(body)));

            // 429, not 503: OnlineAdmissionControl sheds with TOO_MANY_REQUESTS + Retry-After.
            // That differs from the 8080 path, where ProtectedRecommendationPipeline throws
            // ServiceOverloadedException -> 503. Both are "shed", but the two services report it
            // differently, and this test pins 7010's actual contract rather than an assumed one.
            assertThat(r.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(r.headers().get(HttpHeaderNames.RETRY_AFTER)).isNotNull();
        } finally {
            // Must release even on failure: the shedder is static and shared, so a leaked permit
            // would cascade into every sibling test in this class.
            LOAD_SHEDDER.release();
        }
    }
}
