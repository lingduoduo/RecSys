package com.recsys.api.online;
import com.recsys.application.online.OnlineServices;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.application.online.OnlineRecommendationService;
import com.recsys.observability.OnlineServingMetricsService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.domain.item.Movie;
import com.recsys.domain.user.User;
import com.recsys.reliability.OnlineAdmissionControl;
import com.recsys.reliability.OnlineLoadShedder;
import com.recsys.observability.OnlineServingMetricsService;
import com.recsys.reliability.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test that verifies GET /online/recommendation still returns 200
 * with the expected response shape after the /v2/recommend endpoint was added.
 */
class OnlinePredictionRegressionTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final OnlineRecommendationService mockRec = mock(OnlineRecommendationService.class);

    static {
        OnlineRecommendationResult result = new OnlineRecommendationResult(
                new User(1, "Alice"), "last_hour", "multichannel",
                List.of(new Movie(5, "Interstellar", 2014, List.of("Sci-Fi"))),
                List.of(new Movie(7, "The Matrix", 1999, List.of("Sci-Fi"))),
                List.of(new Movie(10, "Inception", 2010, List.of("Sci-Fi"))));
        when(mockRec.recommend(any())).thenReturn(result);
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            OnlineServingMetricsService metrics = new OnlineServingMetricsService();
            OnlineLoadShedder shedder = new OnlineLoadShedder();

            sb.service("/online/recommendation",
                    new OnlineAdmissionControl(
                            new OnlineServices.Prediction(mockRec, metrics, shedder,
                                    RedisRateLimiter.disabled(), true),
                            shedder, metrics));
        }
    };

    @Test
    void getRecommendation_returns200WithExpectedShape() throws Exception {
        AggregatedHttpResponse r = server.blockingWebClient()
                .get("/online/recommendation?userId=1&k=5");

        assertThat(r.status()).isEqualTo(HttpStatus.OK);

        JsonNode json = MAPPER.readTree(r.contentUtf8());
        assertThat(json.has("user")).isTrue();
        assertThat(json.get("user").get("userId").asInt()).isEqualTo(1);
        assertThat(json.has("window")).isTrue();
        assertThat(json.get("window").asText()).isEqualTo("last_hour");
        assertThat(json.has("strategy")).isTrue();
        assertThat(json.get("strategy").asText()).isEqualTo("multichannel");
        assertThat(json.has("recommendations")).isTrue();
        assertThat(json.get("recommendations").isArray()).isTrue();
        assertThat(json.get("recommendations").size()).isEqualTo(1);
        assertThat(json.get("recommendations").get(0).get("title").asText()).isEqualTo("Inception");
    }

    @Test
    void getRecommendation_missingUserId_returns400() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .get("/online/recommendation");
        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getRecommendation_nonNumericUserId_returns400() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .get("/online/recommendation?userId=abc&k=5");
        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
