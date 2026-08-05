package com.recsys.application.gateway;
import com.recsys.config.EnvVars;

import java.net.http.HttpClient;
import java.time.Clock;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class GatewayAuthenticator {
    private static final Logger log = LoggerFactory.getLogger(GatewayAuthenticator.class);
    private static final GatewayAuthenticator DISABLED = new GatewayAuthenticator(Set.of(), Set.of("/health"), null);
    private static final String AUTHORIZATION_PREFIX = "Bearer ";

    // User-data prefixes that must NEVER be anonymously reachable, regardless of
    // GATEWAY_PUBLIC_PATHS. Defense-in-depth against a configmap typo (e.g. a bare "/api/catalog"
    // public prefix that would otherwise expose "/api/catalog/user"). Matched with the same
    // boundary rule as public paths, so "/api/users/profile" is guarded but "/api/usersettings"
    // is not.
    private static final Set<String> HAND_LISTED_PROTECTED_PREFIXES =
            Set.of("/api/catalog/user", "/api/users");

    /**
     * The hand-listed prefixes above, plus every user-scoped route {@link BackendRoutePolicy}
     * declares, derived rather than restated.
     *
     * <p>Making a user-scoped route public is not merely "unauthenticated" — an anonymous caller is
     * SERVICE tier, and service tier is exempt from the user-scope check, so listing
     * {@code /api/catalog/getrecommendation} in {@code GATEWAY_PUBLIC_PATHS} would turn off §10's
     * enforcement for that route without any warning firing. Deriving the guard from the same
     * declaration the check reads is what keeps the two from drifting; a second hand-maintained
     * list beside {@code BackendRoutePolicy} is precisely the failure mode being closed.
     *
     * <p>The hand-listed entries are kept: {@code /api/users} guards a whole prefix, which is
     * broader than the specific handlers the derivation produces.
     */
    private static final Set<String> PROTECTED_PREFIXES = protectedPrefixes();

    private static Set<String> protectedPrefixes() {
        Set<String> prefixes = new LinkedHashSet<>(HAND_LISTED_PROTECTED_PREFIXES);
        prefixes.addAll(BackendRoutePolicy.userScopedGatewayPaths(MicroserviceRoute.defaults()));
        return Set.copyOf(prefixes);
    }

    private final Set<String> apiKeys;
    private final Set<String> publicPaths;
    private final CognitoJwtVerifier jwtVerifier;

    private GatewayAuthenticator(Set<String> apiKeys, Set<String> publicPaths, CognitoJwtVerifier jwtVerifier) {
        this.apiKeys = Set.copyOf(apiKeys);
        this.publicPaths = Set.copyOf(publicPaths);
        this.jwtVerifier = jwtVerifier;
    }

    // Test-visible factory: inject a verifier backed by a static JWK provider.
    static GatewayAuthenticator forTesting(Set<String> apiKeys, Set<String> publicPaths, CognitoJwtVerifier jwtVerifier) {
        return new GatewayAuthenticator(apiKeys, publicPaths, jwtVerifier);
    }

    public static GatewayAuthenticator disabled() {
        return DISABLED;
    }

    public static GatewayAuthenticator fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    public static GatewayAuthenticator fromEnvironment(EnvVars.EnvReader env) {
        Set<String> keys = parseCsv(env.get("GATEWAY_API_KEYS"));
        CognitoConfig cognito = CognitoConfig.fromEnvironment(env);
        CognitoJwtVerifier verifier = cognito.isConfigured()
                ? new CognitoJwtVerifier(cognito, HttpClient.newHttpClient(), Clock.systemUTC())
                : null;
        if (keys.isEmpty() && verifier == null) {
            // Fail closed: refuse to start wide open. A gateway with no API keys and no Cognito
            // issuer authenticates nobody, so every caller collapses to "anonymous" — which turns
            // any downstream authorization gap into an unauthenticated one. Running that way must be
            // a deliberate, explicit choice (local dev / tests), never the accidental default.
            if (EnvVars.readBool(env, "GATEWAY_ALLOW_ANONYMOUS", false)) {
                log.warn("Gateway authentication is DISABLED (GATEWAY_ALLOW_ANONYMOUS=true): all "
                        + "requests are treated as anonymous. Never use this in production.");
                return disabled();
            }
            throw new IllegalStateException(
                    "Gateway authentication is not configured: set GATEWAY_API_KEYS and/or "
                            + "GATEWAY_COGNITO_ISSUER, or set GATEWAY_ALLOW_ANONYMOUS=true to "
                            + "explicitly run the gateway without authentication (dev/local only).");
        }
        Set<String> publicPaths = parseCsv(env.get("GATEWAY_PUBLIC_PATHS"));
        if (publicPaths.isEmpty()) {
            publicPaths = Set.of("/health");
        }
        warnOnProtectedOverlap(publicPaths);
        return new GatewayAuthenticator(keys, publicPaths, verifier);
    }

    /**
     * Surface a misconfiguration where GATEWAY_PUBLIC_PATHS would have exposed a protected
     * user-data path (the {@link #PROTECTED_PREFIXES} guard overrides it, so this is a warning,
     * not a failure). A configured path exposes a protected prefix when it equals it or is a
     * bare-prefix parent of it — the same boundary rule {@link #isPublic} uses.
     */
    private static void warnOnProtectedOverlap(Set<String> publicPaths) {
        for (String configured : publicPaths) {
            // One line per configured entry, not per pair: a bare "/api/catalog" now overlaps every
            // user-scoped catalog route, and ten near-identical warnings read as noise rather than
            // as one misconfiguration.
            List<String> exposed = PROTECTED_PREFIXES.stream()
                    .filter(p -> p.equals(configured) || p.startsWith(configured + "/"))
                    .sorted()
                    .toList();
            if (!exposed.isEmpty()) {
                log.warn("GATEWAY_PUBLIC_PATHS entry \"{}\" would expose protected user-data paths "
                        + "{}; they stay auth-required (never-public guard). Fix the config to "
                        + "list only exact non-sensitive read paths.", configured, exposed);
            }
        }
    }

    public boolean isEnabled() {
        return !apiKeys.isEmpty() || jwtVerifier != null;
    }

    /**
     * Check whether the request is authorized. Returns an allowed {@link GatewayAuthResult}
     * carrying the authenticated {@link GatewayPrincipal} if the request may proceed; returns
     * a rejected result (401) if it must be rejected.
     */
    public GatewayAuthResult check(RequestHeaders headers, String path) {
        if (!isEnabled() || isPublic(path)) {
            return GatewayAuthResult.allowed(GatewayPrincipal.anonymous());
        }

        String bearer = bearerToken(headers.get(HttpHeaderNames.AUTHORIZATION));
        String provided = firstNonBlank(headers.get(HttpHeaderNames.of("x-api-key")), bearer);

        if (provided != null) {
            boolean matched = false;
            for (String key : apiKeys) {
                matched |= constantTimeEquals(key, provided);
            }
            if (matched) {
                return GatewayAuthResult.allowed(GatewayPrincipal.ofApiKey(provided));
            }
        }

        if (jwtVerifier != null && bearer != null) {
            CognitoJwtVerifier.VerifiedClaims claims = jwtVerify(bearer);
            if (claims != null) {
                return GatewayAuthResult.allowed(GatewayPrincipal.ofJwt(claims));
            }
        }

        return GatewayAuthResult.rejected(HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.UNAUTHORIZED)
                        .set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer")
                        .contentType(MediaType.JSON_UTF_8)
                        .build(),
                HttpData.ofUtf8("{\"error\":\"missing or invalid gateway API key\"}")));
    }

    private CognitoJwtVerifier.VerifiedClaims jwtVerify(String token) {
        try {
            return jwtVerifier.verify(token);
        } catch (CognitoJwtVerifier.JwtAuthException e) {
            return null;
        }
    }

    private boolean isPublic(String path) {
        // A protected user-data path is never public, even if GATEWAY_PUBLIC_PATHS would match it.
        if (matchesPrefix(path, PROTECTED_PREFIXES)) {
            return false;
        }
        return matchesPrefix(path, publicPaths);
    }

    private static boolean matchesPrefix(String path, Set<String> prefixes) {
        return prefixes.stream().anyMatch(prefix ->
                path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        return authorization.regionMatches(true, 0, AUTHORIZATION_PREFIX, 0, AUTHORIZATION_PREFIX.length())
                ? authorization.substring(AUTHORIZATION_PREFIX.length()).trim()
                : null;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second != null && !second.isBlank() ? second.trim() : null;
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
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
}
