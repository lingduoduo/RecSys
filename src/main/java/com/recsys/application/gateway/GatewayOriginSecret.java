package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.HttpService;
import com.recsys.config.EnvVars;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.AsciiString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates the secret header CloudFront injects on every origin request.
 *
 * <p>The ALB security group is pinned to the CloudFront origin-facing managed prefix list, but
 * that list covers <em>every</em> AWS account's distributions — so the prefix list alone does not
 * prove the request came from <em>our</em> distribution. This header does.
 *
 * <p>Disabled when {@code GATEWAY_ORIGIN_SECRET} is unset, so local dev and the existing test
 * suite are unaffected. See docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md.
 */
public final class GatewayOriginSecret {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayOriginSecret.class);

    public static final String HEADER = "x-origin-secret";

    /**
     * Paths that reach the pod directly, never through CloudFront: the ALB health check, the
     * kubelet startup/readiness/liveness probes (k8s/base/api-gateway.yaml), and the Prometheus
     * ServiceMonitor scrape. Enforcing the secret on these would fail every probe and the pod
     * would never become ready.
     */
    private static final Set<String> EXEMPT_PATHS = Set.of("/health", "/metrics");

    /** Hoisted off the hot path — every gateway request would otherwise re-resolve it. */
    private static final AsciiString HEADER_NAME = HttpHeaderNames.of(HEADER);

    private static final GatewayOriginSecret DISABLED = new GatewayOriginSecret(Set.of());

    /**
     * All currently-accepted secrets. More than one is the rotation window: add the new secret
     * alongside the old, roll the pods, flip the distribution, then drop the old one — see
     * docs/runbooks/cdn-operations.md.
     */
    private final Set<String> secrets;

    private GatewayOriginSecret(Set<String> secrets) {
        this.secrets = Set.copyOf(secrets);
    }

    public static GatewayOriginSecret disabled() {
        return DISABLED;
    }

    public static GatewayOriginSecret fromEnvironment(EnvVars.EnvReader env) {
        Set<String> parsed = parseCsv(env.get("GATEWAY_ORIGIN_SECRET"));
        return parsed.isEmpty() ? DISABLED : new GatewayOriginSecret(parsed);
    }

    public boolean isEnabled() {
        return !secrets.isEmpty();
    }

    public boolean isAllowed(RequestHeaders headers, String path) {
        if (!isEnabled() || isExempt(path)) {
            return true;
        }
        String provided = headers.get(HEADER_NAME);
        if (provided == null || provided.isBlank()) {
            return false;
        }
        String trimmed = provided.trim();

        // Deliberately does NOT break on the first match: an early exit would make the loop
        // count depend on which secret matched, leaking set size and match position through
        // timing and undoing the point of the constant-time compare. Mirrors
        // GatewayAuthenticator.check.
        boolean matched = false;
        for (String secret : secrets) {
            matched |= constantTimeEquals(secret, trimmed);
        }
        return matched;
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isExempt(String path) {
        return EXEMPT_PATHS.stream().anyMatch(exempt ->
                path.equals(exempt) || path.startsWith(exempt + "/"));
    }

    /**
     * Server-wide decorator: rejects any non-exempt request lacking a valid secret with 403.
     *
     * <p>Side effects live here rather than in {@link #isAllowed}, which stays a pure predicate.
     *
     * @param registry may be null, in which case no counter is registered.
     */
    public static Function<? super HttpService, ? extends HttpService> newDecorator(
            GatewayOriginSecret secret, MeterRegistry registry) {

        Counter rejected = registry == null ? null
                : Counter.builder("gateway_origin_secret_rejected_total")
                        .description("Requests rejected for a missing or invalid CloudFront origin secret")
                        .register(registry);

        // Logged once, not per request: under a scan or a botched rotation this fires on every
        // request, and a per-request log would flood. The counter is the real signal; this is
        // the breadcrumb that explains it.
        AtomicBoolean warned = new AtomicBoolean();

        return delegate -> (ctx, req) -> {
            if (!secret.isAllowed(req.headers(), ctx.path())) {
                if (rejected != null) {
                    rejected.increment();
                }
                if (warned.compareAndSet(false, true)) {
                    LOG.warn("Rejected a request with a missing or invalid {} header (first "
                                    + "occurrence, path={}). If this coincides with a secret "
                                    + "rotation, the distribution and GATEWAY_ORIGIN_SECRET "
                                    + "disagree — see docs/runbooks/cdn-operations.md. Further "
                                    + "rejections are counted in gateway_origin_secret_rejected_total "
                                    + "and not logged.",
                            HEADER, ctx.path());
                }
                return GatewayProxyService.gatewayError(
                        HttpStatus.FORBIDDEN, "direct origin access is not permitted");
            }
            return delegate.serve(ctx, req);
        };
    }
}
