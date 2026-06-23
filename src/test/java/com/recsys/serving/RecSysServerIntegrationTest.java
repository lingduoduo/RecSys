package com.recsys.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.infrastructure.PairPredictionService;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.domain.item.Movie;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecSysServerIntegrationTest {

    static final DataManager mockData = mock(DataManager.class);
    static final EmbeddingStore mockEmb = mock(EmbeddingStore.class);
    static final EmbeddingStore mockUserEmb = mock(EmbeddingStore.class);
    static final PairPredictionService mockPrediction = mock(PairPredictionService.class);

    static {
        when(mockData.getUserById(anyInt())).thenReturn(new com.recsys.domain.user.User(1, "Alice"));
        when(mockData.getUserById(999)).thenReturn(null);
        when(mockData.getMovieById(anyInt())).thenReturn(null);
        when(mockData.getMovieById(1)).thenReturn(new Movie(1, "Test Movie", 2020, List.of("Action")));
        when(mockData.getSimilarMovies(anyInt())).thenReturn(List.of());
        when(mockData.getTopRatedMovies(anyInt())).thenReturn(List.of());
        when(mockData.getMoviesByGenre(any(), anyInt())).thenReturn(List.of());
        when(mockEmb.getEmbedding(1)).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(mockEmb.getEmbedding(999)).thenReturn(null);
        when(mockEmb.getEmbeddings(any())).thenReturn(java.util.Map.of());
        when(mockPrediction.predict(any())).thenReturn(List.of());
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            CandidateGenerator cg = mock(CandidateGenerator.class);
            when(cg.byUserHistory(anyInt(), anyInt())).thenReturn(List.of());
            when(cg.byEmbedding(anyInt(), anyInt())).thenReturn(List.of());
            when(cg.byGenre(any(), anyInt())).thenReturn(List.of());
            // Mirror the real CandidateGenerator: reject non-6-dim vectors so we can assert
            // EmbeddingService.SetMovie maps the IllegalArgumentException to 400 (not 500).
            org.mockito.Mockito.doThrow(
                            new IllegalArgumentException("vector dimension mismatch: expected 6, got 3"))
                    .when(cg).updateEmbedding(anyInt(), org.mockito.ArgumentMatchers.argThat(
                            v -> v != null && v.length != 6));

            MultiChannelRecallService recallService = mock(MultiChannelRecallService.class);
            when(recallService.recall(any(), anyInt())).thenReturn(List.of());

            CatalogService.Movies movie = new CatalogService.Movies(mockData);
            CatalogService.Users user = new CatalogService.Users(mockData);
            RecommendationService.V1 rec = new RecommendationService.V1(mockData, recallService);

            sb.service("/item", movie)
              .service("/movie", movie)
              .service("/getuser", user)
              .service("/user", user)
              .service("/similar", new RecommendationService.Similar(mockEmb, mockData))
              .service("/getrecommendation", rec)
              .service("/recommendation", rec)
              .service("/setembedding", new EmbeddingService.SetMovie(mockEmb, cg))
              .service("/setuserembedding", new EmbeddingService.SetUser(mockUserEmb))
              .service("/health", new RecommendationService.Health())
              .service(Route.builder()
                               .regex("^/v1/models/recmodel:predict$")
                               .methods(com.linecorp.armeria.common.HttpMethod.POST)
                               .build(),
                       new PredictionService(mockPrediction));
        }
    };

    @Test void healthReturns200() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/health");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("ok");
    }

    @Test void getMovieById() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/item?id=1");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("Test Movie");
    }

    @Test void getMovieNotFound() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/item?id=999");
        assertThat(r.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.contentUtf8()).contains("error");
    }

    @Test void getMovieMissingId() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/item");
        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void getUserById() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/getuser?userId=1");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
    }

    @Test void getUserNotFound() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/getuser?userId=999");
        assertThat(r.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void getRecommendation() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/getrecommendation?userId=1&k=5");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
    }

    @Test void getRecommendationMissingUserId() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/getrecommendation");
        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void similarMoviesNotFound() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/similar?movieId=999&k=3");
        assertThat(r.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void similarMoviesFound() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/similar?movieId=1&k=3");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
    }

    @Test void setEmbeddingEmptyBody() {
        AggregatedHttpResponse r = server.blockingWebClient().post("/setembedding?movieId=1", "");
        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void setEmbeddingDimensionMismatchReturns400() {
        AggregatedHttpResponse r = server.blockingWebClient().post("/setembedding?movieId=1", "0.1 0.3 0.6");
        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.contentUtf8()).contains("dimension mismatch");
    }

    @Test void setEmbeddingValidDimensionReturns200() {
        AggregatedHttpResponse r = server.blockingWebClient().post(
                "/setembedding?movieId=1", "0.1 0.3 0.6 0.0 0.0 0.0");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"ok\":true");
    }

    @Test void restAliasMovie() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/movie?id=1");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
    }

    @Test void predictReturns200() {
        when(mockPrediction.predict(any())).thenReturn(List.of(List.of(0.9)));
        AggregatedHttpResponse r = server.blockingWebClient().post(
                "/v1/models/recmodel:predict",
                "{\"instances\":[{\"userId\":1,\"movieId\":1}]}");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("predictions");
    }

    @Test void predictEmptyInstancesReturns400() {
        AggregatedHttpResponse r = server.blockingWebClient().post(
                "/v1/models/recmodel:predict",
                "{\"instances\":[]}");
        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void predictEmptyBodyReturns400() {
        AggregatedHttpResponse r = server.blockingWebClient().post(
                "/v1/models/recmodel:predict", "");
        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void setUserEmbeddingHappyPath() {
        AggregatedHttpResponse r = server.blockingWebClient().post(
                "/setuserembedding?userId=1", "0.1 0.2 0.3");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"ok\":true");
        assertThat(r.contentUtf8()).contains("\"userId\":1");
    }

    @Test void setUserEmbeddingEmptyBody() {
        AggregatedHttpResponse r = server.blockingWebClient().post("/setuserembedding?userId=1", "");
        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
