package com.recsys.api.serving;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.domain.item.Movie;
import com.recsys.domain.user.User;
import com.recsys.infrastructure.dataloading.DataManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogCacheHeadersTest {

    static final DataManager mockData = mock(DataManager.class);

    static {
        when(mockData.getMovieById(anyInt())).thenReturn(null);
        when(mockData.getMovieById(1)).thenReturn(new Movie(1, "Test Movie", 2020, List.of("Action")));
        when(mockData.getUserById(anyInt())).thenReturn(new User(1, "Alice"));
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/item", new CatalogService.Movies(mockData));
            sb.service("/getuser", new CatalogService.Users(mockData));
        }
    };

    private WebClient client() {
        return WebClient.of(server.httpUri());
    }

    @Test
    void item_isPubliclyCacheableWithEtag() {
        AggregatedHttpResponse res = client().get("/item?id=1").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo("public, s-maxage=3600, stale-while-revalidate=86400, stale-if-error=86400");
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isNotBlank();
    }

    @Test
    void item_revalidatesTo304() {
        String etag = client().get("/item?id=1").aggregate().join()
                .headers().get(HttpHeaderNames.ETAG);

        AggregatedHttpResponse res = client().execute(RequestHeaders.of(
                HttpMethod.GET, "/item?id=1",
                HttpHeaderNames.IF_NONE_MATCH, etag)).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(res.content().isEmpty()).isTrue();
    }

    @Test
    void item_notFoundIsNotCacheable() {
        AggregatedHttpResponse res = client().get("/item?id=999").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void getuser_isNeverCacheable() {
        AggregatedHttpResponse res = client().get("/getuser?userId=1").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isNull();
    }
}
