package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.recsys.config.EnvVars;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Adds RFC 8594 {@code Sunset} and {@code Deprecation} response headers to the two deprecated
 * request shapes, from one place, so no route has to remember to do it.
 *
 * <p>Two independent deprecation classes:
 * <ul>
 *   <li><b>Unversioned spelling</b> — an {@code /api} path with no explicit version segment.
 *       Its successor is mechanically derivable, so a {@code Link} is emitted.
 *   <li><b>Back-compat alias route</b> — {@code /api/catalog}, {@code /api/model},
 *       {@code /api/online}. These duplicate other routes and stay deprecated even when
 *       versioned. Their successors are NOT mechanical (the aliases strip to different backend
 *       paths), so no {@code Link} is emitted for them.
 * </ul>
 *
 * <p>Disabled when {@code GATEWAY_DEPRECATION_SUNSET} is unset or unparseable: the compatibility
 * policy says a sunset date is published when a deprecation is announced, so emitting
 * {@code Deprecation} without a date would be a promise with no expiry attached.
 *
 * <p>This decorator only adds headers. It never changes status, body, or routing.
 */
public final class ApiDeprecationDecorator {

    private static final Logger log = LoggerFactory.getLogger(ApiDeprecationDecorator.class);

    /** Probes and scrapes reach the pod directly and carry no client contract. */
    static final Set<String> EXEMPT_PREFIXES = Set.of("/health", "/metrics");

    /** Backend-oriented aliases, deprecated in favour of the resource-oriented routes. */
    static final Set<String> ALIAS_PREFIXES = Set.of("/api/catalog", "/api/model", "/api/online");

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private final String sunsetHeaderValue;

    private ApiDeprecationDecorator(String sunsetHeaderValue) {
        this.sunsetHeaderValue = sunsetHeaderValue;
    }

    public static ApiDeprecationDecorator fromEnvironment(EnvVars.EnvReader env) {
        String raw = env.get("GATEWAY_DEPRECATION_SUNSET");
        if (raw == null || raw.isBlank()) {
            return new ApiDeprecationDecorator(null);
        }
        try {
            LocalDate date = LocalDate.parse(raw.trim());
            return new ApiDeprecationDecorator(
                    HTTP_DATE.format(date.atStartOfDay(ZoneOffset.UTC)));
        } catch (RuntimeException e) {
            log.warn("GATEWAY_DEPRECATION_SUNSET=\"{}\" is not an ISO-8601 date (expected "
                    + "yyyy-MM-dd); deprecation headers are disabled.", raw);
            return new ApiDeprecationDecorator(null);
        }
    }

    public boolean isEnabled() {
        return sunsetHeaderValue != null;
    }

    String sunsetHeaderValue() {
        return sunsetHeaderValue;
    }

    public boolean isDeprecated(String requestPath) {
        if (!isEnabled()) {
            return false;
        }
        String path = MicroserviceRoute.normalizePath(requestPath);
        if (matchesAny(path, EXEMPT_PREFIXES)) {
            return false;
        }
        return isUnversionedApiPath(path) || matchesAny(ApiVersion.parse(path).path(), ALIAS_PREFIXES);
    }

    /** The {@code Link} header value, or null when no mechanical successor exists. */
    public String successorLink(String requestPath) {
        if (!isDeprecated(requestPath)) {
            return null;
        }
        String path = MicroserviceRoute.normalizePath(requestPath);
        if (!isUnversionedApiPath(path)) {
            return null;
        }
        return "<" + ApiVersion.versioned(ApiVersion.DEFAULT_VERSION, path)
                + ">; rel=\"successor-version\"";
    }

    public Function<? super HttpService, ? extends HttpService> newDecorator() {
        return delegate -> (ctx, req) -> {
            String requestPath = ctx.path();
            if (!isDeprecated(requestPath)) {
                return delegate.serve(ctx, req);
            }
            String link = successorLink(requestPath);
            return delegate.serve(ctx, req).mapHeaders(headers -> {
                var builder = headers.toBuilder()
                        .set(HttpHeaderNames.of("deprecation"), "true")
                        .set(HttpHeaderNames.of("sunset"), sunsetHeaderValue);
                if (link != null) {
                    builder.set(HttpHeaderNames.LINK, link);
                }
                return builder.build();
            });
        };
    }

    private static boolean isUnversionedApiPath(String normalizedPath) {
        return normalizedPath.startsWith("/api") && !ApiVersion.parse(normalizedPath).explicit();
    }

    private static boolean matchesAny(String path, Set<String> prefixes) {
        return prefixes.stream().anyMatch(prefix ->
                path.equals(prefix) || path.startsWith(prefix + "/"));
    }
}
