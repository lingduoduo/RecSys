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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineV2RecommendIntegrationTest {

    static final int MAX_CANDIDATES = 500;
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

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            RecommendationPipeline pipeline =
                    new OnlineBlendingPipeline(mockService, pagination(), MAX_CANDIDATES);
            sb.service("/v2/recommend", new OnlineServices.RecommendV2(pipeline));
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

    private static RecommendationPaginationCoordinator pagination() {
        RecommendationPaginationConfig config = new RecommendationPaginationConfig(
                "online-v2-integration-signing-key".repeat(2),
                null,
                Duration.ofMinutes(15),
                false,
                MAX_CANDIDATES);
        return new RecommendationPaginationCoordinator(
                new RecommendationCursorCodec(config, Clock.systemUTC()),
                new CursorPaginationService(),
                new RecommendationPaginationMetrics(new SimpleMeterRegistry()));
    }
}
