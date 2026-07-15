package com.recsys.application.gateway;

import com.recsys.ratelimit.GatewayRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GatewayProxyService implements HttpService {

    private final MicroserviceRouteTable routeTable;
    private final GatewayRequestForwarder forwarder;
    private final GatewayAuthenticator authenticator;

    public GatewayProxyService(List<MicroserviceRoute> routes,
                               Duration timeout,
                               Map<String, RouteCircuitBreaker> circuitBreakers,
                               GatewayRateLimiter rateLimiter,
                               GatewayAuthenticator authenticator) {
        this(routes, new GatewayRequestForwarder(routes, timeout, circuitBreakers, rateLimiter), authenticator);
    }

    public GatewayProxyService(List<MicroserviceRoute> routes,
                               GatewayRequestForwarder forwarder,
                               GatewayAuthenticator authenticator) {
        this.routeTable = new MicroserviceRouteTable(List.copyOf(routes));
        this.forwarder = Objects.requireNonNull(forwarder, "forwarder");
        this.authenticator = authenticator == null
                ? GatewayAuthenticator.disabled() : authenticator;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        String path = ctx.path();

        GatewayAuthResult auth = authenticator.check(req.headers(), path);
        if (auth.rejected()) return auth.rejection();
        GatewayPrincipal principal = auth.principal();

        MicroserviceRoute route = routeTable.match(path);
        if (route == null) {
            return gatewayError(HttpStatus.NOT_FOUND, "no route found");
        }

        URI target = route.rewrite(path, ctx.query());
        String targetPath = target.getRawPath()
                + (target.getRawQuery() != null ? "?" + target.getRawQuery() : "");

        return HttpResponse.of(req.aggregate().thenApply(aggregated ->
                forwarder.forward(ctx, aggregated, route, targetPath, principal)));
    }

    public static HttpResponse gatewayError(HttpStatus status, String message) {
        String escaped = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        // no-store: without it, CloudFront's default Error Caching Minimum TTL (10s) would pin
        // this response at the edge per cache key/POP — e.g. a 403 from GatewayOriginSecret on
        // /api/catalog/item* would still look broken for 10s after a secret rotation completes.
        ResponseHeaders headers = ResponseHeaders.builder(status)
                .contentType(MediaType.JSON_UTF_8)
                .set(HttpHeaderNames.CACHE_CONTROL, "no-store")
                .build();
        return HttpResponse.of(headers, HttpData.ofUtf8("{\"error\":\"" + escaped + "\"}"));
    }
}
