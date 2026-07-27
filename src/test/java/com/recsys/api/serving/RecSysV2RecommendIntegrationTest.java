package com.recsys.api.serving;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.application.recommendation.RecommendationPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecSysV2RecommendIntegrationTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final RecommendationPipeline mockPipeline = mock(RecommendationPipeline.class);

    static {
        when(mockPipeline.recommend(any())).thenReturn(
                new RecommendationResult("1",
                        List.of(new RankedMovie("42", 0.9, 1, Map.of())),
                        null,
                        false,
                        Map.of("candidateCount", "10", "rankedCount", "5")));
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/v2/recommend", new RecommendationService.V2(mockPipeline));
        }
    };

    @Test
    void validQuery_returns200WithRecommendationResult() throws Exception {
        String body = MAPPER.writeValueAsString(
                new RecommendationQuery("1", 5, Set.of(), null));

        AggregatedHttpResponse r = server.blockingWebClient()
                .post("/v2/recommend", body);

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"userId\":\"1\"");
        assertThat(r.contentUtf8()).contains("\"itemId\":\"42\"");
    }

    @Test
    void invalidQuery_returns400() throws Exception {
        AggregatedHttpResponse r = server.blockingWebClient()
                .post("/v2/recommend", "");

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
