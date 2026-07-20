package com.recsys.api.online;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Function;

/**
 * Operator-token gate for online-serving introspection / bulk surfaces (e.g. {@code /online/ops},
 * {@code GET /shards/shard}). A valid gateway client credential is not sufficient to reach these —
 * the caller must also present the operator token in the {@code X-Admin-Token} header.
 *
 * <p>Fail closed: when no token is configured the guard authorizes nobody, so an unprovisioned
 * deployment returns 403 rather than exposing the surface. The comparison is constant-time so the
 * token's prefix does not leak through response timing.
 *
 * <p>Configured from {@code SHARD_ADMIN_TOKEN} — the same operator token that gates the reshard
 * endpoint.
 */
public final class AdminTokenGuard {

    public static final String HEADER = "X-Admin-Token";

    private final String token;

    public AdminTokenGuard(String token) {
        this.token = token;
    }

    /** True when an operator token is set; when false the guard authorizes nobody (fail closed). */
    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    /** True only when configured AND {@code provided} matches the token in constant time. */
    public boolean isAuthorized(String provided) {
        if (!isConfigured() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Armeria decorator that rejects any request without a valid {@code X-Admin-Token} with 403,
     * otherwise delegates. Apply to routes serving operator-only surfaces.
     */
    public static Function<? super HttpService, ? extends HttpService> newDecorator(AdminTokenGuard guard) {
        return delegate -> (ctx, req) -> {
            if (!guard.isAuthorized(req.headers().get(HEADER))) {
                return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.JSON_UTF_8,
                        "{\"error\":\"operator token required\"}");
            }
            return delegate.serve(ctx, req);
        };
    }
}
