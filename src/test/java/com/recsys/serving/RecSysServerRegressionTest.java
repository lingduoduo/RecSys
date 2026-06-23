package com.recsys.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.domain.item.Movie;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests that verify pre-existing RecSysServer routes continue
 * to function correctly after the /v2/recommend route was added.
 */
class RecSysServerRegressionTest {

    static final DataManager mockData = mock(DataManager.class);
    static final MultiChannelRecallService mockRecall = mock(MultiChannelRecallService.class);

    static {
        when(mockData.getUserById(anyInt()))
                .thenReturn(new com.recsys.domain.user.User(1, "Alice"));
        when(mockData.getUserById(999)).thenReturn(null);
        when(mockData.getMovieById(anyInt()))
                .thenReturn(new Movie(1, "Test Movie", 2020, List.of("Action")));
        when(mockData.getWatchedMovieIds(anyInt())).thenReturn(java.util.Set.of());
        when(mockData.getTopRatedMovies(anyInt())).thenReturn(List.of());
        when(mockData.getMoviesByGenre(any(), anyInt())).thenReturn(List.of());
        when(mockRecall.recall(any(), anyInt())).thenReturn(List.of());
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            RecommendationService.V1 rec = new RecommendationService.V1(mockData, mockRecall);

            sb.service("/getrecommendation", rec)
              .service("/recommendation", rec);
        }
    };

    @Test
    void getRecommendation_stillReturns200WithUserAndMoviesKeys() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .get("/getrecommendation?userId=1&k=5");

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("user");
        assertThat(r.contentUtf8()).contains("recommendations");
    }

    @Test
    void recommendationAlias_stillReturns200() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .get("/recommendation?userId=1&k=5");

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
    }
}
