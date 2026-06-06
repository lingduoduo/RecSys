package com.recsys.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.PairPredictionService;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.model.Movie;
import com.recsys.streaming.TrendingStore;
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
    static final TrendingStore mockTopk = mock(TrendingStore.class);
    static final PairPredictionService mockPrediction = mock(PairPredictionService.class);

    static {
        when(mockData.getUserById(anyInt())).thenReturn(new com.recsys.model.User(1, "Alice"));
        when(mockData.getUserById(999)).thenReturn(null);
        when(mockData.getMovieById(anyInt())).thenReturn(null);
        when(mockData.getMovieById(1)).thenReturn(new Movie(1, "Test Movie", 2020, List.of("Action")));
        when(mockData.getSimilarMovies(anyInt())).thenReturn(List.of());
        when(mockData.getTopRatedMovies(anyInt())).thenReturn(List.of());
        when(mockData.getMoviesByGenre(any(), anyInt())).thenReturn(List.of());
        when(mockEmb.getEmbedding(1)).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(mockEmb.getEmbedding(999)).thenReturn(null);
        when(mockEmb.getEmbeddings(any())).thenReturn(java.util.Map.of());
        when(mockTopk.getTopKIds(any(), anyInt())).thenReturn(List.of("1", "2"));
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

            MovieService movie = new MovieService(mockData);
            UserService user = new UserService(mockData);
            RecommendationService rec = new RecommendationService(mockData, cg, mockTopk);

            sb.service("/item", movie)
              .service("/movie", movie)
              .service("/getuser", user)
              .service("/user", user)
              .service("/similar", new SimilarMovieService(mockEmb, mockData))
              .service("/getrecommendation", rec)
              .service("/recommendation", rec)
              .service("/setembedding", new SetEmbeddingService(mockEmb, cg))
              .service("/health", new HealthService())
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
}
