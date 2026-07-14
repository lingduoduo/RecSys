package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.HttpService;
import com.recsys.config.EnvVars;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.function.Function;

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

    public static final String HEADER = "x-origin-secret";

    /**
     * Paths that reach the pod directly, never through CloudFront: the ALB health check, the
     * kubelet startup/readiness/liveness probes (k8s/base/api-gateway.yaml), and the Prometheus
     * ServiceMonitor scrape. Enforcing the secret on these would fail every probe and the pod
     * would never become ready.
     */
    private static final Set<String> EXEMPT_PATHS = Set.of("/health", "/metrics");

    private static final GatewayOriginSecret DISABLED = new GatewayOriginSecret(null);

    private final String expected;

    private GatewayOriginSecret(String expected) {
        this.expected = expected;
    }

    public static GatewayOriginSecret disabled() {
        return DISABLED;
    }

    public static GatewayOriginSecret fromEnvironment(EnvVars.EnvReader env) {
        String value = env.get("GATEWAY_ORIGIN_SECRET");
        if (value == null || value.isBlank()) {
            return DISABLED;
        }
        return new GatewayOriginSecret(value.trim());
    }

    public boolean isEnabled() {
        return expected != null;
    }

    public boolean isAllowed(RequestHeaders headers, String path) {
        if (!isEnabled() || isExempt(path)) {
            return true;
        }
        String provided = headers.get(HttpHeaderNames.of(HEADER));
        if (provided == null || provided.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isExempt(String path) {
        return EXEMPT_PATHS.stream().anyMatch(exempt ->
                path.equals(exempt) || path.startsWith(exempt + "/"));
    }

    /** Server-wide decorator: rejects any non-exempt request lacking the secret with 403. */
    public static Function<? super HttpService, ? extends HttpService> newDecorator(
            GatewayOriginSecret secret) {
        return delegate -> (ctx, req) -> {
            if (!secret.isAllowed(req.headers(), ctx.path())) {
                return GatewayProxyService.gatewayError(
                        HttpStatus.FORBIDDEN, "direct origin access is not permitted");
            }
            return delegate.serve(ctx, req);
        };
    }
}
