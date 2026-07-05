package com.recsys.application.gateway;

import com.recsys.infrastructure.cache.LlmResponseCache;
import com.recsys.ratelimit.LlmTokenRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProxyServiceWarmupTest {

    // Records the paths the upstream is asked for so we can assert the warmup hit the health path.
    static final List<String> hits = new CopyOnWriteArrayList<>();

    @RegisterExtension
    static final ServerExtension upstream = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("prefix:/", (ctx, req) -> {
                hits.add(ctx.path());
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{\"ok\":true}");
            });
        }
    };

    private static LlmProxyService proxy(String baseUri, String healthPath, ClientFactory factory) {
        MicroserviceRoute route = new MicroserviceRoute(
                "llm", "/api/llm", "UNUSED", URI.create(baseUri), healthPath);
        return new LlmProxyService(
                route, Duration.ofSeconds(5),
                new RouteCircuitBreaker(3, 5000),
                LlmTokenRateLimiter.disabled(),
                LlmResponseCache.disabled(),
                1000, 30_000,
                GatewayAuthenticator.disabled(),
                factory);
    }

    @Test
    void warmUp_success_hitsHealthPathExactlyOnce() {
        hits.clear();
        LlmProxyService svc = proxy("http://127.0.0.1:" + upstream.httpPort(), "/api/tags",
                ClientFactory.ofDefault());

        svc.warmUp().join();

        assertThat(hits).containsExactly("/api/tags");
    }

    @Test
    void warmUp_deadUpstream_completesWithoutThrowing() {
        // Port 1 is not listening; the warmup must swallow the failure and complete normally.
        LlmProxyService svc = proxy("http://127.0.0.1:1", "/api/tags", ClientFactory.ofDefault());

        // join() would throw if warmUp() completed exceptionally.
        svc.warmUp().join();
    }

    @Test
    void warmUp_shortConnectTimeout_failsFastWellUnderResponseTimeout() {
        // 240.0.0.1 is reserved and never routable, so connect never completes; a 100ms
        // connectTimeout guarantees the future resolves quickly despite the 5s response timeout.
        ClientFactory factory = ClientFactory.builder()
                .connectTimeout(Duration.ofMillis(100))
                .build();
        try {
            LlmProxyService svc = proxy("http://240.0.0.1:80", "/api/tags", factory);
            long start = System.currentTimeMillis();
            svc.warmUp().join(); // handled internally -> completes normally
            long elapsedMs = System.currentTimeMillis() - start;
            assertThat(elapsedMs).isLessThan(5_000L);
        } finally {
            factory.close();
        }
    }
}
