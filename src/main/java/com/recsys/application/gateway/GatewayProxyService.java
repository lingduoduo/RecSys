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
        // Normalize BEFORE authorization. GATEWAY_PUBLIC_PATHS and PROTECTED_PREFIXES are matched
        // against version-free paths, so a caller must not be able to reach a protected route by
        // adding a version segment. Every consumer below sees the normalized path.
        ApiVersion apiVersion = ApiVersion.parse(ctx.path());
        if (!apiVersion.supported()) {
            return gatewayError(HttpStatus.BAD_REQUEST, apiVersion.unsupportedMessage());
        }
        String path = apiVersion.path();

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
        // no-store is load-bearing for the 502/503 this helper returns: both are on
        // CloudFront's unconditionally-cached list, so on one of the four cached catalog
        // behaviors a circuit-open 503 would otherwise be pinned at the edge for the 10 s Error
        // Caching Minimum TTL and served to every viewer at that POP. For its 400, 403 and 404
        // callers — including GatewayOriginSecret's 403 during a secret rotation — it is
        // defensive: CloudFront caches those only with max-age/s-maxage, which this response
        // never sends. Depends on both cache policies keeping MinTTL: 0.
        ResponseHeaders headers = ResponseHeaders.builder(status)
                .contentType(MediaType.JSON_UTF_8)
                .set(HttpHeaderNames.CACHE_CONTROL, "no-store")
                .build();
        return HttpResponse.of(headers, HttpData.ofUtf8("{\"error\":\"" + escaped + "\"}"));
    }
}
