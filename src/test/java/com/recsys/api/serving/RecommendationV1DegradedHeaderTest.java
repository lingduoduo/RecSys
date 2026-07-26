package com.recsys.api.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallResult;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.user.User;
import com.recsys.infrastructure.dataloading.DataManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static com.recsys.application.retrieval.multichannel.RecallResult.DegradationOutcome.*;

class RecommendationV1DegradedHeaderTest {

    @Test
    void setsHeaderWhenChannelsDegraded() throws Exception {
        DataManager dm = mock(DataManager.class);
        when(dm.getUserById(1)).thenReturn(mock(User.class));
        when(dm.getWatchedMovieIds(1)).thenReturn(Set.of());
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        when(recall.recallDetailed(any(), anyInt()))
                .thenReturn(new RecallResult(List.<MovieCandidate>of(), Set.of("trending", "momentum"), ALL_CHANNELS));

        RecommendationService.V1 v1 = new RecommendationService.V1(dm, recall);
        HttpRequest req = HttpRequest.of(HttpMethod.GET, "/recommendation?userId=1&k=5");
        AggregatedHttpResponse res = v1.serve(ServiceRequestContext.of(req), req).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        // Sorted alphabetically by the helper for determinism — see BaseApiService javadoc.
        assertThat(res.headers().get("x-recall-degraded")).isEqualTo("momentum,trending");
        assertThat(res.headers().get("x-recall-degradation-reason")).isEqualTo("all_channels");
    }

    @Test
    void noHeaderWhenNotDegraded() throws Exception {
        DataManager dm = mock(DataManager.class);
        when(dm.getUserById(1)).thenReturn(mock(User.class));
        when(dm.getWatchedMovieIds(1)).thenReturn(Set.of());
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        when(recall.recallDetailed(any(), anyInt()))
                .thenReturn(new RecallResult(List.<MovieCandidate>of(), Set.of()));

        RecommendationService.V1 v1 = new RecommendationService.V1(dm, recall);
        HttpRequest req = HttpRequest.of(HttpMethod.GET, "/recommendation?userId=1&k=5");
        AggregatedHttpResponse res = v1.serve(ServiceRequestContext.of(req), req).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get("x-recall-degraded")).isNull();
        assertThat(res.headers().get("x-recall-degradation-reason")).isNull();
    }
}
