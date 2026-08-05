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
        HttpResponse nonCanonical = rejectNonCanonicalPath(path);
        if (nonCanonical != null) {
            return nonCanonical;
        }

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

    /**
     * Rejects a request path carrying a {@code .} or {@code ..} segment, with {@code 400}.
     *
     * <p>Armeria and Tomcat disagree about {@code .}: Armeria preserves the segment (it rejects
     * only {@code ..}), Tomcat collapses it. Every gateway control that keys on the path — route
     * matching, {@code GATEWAY_PUBLIC_PATHS}/{@code PROTECTED_PREFIXES}, the {@link UserScopedRoutes}
     * lookup, rate-limit keying, and the CloudFront cache key — therefore sees a different path
     * than the 8080 handler does. Concretely, {@code /api/model/api/v1/./recommend} misses the
     * user-scope table (matching there is exact, deliberately) and still reaches the Spring handler
     * for {@code /api/v1/recommend}, so a user-tier caller could name any userId.
     *
     * <p>Rejecting rather than normalizing is the point. Canonicalizing the path here would fix the
     * lookup while leaving route matching, rate-limit keying, and the edge cache key on whatever
     * spelling the client chose; and canonicalizing inside the lookup alone would fix none of the
     * others. No legitimate client emits a dot segment — every route the gateway publishes is a
     * literal path — so the family is simply not accepted. This is the one control that rejects
     * for service-tier callers too: it is a malformed-request rejection, not an authorization
     * decision.
     *
     * @return the 400 to return, or null when the path is canonical
     */
    static HttpResponse rejectNonCanonicalPath(String path) {
        if (hasEncodedSeparator(path)) {
            return gatewayError(HttpStatus.BAD_REQUEST,
                    "bad request: percent-encoded \"/\" is not allowed in the path");
        }
        if (!hasNonCanonicalSegment(path)) {
            return null;
        }
        return gatewayError(HttpStatus.BAD_REQUEST,
                "bad request: path segment \".\" or \"..\" is not allowed");
    }

    /**
     * True when the path carries a percent-encoded separator ({@code %2F}).
     *
     * <p>The second spelling of the same gateway/backend disagreement the dot guard exists for.
     * Armeria decodes unreserved characters but deliberately leaves {@code %2F} encoded in
     * {@code ctx.path()}, so {@code /api/model/api/v1/.%2Frecommend} is <em>one</em> segment to
     * every control here — route matching, the public-path check, the {@link UserScopedRoutes}
     * lookup — and two to any backend that decodes it. That is exactly the bypass shape: the
     * gateway authorizes one path and the backend serves another.
     *
     * <p>It is inert today only because Tomcat rejects an encoded solidus by default. That is a
     * setting (<code>encodedSolidusHandling</code>), not a guarantee, and this control should not
     * depend on a downstream default staying put. No route the gateway publishes takes a path
     * segment containing a literal {@code /}, so the encoding has no legitimate use here.
     */
    static boolean hasEncodedSeparator(String path) {
        if (path == null || path.indexOf('%') < 0) {
            return false;
        }
        for (int i = 0; i + 2 < path.length(); i++) {
            if (path.charAt(i) == '%'
                    && path.charAt(i + 1) == '2'
                    && (path.charAt(i + 2) == 'F' || path.charAt(i + 2) == 'f')) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when any {@code /}-delimited segment of {@code path} is {@code .} or {@code ..}.
     *
     * <p>{@code %2E} is decoded first: Armeria percent-decodes unreserved characters before
     * {@code ctx.path()} is read, but decoding again here costs nothing and removes the dependency
     * on that behaviour. A dot <em>inside</em> a segment ({@code movie.json}, {@code 1.2.3},
     * {@code ..foo}) is an ordinary character and is left alone.
     */
    static boolean hasNonCanonicalSegment(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String decoded = path.indexOf('%') < 0
                ? path
                : path.replace("%2E", ".").replace("%2e", ".");
        int cursor = 0;
        int length = decoded.length();
        while (cursor <= length) {
            int end = decoded.indexOf('/', cursor);
            if (end < 0) {
                end = length;
            }
            int size = end - cursor;
            if ((size == 1 && decoded.charAt(cursor) == '.')
                    || (size == 2 && decoded.charAt(cursor) == '.' && decoded.charAt(cursor + 1) == '.')) {
                return true;
            }
            cursor = end + 1;
        }
        return false;
    }

    public static HttpResponse gatewayError(HttpStatus status, String message) {
        String escaped = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        // no-store is load-bearing for the 404/502/503 this helper returns: all three are on
        // CloudFront's unconditionally-cached list, so on one of the four cached catalog
        // behaviors a missing route or a circuit-open 503 would otherwise be pinned at the edge
        // for the 10 s Error Caching Minimum TTL and served to every viewer at that POP. For its
        // 400 and 403 callers — including GatewayOriginSecret's 403 during a secret rotation —
        // it is defensive: CloudFront caches those only with max-age/s-maxage, which this
        // response never sends. Depends on both cache policies keeping MinTTL: 0.
        ResponseHeaders headers = ResponseHeaders.builder(status)
                .contentType(MediaType.JSON_UTF_8)
                .set(HttpHeaderNames.CACHE_CONTROL, "no-store")
                .build();
        return HttpResponse.of(headers, HttpData.ofUtf8("{\"error\":\"" + escaped + "\"}"));
    }
}
