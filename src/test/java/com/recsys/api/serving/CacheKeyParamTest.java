package com.recsys.api.serving;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the cache-key parameter contract: exactly one canonical spelling per value reaches
 * the origin, so the set of CloudFront cache keys is the set of distinct response bodies.
 * See docs/superpowers/specs/2026-07-29-cdn-cache-key-and-edge-config-hardening-design.md.
 */
class CacheKeyParamTest {

    /** Fixture route with the same shape as the two real cacheable routes. */
    static final class Probe extends BaseApiService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            try {
                int id = cacheKeyIntParam(ctx, "id");
                int k = cacheKeyIntParam(ctx, "k", 10, 1, 200);
                return writeCacheableJson(HttpStatus.OK, Map.of("id", id, "k", k),
                        HttpCaching.publicCache(3600, 86400), req);
            } catch (BadRequestException e) {
                return writeNoStoreError(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/probe", new Probe());
        }
    };

    private AggregatedHttpResponse get(String query) {
        return WebClient.of(server.httpUri()).get("/probe" + query).aggregate().join();
    }

    private void assertRejected(String query) {
        AggregatedHttpResponse res = get(query);
        assertThat(res.status()).as("query %s", query).isEqualTo(HttpStatus.BAD_REQUEST);
        // A rejection must never be cacheable: it is reachable on a cached behavior, and an
        // edge-pinned rejection would outlive the bad request that caused it.
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void canonicalValuesAreAccepted() {
        AggregatedHttpResponse res = get("?id=7&k=5");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).contains("\"id\":7").contains("\"k\":5");
    }

    @Test
    void absentOptionalParamUsesItsDefault() {
        AggregatedHttpResponse res = get("?id=7");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).contains("\"k\":10");
    }

    @Test
    void rangeBoundariesAreAccepted() {
        assertThat(get("?id=7&k=1").status()).isEqualTo(HttpStatus.OK);
        assertThat(get("?id=7&k=200").status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void zeroAndNegativeAreCanonicalWhenInRange() {
        // id has the full int range, so 0 and -5 are valid spellings and must survive: the
        // real /item route answers them with a 404, and changing that to a 400 would be a
        // status change on an unchanged condition for no cache-key benefit.
        assertThat(get("?id=0").status()).isEqualTo(HttpStatus.OK);
        assertThat(get("?id=-5").status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void leadingZeroSpellingsAreRejected() {
        // The unbounded channel: id=7, 07, 007, 0007 ... all parse to 7 under
        // Integer.parseInt, so each is a distinct edge cache key over one identical body.
        assertRejected("?id=07");
        assertRejected("?id=007");
        assertRejected("?id=0000007");
    }

    @Test
    void signAndWhitespaceSpellingsAreRejected() {
        assertRejected("?id=%2B7");   // "+7" — parseInt accepts it, so it aliases 7
        assertRejected("?id=+7");     // "+" arrives as a space or as a literal '+'; both alias 7
        assertRejected("?id=%207");   // " 7"
        assertRejected("?id=7%20");   // "7 "
        assertRejected("?id=-0");     // aliases 0
    }

    @Test
    void outOfRangeValuesAreRejectedNotClamped() {
        // optionalIntParam would clamp these to 200 and 10 respectively, making every value
        // above 200 and every value below 1 a distinct cache key over one body.
        assertRejected("?id=7&k=201");
        assertRejected("?id=7&k=999999");
        assertRejected("?id=7&k=0");
        assertRejected("?id=7&k=-1");
    }

    @Test
    void valuesWiderThanIntAreRejected() {
        assertRejected("?id=99999999999999999999");
    }

    @Test
    void repeatedParametersAreRejected() {
        // CloudFront's cache key includes every occurrence of a whitelisted parameter, while
        // ctx.queryParam reads only the first — so ?id=1&id=<n> is an unbounded family of
        // keys over one body. Value canonicalization alone cannot close this.
        assertRejected("?id=1&id=2");
        assertRejected("?id=7&k=5&k=6");
    }

    @Test
    void presentButEmptyIsRejected() {
        // "?k=" is a third spelling of the default alongside "?k=10" and an absent k.
        assertRejected("?id=7&k=");
        assertRejected("?id=");
    }

    @Test
    void missingRequiredParameterIsRejected() {
        assertRejected("?k=5");
    }
}
