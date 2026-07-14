package com.recsys.api.serving;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaseApiServiceCachingTest {

    /** Fixture route exercising writeCacheableJson through a real server. */
    static final class Cacheable extends BaseApiService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return writeCacheableJson(HttpStatus.OK, Map.of("id", 1),
                    HttpCaching.publicCache(3600, 86400), req);
        }
    }

    /** Fixture route exercising writeNoStoreJson. */
    static final class NoStore extends BaseApiService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return writeNoStoreJson(HttpStatus.OK, Map.of("token", "secret"));
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/cacheable", new Cacheable());
            sb.service("/nostore", new NoStore());
        }
    };

    private WebClient client() {
        return WebClient.of(server.httpUri());
    }

    @Test
    void cacheableJson_setsCacheControlAndEtag() {
        AggregatedHttpResponse res = client().get("/cacheable").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo("public, s-maxage=3600, stale-while-revalidate=86400");
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isNotBlank();
        assertThat(res.contentUtf8()).contains("\"id\":1");
    }

    @Test
    void cacheableJson_returns304WhenIfNoneMatchMatches() {
        String etag = client().get("/cacheable").aggregate().join()
                .headers().get(HttpHeaderNames.ETAG);

        AggregatedHttpResponse res = client().execute(RequestHeaders.of(
                HttpMethod.GET, "/cacheable",
                HttpHeaderNames.IF_NONE_MATCH, etag)).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(res.content().isEmpty()).isTrue();
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isEqualTo(etag);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isNotBlank();
    }

    @Test
    void cacheableJson_returns200WhenIfNoneMatchIsStale() {
        AggregatedHttpResponse res = client().execute(RequestHeaders.of(
                HttpMethod.GET, "/cacheable",
                HttpHeaderNames.IF_NONE_MATCH, "\"stale-etag\"")).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).contains("\"id\":1");
    }

    @Test
    void noStoreJson_setsNoStoreAndOmitsEtag() {
        AggregatedHttpResponse res = client().get("/nostore").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isNull();
    }
}
