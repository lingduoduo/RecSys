package com.recsys.api.serving;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real docker/cdn/default.conf.template against a stub origin, proving the local CDN
 * actually mirrors the CloudFront behaviors rather than merely looking like it does.
 *
 * <p>Tagged docker: excluded from `mvn test` by default (pom.xml). Run with
 * `mvn test -DexcludedGroups=load -Dgroups=docker -Dtest=LocalCdnCacheTest`.
 */
@Tag("docker")
class LocalCdnCacheTest {

    private static final String SECRET = "local-test-secret";

    /** Origin hits that actually reached the stub — a cache HIT must not increment this. */
    static final AtomicInteger originHits = new AtomicInteger();
    /** Every x-origin-secret value the stub received. */
    static final List<String> receivedSecrets = new CopyOnWriteArrayList<>();
    /** Origin hits on /api/catalog/similar specifically — separate from the /item counter above. */
    static final AtomicInteger similarOriginHits = new AtomicInteger();

    static Server origin;
    static GenericContainer<?> nginx;
    static String etag;

    @BeforeAll
    static void startAll() {
        origin = Server.builder()
                .http(0)
                // Emits ONLY s-maxage — no max-age, no Expires. Combined with the template's
                // deliberate lack of proxy_cache_valid, a HIT is only possible if nginx honours
                // s-maxage. That is the whole point of this fixture.
                .service("/api/catalog/item", (ctx, req) -> {
                    originHits.incrementAndGet();
                    String secret = req.headers().get(HttpHeaderNames.of("x-origin-secret"));
                    receivedSecrets.add(secret == null ? "<absent>" : secret);
                    byte[] body = "{\"id\":1,\"title\":\"Test Movie\"}".getBytes();
                    String tag = HttpCaching.etagFor(body);
                    if (HttpCaching.matches(req.headers().get(HttpHeaderNames.IF_NONE_MATCH), tag)) {
                        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.NOT_MODIFIED)
                                .set(HttpHeaderNames.CACHE_CONTROL, HttpCaching.publicCache(3600, 86400))
                                .set(HttpHeaderNames.ETAG, tag)
                                .build());
                    }
                    return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                            .contentType(MediaType.JSON_UTF_8)
                            .set(HttpHeaderNames.CACHE_CONTROL, HttpCaching.publicCache(3600, 86400))
                            .set(HttpHeaderNames.ETAG, tag)
                            .build(), HttpData.wrap(body));
                })
                // Mirrors the /api/catalog/item handler's shape, but keyed on movieId+k so the
                // more complex two-parameter cache key ($uri|$arg_movieId|$arg_k) is exercised.
                .service("/api/catalog/similar", (ctx, req) -> {
                    similarOriginHits.incrementAndGet();
                    String secret = req.headers().get(HttpHeaderNames.of("x-origin-secret"));
                    receivedSecrets.add(secret == null ? "<absent>" : secret);
                    String movieId = ctx.queryParam("movieId");
                    String k = ctx.queryParam("k");
                    byte[] body = ("{\"movieId\":" + movieId + ",\"k\":" + k + ",\"neighbors\":[]}")
                            .getBytes();
                    String tag = HttpCaching.etagFor(body);
                    if (HttpCaching.matches(req.headers().get(HttpHeaderNames.IF_NONE_MATCH), tag)) {
                        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.NOT_MODIFIED)
                                .set(HttpHeaderNames.CACHE_CONTROL, HttpCaching.publicCache(300, 3600))
                                .set(HttpHeaderNames.ETAG, tag)
                                .build());
                    }
                    return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                            .contentType(MediaType.JSON_UTF_8)
                            .set(HttpHeaderNames.CACHE_CONTROL, HttpCaching.publicCache(300, 3600))
                            .set(HttpHeaderNames.ETAG, tag)
                            .build(), HttpData.wrap(body));
                })
                .service("/api/recommend", (ctx, req) -> {
                    originHits.incrementAndGet();
                    String secret = req.headers().get(HttpHeaderNames.of("x-origin-secret"));
                    receivedSecrets.add(secret == null ? "<absent>" : secret);
                    return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                            .contentType(MediaType.JSON_UTF_8)
                            .set(HttpHeaderNames.CACHE_CONTROL, "no-store")
                            .build(), HttpData.ofUtf8("{\"personalized\":true}"));
                })
                .build();
        origin.start().join();
        int originPort = origin.activeLocalPort();
        etag = HttpCaching.etagFor("{\"id\":1,\"title\":\"Test Movie\"}".getBytes());

        Testcontainers.exposeHostPorts(originPort);
        nginx = new GenericContainer<>("nginx:1.27-alpine")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("docker/cdn/default.conf.template"),
                        "/etc/nginx/templates/default.conf.template")
                .withEnv("NGINX_ENVSUBST_FILTER", "CDN_")
                .withEnv("CDN_ORIGIN_HOST", "host.testcontainers.internal")
                .withEnv("CDN_ORIGIN_PORT", String.valueOf(originPort))
                .withEnv("CDN_ORIGIN_SECRET", SECRET)
                .withExposedPorts(8090)
                .waitingFor(Wait.forListeningPort());
        nginx.start();
    }

    @AfterAll
    static void stopAll() {
        if (nginx != null) nginx.stop();
        if (origin != null) origin.stop().join();
    }

    private WebClient cdn() {
        return WebClient.of("http://" + nginx.getHost() + ":" + nginx.getMappedPort(8090));
    }

    private static String cacheStatus(AggregatedHttpResponse res) {
        return res.headers().get(HttpHeaderNames.of("x-cache"));
    }

    @Test
    void sMaxAgeAloneIsEnoughToCache_missThenHit() {
        int before = originHits.get();

        AggregatedHttpResponse first = cdn().get("/api/catalog/item?id=1").aggregate().join();
        assertThat(first.status()).isEqualTo(HttpStatus.OK);
        assertThat(cacheStatus(first)).isEqualTo("MISS");

        AggregatedHttpResponse second = cdn().get("/api/catalog/item?id=1").aggregate().join();
        assertThat(second.status()).isEqualTo(HttpStatus.OK);
        // The load-bearing assertion. The origin sends only `s-maxage` and the nginx config has
        // no proxy_cache_valid, so a HIT is possible ONLY if nginx honours s-maxage.
        assertThat(cacheStatus(second)).isEqualTo("HIT");
        // And the origin was hit exactly once, proving the HIT was served from cache.
        assertThat(originHits.get()).isEqualTo(before + 1);
    }

    @Test
    void cacheKeyWhitelistMeansCachebusterCannotFragmentTheCache() {
        cdn().get("/api/catalog/item?id=7").aggregate().join();   // prime
        int before = originHits.get();

        AggregatedHttpResponse busted =
                cdn().get("/api/catalog/item?id=7&cachebuster=99").aggregate().join();

        // The key is "$uri|$arg_id", so cachebuster is not part of it.
        assertThat(cacheStatus(busted)).isEqualTo("HIT");
        assertThat(originHits.get()).isEqualTo(before);
    }

    @Test
    void defaultBehaviorNeverCaches() {
        int before = originHits.get();

        AggregatedHttpResponse first = cdn().post("/api/recommend", "{}").aggregate().join();
        AggregatedHttpResponse second = cdn().post("/api/recommend", "{}").aggregate().join();

        assertThat(cacheStatus(first)).isEqualTo("BYPASS");
        assertThat(cacheStatus(second)).isEqualTo("BYPASS");
        // Every request reached the origin.
        assertThat(originHits.get()).isEqualTo(before + 2);
    }

    @Test
    void ifNoneMatchReturns304ThroughTheCdn() {
        cdn().get("/api/catalog/item?id=3").aggregate().join();   // prime

        AggregatedHttpResponse res = cdn().execute(RequestHeaders.of(
                HttpMethod.GET, "/api/catalog/item?id=3",
                HttpHeaderNames.IF_NONE_MATCH, etag)).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        // Observed (not guessed): nginx served this 304 from its own cached copy — it found a
        // fresh cached response, matched the client's If-None-Match against the cached ETag
        // itself, and answered without a round trip to the origin. That is a cache HIT, not a
        // proxy-through to the origin, so $upstream_cache_status is HIT here (not MISS/BYPASS).
        assertThat(cacheStatus(res)).isEqualTo("HIT");
    }

    // NOTE on test isolation: each test above uses a disjoint `id` (1, 7, 3, 42) against the
    // shared nginx cache so MISS/HIT assertions in one test cannot be perturbed by another test
    // priming or busting the same cache entry. Nothing enforces this today — a future test that
    // reuses one of these ids would produce a confusing, order-dependent failure.
    @Test
    void originSecretIsInjectedOnEveryForwardedRequest() {
        receivedSecrets.clear();
        cdn().get("/api/catalog/item?id=42").aggregate().join();

        assertThat(receivedSecrets).isNotEmpty();
        assertThat(receivedSecrets).allMatch(SECRET::equals);

        // The cached-location assertion above cannot prove the header is injected from the
        // shared upstream{} block config rather than something specific to that location, since
        // location = /api/catalog/item and location = /api/catalog/similar both set it
        // explicitly. Drive a request through the DEFAULT (location /) block too — POST
        // /api/recommend never matches the two named locations — and check the secret arrives
        // there as well.
        receivedSecrets.clear();
        cdn().post("/api/recommend", "{}").aggregate().join();

        assertThat(receivedSecrets).isNotEmpty();
        assertThat(receivedSecrets).allMatch(SECRET::equals);
    }

    // Uses movieId=55, disjoint from the /item tests' ids (1, 7, 3, 42) above — same
    // order-independence rationale, even though /item and /similar are different locations
    // with different $uri-prefixed cache keys and so could not collide regardless.
    @Test
    void similarCacheKeyIncludesK_missThenHit() {
        int before = similarOriginHits.get();

        AggregatedHttpResponse first =
                cdn().get("/api/catalog/similar?movieId=55&k=5").aggregate().join();
        assertThat(first.status()).isEqualTo(HttpStatus.OK);
        assertThat(cacheStatus(first)).isEqualTo("MISS");

        AggregatedHttpResponse second =
                cdn().get("/api/catalog/similar?movieId=55&k=5").aggregate().join();
        assertThat(second.status()).isEqualTo(HttpStatus.OK);
        assertThat(cacheStatus(second)).isEqualTo("HIT");
        assertThat(similarOriginHits.get()).isEqualTo(before + 1);

        // k IS part of the cache key ("$uri|$arg_movieId|$arg_k"), so a differing k must be a
        // separate cache entry — a fresh MISS that reaches the origin again, not a HIT reusing
        // the k=5 entry.
        AggregatedHttpResponse differentK =
                cdn().get("/api/catalog/similar?movieId=55&k=6").aggregate().join();
        assertThat(differentK.status()).isEqualTo(HttpStatus.OK);
        assertThat(cacheStatus(differentK)).isEqualTo("MISS");
        assertThat(similarOriginHits.get()).isEqualTo(before + 2);
    }
}
