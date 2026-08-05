package com.recsys.application.gateway;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The backend routes that act on a caller-named {@code userId}, and where that id arrives.
 *
 * <p>Keyed on the <em>backend</em> service and path rather than the gateway path. Three route
 * prefixes — {@code /api/users}, {@code /api/movies}, {@code /api/catalog} — all resolve to 6010,
 * and {@link MicroserviceRoute#rewrite} forwards the suffix verbatim, so {@code /api/catalog/getuser}
 * and {@code /api/users/getuser} are one handler reached two ways. Keying on the handler describes
 * it once and covers every prefix that reaches it.
 *
 * <p>Matching is exact, never by prefix: prefix-with-boundary matching is precisely what created
 * the {@code /api/catalog} trap documented in {@code 20_AuthN_AuthZ} §3.
 *
 * <p>{@code UserScopedRouteCoverageTest} requires every gateway-reachable backend route to appear
 * here or in that test's explicit not-user-scoped list, so a new route cannot ship unclassified.
 */
final class UserScopedRoutes {

    private static final Map<String, Map<String, UserIdSource>> TABLE = Map.of(
            "recsys-catalog-serving", Map.of(
                    "/getuser", UserIdSource.QUERY,
                    "/user", UserIdSource.QUERY,
                    "/getrecommendation", UserIdSource.QUERY,
                    "/recommendation", UserIdSource.QUERY,
                    "/setuserembedding", UserIdSource.QUERY,
                    "/v2/recommend", UserIdSource.BODY,
                    // Scores against u2vEmb:<userId>, so naming another user reads their
                    // embedding — and the "user embedding not found" error is an existence oracle.
                    "/v1/models/recmodel:predict", UserIdSource.BODY_INSTANCES),
            "recsys-online-serving", Map.of(
                    "/online/recommendation", UserIdSource.QUERY,
                    "/online/features", UserIdSource.QUERY,
                    "/v2/recommend", UserIdSource.BODY),
            "recsys-model-serving", Map.of(
                    "/api/v1/recommend", UserIdSource.BODY,
                    "/v2/recommend", UserIdSource.BODY,
                    "/v2/sequential/recommend", UserIdSource.BODY));

    private UserScopedRoutes() {}

    /** @return where this route's userId lives, or null when the route is not user-scoped. */
    static UserIdSource lookup(String serviceName, String backendPath) {
        if (serviceName == null || backendPath == null) {
            return null;
        }
        Map<String, UserIdSource> paths = TABLE.get(serviceName);
        return paths == null ? null : paths.get(backendPath);
    }

    /** Strips the query string from a forwarder targetPath, which arrives as `rawPath?rawQuery`. */
    static String pathWithoutQuery(String targetPath) {
        if (targetPath == null) {
            return "";
        }
        int mark = targetPath.indexOf('?');
        return mark < 0 ? targetPath : targetPath.substring(0, mark);
    }

    /** @return true when this backend service has any user-scoped route at all. */
    static boolean declaresAnyFor(String serviceName) {
        return serviceName != null && TABLE.containsKey(serviceName);
    }

    /**
     * The gateway-facing spellings of every declared user-scoped route, derived from the route
     * table: {@code route.prefix() + backendPath} for each route whose {@code serviceName} appears
     * above. {@code MicroserviceRoute.rewrite} forwards the suffix verbatim, so that concatenation
     * <em>is</em> the gateway path, and every prefix that reaches a handler contributes its own
     * spelling — {@code /api/catalog/getuser} and {@code /api/users/getuser} both appear.
     *
     * <p>Version-free by construction, which is what {@link GatewayAuthenticator} needs: the
     * gateway strips {@code /api/v1} before any path-matching control runs.
     *
     * <p>Exists so the never-public guard can be <em>derived</em> from this declaration rather than
     * hand-maintained beside it. Two lists that must agree and are edited independently is exactly
     * how a user-scoped route ends up listable in {@code GATEWAY_PUBLIC_PATHS} — which would make
     * its caller anonymous, hence service-tier, hence exempt from the check declared right here.
     */
    static Set<String> gatewayPaths(List<MicroserviceRoute> routes) {
        Set<String> paths = new LinkedHashSet<>();
        for (MicroserviceRoute route : routes) {
            Map<String, UserIdSource> declared = TABLE.get(route.serviceName());
            if (declared == null) {
                continue;
            }
            for (String backendPath : declared.keySet()) {
                paths.add(route.prefix() + backendPath);
            }
        }
        return Set.copyOf(paths);
    }
}
