package com.recsys.api.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.application.recommendation.RecommendationPipeline;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationV2DegradedHeaderTest {

    @Test
    void setsHeaderWhenDegradedChannelsInTrace() throws Exception {
        RecommendationPipeline pipeline = mock(RecommendationPipeline.class);
        when(pipeline.recommend(any())).thenReturn(new RecommendationResult(
                "1", List.<RankedMovie>of(), null, false,
                Map.of("degradedChannels", "momentum,trending",
                        "degradationOutcome", "all_channels")));

        RecommendationService.V2 v2 = new RecommendationService.V2(pipeline);
        HttpRequest req = HttpRequest.of(
                com.linecorp.armeria.common.RequestHeaders.builder(HttpMethod.POST, "/v2/recommend")
                        .contentType(MediaType.JSON_UTF_8)
                        .build(),
                com.linecorp.armeria.common.HttpData.ofUtf8(
                        "{\"userId\":\"1\",\"limit\":10,\"excludedItemIds\":[],\"cursor\":null}"));
        AggregatedHttpResponse res = v2.serve(ServiceRequestContext.of(req), req).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get("x-recall-degraded")).isEqualTo("momentum,trending");
        assertThat(res.headers().get("x-recall-degradation-reason")).isEqualTo("all_channels");
    }

    @Test
    void noHeaderWhenNoDegradedChannelsInTrace() throws Exception {
        RecommendationPipeline pipeline = mock(RecommendationPipeline.class);
        when(pipeline.recommend(any())).thenReturn(new RecommendationResult(
                "1", List.<RankedMovie>of(), null, false, Map.of("candidateCount", "0")));

        RecommendationService.V2 v2 = new RecommendationService.V2(pipeline);
        HttpRequest req = HttpRequest.of(
                com.linecorp.armeria.common.RequestHeaders.builder(HttpMethod.POST, "/v2/recommend")
                        .contentType(MediaType.JSON_UTF_8)
                        .build(),
                com.linecorp.armeria.common.HttpData.ofUtf8(
                        "{\"userId\":\"1\",\"limit\":10,\"excludedItemIds\":[],\"cursor\":null}"));
        AggregatedHttpResponse res = v2.serve(ServiceRequestContext.of(req), req).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get("x-recall-degraded")).isNull();
        assertThat(res.headers().get("x-recall-degradation-reason")).isNull();
    }
}
