package com.recsys.application.gateway;
import com.recsys.reliability.TokenBucket;
import com.recsys.reliability.GatewayRateLimiter;
import com.recsys.reliability.RouteCircuitBreaker;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class GatewayProxyService implements HttpService {

    private static final int SC_TOO_MANY_REQUESTS = 429;
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "content-length", "expect", "host", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade");

    private final MicroserviceRouteTable routeTable;
    private final Map<String, WebClient> routeClients;
    private final Map<String, RouteCircuitBreaker> circuitBreakers;
    private final GatewayRateLimiter rateLimiter;
    private final GatewayAuthenticator authenticator;

    public GatewayProxyService(List<MicroserviceRoute> routes,
                        Duration timeout,
                        Map<String, RouteCircuitBreaker> circuitBreakers,
                        GatewayRateLimiter rateLimiter,
                        GatewayAuthenticator authenticator) {
        this.routeTable = new MicroserviceRouteTable(List.copyOf(routes));
        this.circuitBreakers = Map.copyOf(circuitBreakers);
        this.rateLimiter = rateLimiter == null ? GatewayRateLimiter.disabled() : rateLimiter;
        this.authenticator = authenticator == null ? GatewayAuthenticator.disabled() : authenticator;

        // One retry on IOException (not on timeout): Cloud Map instance registration/deregistration
        // causes a brief window where DNS resolves to a departing endpoint. A single retry after
        // 50 ms recovers from that transient failure without significantly increasing tail latency.
        RetryRule retryRule = RetryRule.builder()
                .onException((ctx, cause) ->
                        cause instanceof java.io.IOException
                                && !(cause instanceof java.net.SocketTimeoutException))
                .thenBackoff(Backoff.fixed(50));

        Function<? super com.linecorp.armeria.client.HttpClient,
                RetryingClient> retryDecorator =
                RetryingClient.builder(retryRule)
                        .maxTotalAttempts(2)
                        .newDecorator();

        this.routeClients = routes.stream().collect(Collectors.toUnmodifiableMap(
                MicroserviceRoute::name,
                r -> WebClient.builder(r.baseUri().toString())
                        .responseTimeoutMillis(timeout.toMillis())
                        .decorator(retryDecorator)
                        .build()));
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        String path = ctx.path();

        HttpResponse authRejection = authenticator.check(req.headers(), path);
        if (authRejection != null) return authRejection;

        MicroserviceRoute route = routeTable.match(path);
        if (route == null) {
            return gatewayError(HttpStatus.NOT_FOUND, "no route found");
        }

        TokenBucket.Decision rateDecision = rateLimiter.tryAcquire(route.name());
        if (!rateDecision.allowed()) {
            int retryAfter = Math.max(1, (int) Math.ceil(rateDecision.retryAfter().toMillis() / 1000.0));
            return HttpResponse.of(
                    ResponseHeaders.builder(HttpStatus.valueOf(SC_TOO_MANY_REQUESTS))
                            .contentType(MediaType.JSON_UTF_8)
                            .set(HttpHeaderNames.RETRY_AFTER, String.valueOf(retryAfter))
                            .set(HttpHeaderNames.of("x-ratelimit-limit"), String.valueOf(rateDecision.limit()))
                            .set(HttpHeaderNames.of("x-ratelimit-remaining"), String.valueOf(rateDecision.remaining()))
                            .build(),
                    HttpData.ofUtf8("{\"error\":\"" + route.name() + " gateway rate limited\"}"));
        }

        RouteCircuitBreaker cb = circuitBreakers.get(route.name());
        if (cb != null && !cb.tryAcquire()) {
            return gatewayError(HttpStatus.SERVICE_UNAVAILABLE,
                    route.name() + " circuit open — upstream unavailable, retry later");
        }

        URI target = route.rewrite(path, ctx.query());
        String targetPath = target.getRawPath()
                + (target.getRawQuery() != null ? "?" + target.getRawQuery() : "");

        WebClient client = routeClients.get(route.name());

        // Aggregate the incoming request body once so we can forward it.
        // The RetryingClient decorator also needs a buffered body for retry attempts.
        return HttpResponse.of(
                req.aggregate().thenCompose(aggReq -> {
                    RequestHeaders upstreamHeaders = buildUpstreamHeaders(aggReq.headers(), targetPath, ctx);
                    HttpRequest upstreamReq = HttpRequest.of(upstreamHeaders, aggReq.content());
                    HttpResponse upstream = client.execute(upstreamReq);
                    return upstream.aggregate()
                            .thenApply(aggResp -> {
                                if (cb != null) {
                                    if (aggResp.status().isServerError()) cb.recordFailure();
                                    else cb.recordSuccess();
                                }
                                return aggResp.toHttpResponse();
                            })
                            .exceptionally(t -> {
                                if (cb != null) cb.recordFailure();
                                return gatewayError(HttpStatus.BAD_GATEWAY, "upstream unreachable");
                            });
                }));
    }

    private static RequestHeaders buildUpstreamHeaders(RequestHeaders incoming, String targetPath,
                                                       ServiceRequestContext ctx) {
        RequestHeadersBuilder b = RequestHeaders.builder(incoming.method(), targetPath);
        incoming.forEach((name, value) -> {
            if (!isHopByHop(name.toString())) b.add(name, value);
        });
        b.set(HttpHeaderNames.of("x-gateway-service"), "recsys-api-gateway");
        String host = incoming.get(HttpHeaderNames.HOST);
        if (host != null && !host.isBlank()) {
            b.set(HttpHeaderNames.of("x-forwarded-host"), host);
        }
        b.set(HttpHeaderNames.of("x-forwarded-proto"), "http");
        if (ctx.remoteAddress() != null) {
            String clientIp = ctx.remoteAddress().getAddress().getHostAddress();
            String existing = incoming.get(HttpHeaderNames.of("x-forwarded-for"));
            String newValue = (existing != null && !existing.isBlank())
                    ? existing + ", " + clientIp : clientIp;
            b.set(HttpHeaderNames.of("x-forwarded-for"), newValue);
        }
        return b.build();
    }

    public static HttpResponse gatewayError(HttpStatus status, String message) {
        String escaped = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        return HttpResponse.of(status, MediaType.JSON_UTF_8, "{\"error\":\"" + escaped + "\"}");
    }

    private static boolean isHopByHop(String name) {
        return name != null && HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }
}
