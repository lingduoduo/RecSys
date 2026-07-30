package com.recsys.api.serving;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimilarCacheHeadersTest {

    static final DataManager mockData = mock(DataManager.class);
    static final EmbeddingStore mockEmb = mock(EmbeddingStore.class);

    static {
        when(mockData.getSimilarMovies(anyInt())).thenReturn(List.of());
        when(mockData.getTopRatedMovies(anyInt())).thenReturn(List.of());
        when(mockData.getMoviesByGenre(any(), anyInt())).thenReturn(List.of());
        when(mockData.getMovieById(anyInt())).thenReturn(null);
        when(mockEmb.getEmbedding(anyInt())).thenReturn(null);
        when(mockEmb.getEmbedding(1)).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(mockEmb.getEmbedding(500)).thenThrow(new RuntimeException("boom"));
        when(mockEmb.getEmbeddings(any())).thenReturn(Map.of());
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/similar", new RecommendationService.Similar(mockEmb, mockData));
        }
    };

    private WebClient client() {
        return WebClient.of(server.httpUri());
    }

    @Test
    void similar_isPubliclyCacheableWithShortTtl() {
        AggregatedHttpResponse res = client().get("/similar?movieId=1&k=5").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo("public, s-maxage=300, stale-while-revalidate=3600, stale-if-error=3600");
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isNotBlank();
    }

    @Test
    void similar_revalidatesTo304() {
        String etag = client().get("/similar?movieId=1&k=5").aggregate().join()
                .headers().get(HttpHeaderNames.ETAG);

        AggregatedHttpResponse res = client().execute(RequestHeaders.of(
                HttpMethod.GET, "/similar?movieId=1&k=5",
                HttpHeaderNames.IF_NONE_MATCH, etag)).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(res.content().isEmpty()).isTrue();
    }

    @Test
    void similar_missingEmbeddingIsNotCacheable() {
        AggregatedHttpResponse res = client().get("/similar?movieId=999").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void similar_badRequestIsNotCacheable() {
        // no-store on every error branch, uniformly. CloudFront would cache this particular
        // 400 only with max-age/s-maxage, so the header is defensive here — but the 404 and
        // 5xx branches depend on it, and one rule per route beats per-status reasoning.
        AggregatedHttpResponse res = client().get("/similar?movieId=abc").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void similar_internalErrorIsNotCacheable() {
        AggregatedHttpResponse res = client().get("/similar?movieId=500").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void similar_outOfRangeKIsRejectedNotClamped() {
        // Was 200 with k clamped to 200. Every k above 200 was a distinct cache key over the
        // k=200 body, and every miss ran a full candidate scan.
        AggregatedHttpResponse res = client().get("/similar?movieId=1&k=201").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void similar_nonPositiveKIsRejected() {
        // Was 200 with k silently reset to the default of 10.
        assertThat(client().get("/similar?movieId=1&k=0").aggregate().join().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(client().get("/similar?movieId=1&k=-1").aggregate().join().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void similar_repeatedMovieIdIsRejected() {
        AggregatedHttpResponse res =
                client().get("/similar?movieId=1&movieId=2").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void similar_leadingZeroMovieIdIsRejected() {
        AggregatedHttpResponse res = client().get("/similar?movieId=01").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void similar_boundaryKIsStillCacheable() {
        // k=200 is the top of the range and must keep serving a cacheable 200 with the
        // unchanged /similar cache-control string.
        AggregatedHttpResponse res = client().get("/similar?movieId=1&k=200").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo("public, s-maxage=300, stale-while-revalidate=3600, stale-if-error=3600");
    }
}
