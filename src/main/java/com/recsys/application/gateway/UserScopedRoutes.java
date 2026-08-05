package com.recsys.application.gateway;

import java.util.Map;

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

    /** The declaration itself, for the conformance test. */
    static Map<String, Map<String, UserIdSource>> table() {
        return TABLE;
    }
}
