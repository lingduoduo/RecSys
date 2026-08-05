package com.recsys.application.gateway;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the gateway is willing to proxy, per backend route, and what it demands first.
 *
 * <p>Keyed on the <em>backend</em> service and path rather than the gateway path. Three route
 * prefixes — {@code /api/users}, {@code /api/movies}, {@code /api/catalog} — all resolve to 6010,
 * and {@link MicroserviceRoute#rewrite} forwards the suffix verbatim, so one handler is reachable
 * under several gateway spellings. Keying on the handler describes it once and covers all of them.
 *
 * <p>This table absorbed the former {@code UserScopedRoutes} rather than sitting beside it: both
 * are keyed on the same pair, and two tables on an identical key that must agree is exactly how
 * {@code PROTECTED_PREFIXES} and the user-scope declaration drifted apart before.
 *
 * <p><strong>An unclassified path is denied.</strong> That is what makes this close a class rather
 * than a list of instances — a diagnostic route added to a backend tomorrow is unreachable through
 * the gateway, not exposed by it. {@code BackendRouteCoverageTest} requires every scanned backend
 * route to appear here, so the denial surfaces at build time rather than in production.
 */
final class BackendRoutePolicy {

    /** What the gateway requires before forwarding a request to a backend route. */
    enum Access {
        /** Never proxied. Telemetry and diagnostics, reachable only on the pod. */
        NO_PROXY,
        /** Requires the operator token, for every caller including service-tier ones. */
        OPERATOR,
        /** Requires that a user-tier caller name its own userId. */
        USER_SCOPED,
        /** Proxied to any authenticated caller — today's behaviour for ordinary data paths. */
        AUTHENTICATED
    }

    /** @param userIdSource where the userId lives; non-null exactly when access is USER_SCOPED. */
    record Policy(Access access, UserIdSource userIdSource) {
        Policy {
            if ((access == Access.USER_SCOPED) != (userIdSource != null)) {
                throw new IllegalArgumentException(
                        "userIdSource must be present exactly when access is USER_SCOPED, but was "
                                + userIdSource + " for " + access);
            }
        }
    }

    private static Policy of(Access access) {
        return new Policy(access, null);
    }

    private static Policy userScoped(UserIdSource source) {
        return new Policy(Access.USER_SCOPED, source);
    }

    // Exact match, always tried first. Map.ofEntries because Map.of caps at ten pairs.
    private static final Map<String, Map<String, Policy>> EXACT = Map.of(
            "recsys-catalog-serving", Map.ofEntries(
                    Map.entry("/getuser", userScoped(UserIdSource.QUERY)),
                    Map.entry("/user", userScoped(UserIdSource.QUERY)),
                    Map.entry("/getrecommendation", userScoped(UserIdSource.QUERY)),
                    Map.entry("/recommendation", userScoped(UserIdSource.QUERY)),
                    Map.entry("/setuserembedding", userScoped(UserIdSource.QUERY)),
                    Map.entry("/v2/recommend", userScoped(UserIdSource.BODY)),
                    // Scores against u2vEmb:<userId>, so naming another user reads their
                    // embedding — and the "user embedding not found" error is an existence oracle.
                    Map.entry("/v1/models/recmodel:predict", userScoped(UserIdSource.BODY_INSTANCES)),
                    // Overwrites item embeddings for every user of the system.
                    Map.entry("/setembedding", of(Access.OPERATOR)),
                    Map.entry("/item", of(Access.AUTHENTICATED)),
                    Map.entry("/movie", of(Access.AUTHENTICATED)),
                    Map.entry("/similar", of(Access.AUTHENTICATED)),
                    Map.entry("/v1/catalog/movies", of(Access.AUTHENTICATED)),
                    Map.entry("/metrics", of(Access.NO_PROXY)),
                    Map.entry("/health", of(Access.NO_PROXY)),
                    Map.entry("/health/ready", of(Access.NO_PROXY)),
                    Map.entry("/health/load", of(Access.NO_PROXY))),
            "recsys-online-serving", Map.ofEntries(
                    Map.entry("/online/recommendation", userScoped(UserIdSource.QUERY)),
                    Map.entry("/online/features", userScoped(UserIdSource.QUERY)),
                    Map.entry("/v2/recommend", userScoped(UserIdSource.BODY)),
                    // Already guarded by AdminTokenGuard on 7010; covered twice on purpose.
                    Map.entry("/online/ops", of(Access.OPERATOR)),
                    Map.entry("/metrics", of(Access.NO_PROXY)),
                    Map.entry("/health", of(Access.NO_PROXY)),
                    Map.entry("/health/live", of(Access.NO_PROXY)),
                    Map.entry("/health/ready", of(Access.NO_PROXY))),
            "recsys-model-serving", Map.ofEntries(
                    Map.entry("/api/v1/recommend", userScoped(UserIdSource.BODY)),
                    Map.entry("/v2/recommend", userScoped(UserIdSource.BODY)),
                    Map.entry("/v2/sequential/recommend", userScoped(UserIdSource.BODY)),
                    // Swap or warm the serving model for everyone.
                    Map.entry("/api/v1/model/versions", of(Access.OPERATOR)),
                    Map.entry("/api/v1/model/versions/activate", of(Access.OPERATOR)),
                    Map.entry("/api/v1/model/versions/rollback", of(Access.OPERATOR)),
                    Map.entry("/api/v1/model/versions/preload", of(Access.OPERATOR)),
                    Map.entry("/api/v1/token", of(Access.AUTHENTICATED)),
                    Map.entry("/api/v1/auth/login", of(Access.AUTHENTICATED)),
                    Map.entry("/api/v1/auth/logout", of(Access.AUTHENTICATED)),
                    Map.entry("/api/v1/knowledge-bases", of(Access.AUTHENTICATED)),
                    Map.entry("/api/v1/knowledge-bases/{knowledgeBaseId}", of(Access.AUTHENTICATED)),
                    Map.entry("/health/jvm", of(Access.NO_PROXY)),
                    Map.entry("/health/gc", of(Access.NO_PROXY)),
                    Map.entry("/health/live", of(Access.NO_PROXY)),
                    Map.entry("/health/metrics", of(Access.NO_PROXY)),
                    Map.entry("/health/load", of(Access.NO_PROXY)),
                    Map.entry("/health/cache", of(Access.NO_PROXY)),
                    Map.entry("/health/ab-tests", of(Access.NO_PROXY)),
                    Map.entry("/health/ready", of(Access.NO_PROXY))));

    /**
     * The only two paths that cannot be enumerated, so the only two matched by prefix.
     *
     * <p>{@code /actuator}'s membership comes from {@code MANAGEMENT_ENDPOINTS_EXPOSURE}, not from
     * source, so no scanner can list it. {@code /shards} is registered as a single Armeria
     * {@code pathPrefix} whose sub-paths are dispatched inside {@code ShardedRecordService}.
     *
     * <p>Stored without a trailing slash and matched with the boundary rule, so {@code /actuatorx}
     * is not {@code /actuator}. Consulted only after an exact miss — prefix-first matching is what
     * produced the {@code /api/catalog} trap of {@code 20_AuthN_AuthZ} §3.
     *
     * <p>{@code /shards} is AUTHENTICATED rather than OPERATOR because the prefix mixes tiers:
     * {@code POST /shards/topology} and {@code GET /shards/shard} are operator surfaces that
     * {@code ShardedRecordService} already guards, while {@code /shards/device} and
     * {@code /shards/records} are ordinary data paths that OPERATOR here would break.
     */
    private static final Map<String, Map<String, Policy>> PREFIX = Map.of(
            "recsys-model-serving", Map.of("/actuator", of(Access.NO_PROXY)),
            "recsys-online-serving", Map.of("/shards", of(Access.AUTHENTICATED)));

    private BackendRoutePolicy() {}

    /** @return the policy for this backend route, or null when it is not classified at all. */
    static Policy lookup(String serviceName, String backendPath) {
        if (serviceName == null || backendPath == null) {
            return null;
        }
        Map<String, Policy> exact = EXACT.get(serviceName);
        if (exact != null) {
            Policy hit = exact.get(backendPath);
            if (hit != null) {
                return hit;
            }
        }
        Map<String, Policy> prefixes = PREFIX.get(serviceName);
        if (prefixes == null) {
            return null;
        }
        for (Map.Entry<String, Policy> entry : prefixes.entrySet()) {
            String prefix = entry.getKey();
            if (backendPath.equals(prefix) || backendPath.startsWith(prefix + "/")) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** @return true when this backend service has any user-scoped route at all. */
    static boolean declaresAnyUserScopedFor(String serviceName) {
        Map<String, Policy> paths = serviceName == null ? null : EXACT.get(serviceName);
        return paths != null && paths.values().stream().anyMatch(p -> p.access() == Access.USER_SCOPED);
    }

    /** Declared exact backend paths for a service, for the coverage test's orphan check. */
    static Set<String> exactPaths(String serviceName) {
        Map<String, Policy> paths = EXACT.get(serviceName);
        return paths == null ? Set.of() : paths.keySet();
    }

    /** Declared prefix backend paths for a service; exempt from the orphan check by nature. */
    static Set<String> prefixPaths(String serviceName) {
        Map<String, Policy> paths = PREFIX.get(serviceName);
        return paths == null ? Set.of() : paths.keySet();
    }

    /**
     * The gateway-facing spellings of every <em>user-scoped</em> route, derived from the route
     * table: {@code route.prefix() + backendPath}. {@link GatewayAuthenticator} derives its
     * never-public guard from this rather than restating it — a user-scoped route listed in
     * {@code GATEWAY_PUBLIC_PATHS} would make its callers anonymous, hence service-tier, hence
     * exempt from the very check declared here.
     *
     * <p>User-scoped only, deliberately. NO_PROXY paths need no never-public guard because they
     * are not proxied at all, and OPERATOR paths carry their own credential.
     */
    static Set<String> userScopedGatewayPaths(List<MicroserviceRoute> routes) {
        Set<String> paths = new LinkedHashSet<>();
        for (MicroserviceRoute route : routes) {
            Map<String, Policy> declared = EXACT.get(route.serviceName());
            if (declared == null) {
                continue;
            }
            declared.forEach((backendPath, policy) -> {
                if (policy.access() == Access.USER_SCOPED) {
                    paths.add(route.prefix() + backendPath);
                }
            });
        }
        return Set.copyOf(paths);
    }

    /**
     * The backend a route actually reaches: its declared {@code serviceName}, or — when it declares
     * none — the {@code serviceName} of whichever known route points at the same authority.
     *
     * <p>Resolving the <em>target</em> rather than the label is what stops a null
     * {@code serviceName} from being a silent exemption. {@link MicroserviceRoute}'s 5-arg
     * constructor defaults it to null and {@code fromEnvOptional} always passes null, so a route
     * added either way — or an {@code LLM_SERVICE_URL} pointed at a backend — would otherwise miss
     * {@link #lookup} entirely and forward unchecked. A route cannot opt out of the check by
     * declining to name itself.
     *
     * @return the effective service name, or null when the route reaches no known backend
     */
    static String effectiveServiceName(MicroserviceRoute route, List<MicroserviceRoute> known) {
        if (route == null) {
            return null;
        }
        if (route.serviceName() != null) {
            return route.serviceName();
        }
        String authority = authorityOf(route.baseUri());
        if (authority == null || known == null) {
            return null;
        }
        for (MicroserviceRoute candidate : known) {
            if (candidate.serviceName() != null
                    && authority.equals(authorityOf(candidate.baseUri()))) {
                return candidate.serviceName();
            }
        }
        return null;
    }

    /** {@code host:port}, lowercased, with the scheme's default port supplied when absent. */
    private static String authorityOf(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return null;
        }
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** Strips the query string from a forwarder targetPath, which arrives as `rawPath?rawQuery`. */
    static String pathWithoutQuery(String targetPath) {
        if (targetPath == null) {
            return "";
        }
        int mark = targetPath.indexOf('?');
        return mark < 0 ? targetPath : targetPath.substring(0, mark);
    }
}
