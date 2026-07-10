package com.recsys.application.gateway;

import com.recsys.ratelimit.GatewayRateLimiter;
import com.recsys.ratelimit.TokenBucket;
import com.recsys.resilience.RouteCircuitBreaker;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class GatewayRequestForwarder {

    private static final int SC_TOO_MANY_REQUESTS = 429;
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "content-length", "expect", "host", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade");

    // Credentials the gateway consumes at its auth boundary — never forwarded upstream.
    private static final Set<String> GATEWAY_CONSUMED_CREDENTIALS = Set.of("authorization", "x-api-key");

    private final Map<String, WebClient> routeClients;
    private final Map<String, RouteCircuitBreaker> circuitBreakers;
    private final GatewayRateLimiter rateLimiter;

    public GatewayRequestForwarder(List<MicroserviceRoute> routes,
                                   Duration timeout,
                                   Map<String, RouteCircuitBreaker> circuitBreakers,
                                   GatewayRateLimiter rateLimiter) {
        this.circuitBreakers = Map.copyOf(circuitBreakers);
        this.rateLimiter = rateLimiter == null ? GatewayRateLimiter.disabled() : rateLimiter;

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

    HttpResponse forward(ServiceRequestContext ctx,
                         AggregatedHttpRequest request,
                         MicroserviceRoute route,
                         String targetPath,
                         GatewayPrincipal principal) {
        TokenBucket.Decision rateDecision = rateLimiter.tryAcquire(route.name(), principal.rateLimitKey());
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
            return GatewayProxyService.gatewayError(HttpStatus.SERVICE_UNAVAILABLE,
                    route.name() + " circuit open — upstream unavailable, retry later");
        }

        WebClient client = routeClients.get(route.name());
        RequestHeaders upstreamHeaders = buildUpstreamHeaders(request.headers(), targetPath, ctx, principal);
        HttpRequest upstreamReq = HttpRequest.of(upstreamHeaders, request.content());
        HttpResponse upstream = client.execute(upstreamReq);
        return HttpResponse.of(upstream.aggregate()
                .thenApply(aggResp -> {
                    if (cb != null) {
                        if (aggResp.status().isServerError()) cb.recordFailure();
                        else cb.recordSuccess();
                    }
                    return aggResp.toHttpResponse();
                })
                .exceptionally(t -> {
                    if (cb != null) cb.recordFailure();
                    return GatewayProxyService.gatewayError(HttpStatus.BAD_GATEWAY, "upstream unreachable");
                }));
    }

    static RequestHeaders buildUpstreamHeaders(RequestHeaders incoming, String targetPath,
                                               ServiceRequestContext ctx, GatewayPrincipal principal) {
        RequestHeadersBuilder b = RequestHeaders.builder(incoming.method(), targetPath);
        incoming.forEach((name, value) -> {
            String n = name.toString();
            // Strip any client-supplied identity header — the gateway is the sole authority.
            if (!isHopByHop(n) && !isGatewayCredential(n)
                    && !n.regionMatches(true, 0, "x-authenticated-", 0, 16)) {
                b.add(name, value);
            }
        });
        principal.identityHeaders().forEach((hn, hv) -> b.set(HttpHeaderNames.of(hn), hv));
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

    private static boolean isHopByHop(String name) {
        return name != null && HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean isGatewayCredential(String name) {
        return name != null && GATEWAY_CONSUMED_CREDENTIALS.contains(name.toLowerCase(Locale.ROOT));
    }
}
