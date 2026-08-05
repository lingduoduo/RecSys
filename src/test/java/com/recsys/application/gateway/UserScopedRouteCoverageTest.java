package com.recsys.application.gateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every route a gateway caller can reach on a backend must be classified: either declared in
 * {@link UserScopedRoutes}, or listed below as not user-scoped with a reason.
 *
 * <p>Adding a backend route therefore fails this test until someone decides which it is. That is
 * the point — the gap this closes was never one missing check, it was that nothing forced the
 * question to be asked.
 */
class UserScopedRouteCoverageTest {

    /**
     * Routes that take no caller-named userId. The value is why, so the next reader does not have
     * to re-derive it.
     */
    private static final Map<String, String> NOT_USER_SCOPED = Map.ofEntries(
            Map.entry("recsys-catalog-serving/item", "movie by id; no user"),
            Map.entry("recsys-catalog-serving/movie", "alias of /item"),
            Map.entry("recsys-catalog-serving/similar", "item-to-item; no user"),
            Map.entry("recsys-catalog-serving/setembedding", "item embedding; control-plane, not user-scoped"),
            Map.entry("recsys-catalog-serving/health", "liveness"),
            Map.entry("recsys-catalog-serving/health/ready", "readiness"),
            Map.entry("recsys-catalog-serving/health/load", "admission-control snapshot"),
            Map.entry("recsys-catalog-serving/metrics", "Prometheus exposition"),
            Map.entry("recsys-catalog-serving/v1/models/recmodel:predict", "pairwise predict; items, not a user profile"),
            Map.entry("recsys-catalog-serving/v1/catalog/movies", "catalog listing; no user"),
            Map.entry("recsys-online-serving/health", "liveness"),
            Map.entry("recsys-online-serving/health/live", "liveness"),
            Map.entry("recsys-online-serving/health/ready", "readiness"),
            Map.entry("recsys-online-serving/metrics", "Prometheus exposition"),
            Map.entry("recsys-online-serving/online/ops", "operator surface; guarded by AdminTokenGuard"),
            Map.entry("recsys-online-serving/shards/", "device-keyed, not user-keyed; no device-to-owner mapping exists"),
            Map.entry("recsys-model-serving/api/v1/token", "issues a submit token; no user named"),
            Map.entry("recsys-model-serving/api/v1/knowledge-bases", "knowledge bases; no user"),
            Map.entry("recsys-model-serving/api/v1/knowledge-bases/{knowledgeBaseId}", "knowledge base by id; no user"),
            Map.entry("recsys-model-serving/api/v1/auth/login", "issues a session token"),
            Map.entry("recsys-model-serving/api/v1/auth/logout", "ends a session"),
            Map.entry("recsys-model-serving/api/v1/model/versions", "control-plane; see 20_AuthN_AuthZ sharp edge 1"),
            Map.entry("recsys-model-serving/api/v1/model/versions/preload",
                    "control-plane; body is {variant} only, warms a model runtime"),
            Map.entry("recsys-model-serving/api/v1/model/versions/activate", "control-plane"),
            Map.entry("recsys-model-serving/api/v1/model/versions/rollback", "control-plane"),
            Map.entry("recsys-model-serving/health", "liveness"),
            Map.entry("recsys-model-serving/health/jvm", "diagnostics"),
            Map.entry("recsys-model-serving/health/gc", "diagnostics"),
            Map.entry("recsys-model-serving/health/live", "liveness"),
            Map.entry("recsys-model-serving/health/metrics", "diagnostics"),
            Map.entry("recsys-model-serving/health/load", "diagnostics"),
            Map.entry("recsys-model-serving/health/cache", "diagnostics"),
            Map.entry("recsys-model-serving/health/ab-tests", "A/B config; no user named"),
            Map.entry("recsys-model-serving/health/ready", "readiness"));

    /**
     * Floors, not exact counts: a regex that silently stops matching would otherwise make this
     * whole test vacuous. Raise them when a service genuinely grows.
     */
    private static final Map<String, Integer> MINIMUM_ROUTES = Map.of(
            "recsys-catalog-serving", 14,
            "recsys-online-serving", 8,
            "recsys-model-serving", 14);

    @Test
    void everyBackendRouteIsClassified() throws IOException {
        Map<String, Set<String>> routes = new LinkedHashMap<>();
        routes.put("recsys-catalog-serving",
                armeriaRoutes(Path.of("src/main/java/com/recsys/api/serving/RecSysServer.java")));
        routes.put("recsys-online-serving",
                armeriaRoutes(Path.of("src/main/java/com/recsys/api/online/OnlinePredictionServer.java")));
        routes.put("recsys-model-serving",
                springRoutes(Path.of("src/main/java/com/recsys/api/rest")));

        List<String> unclassified = new ArrayList<>();
        routes.forEach((service, paths) -> {
            int floor = MINIMUM_ROUTES.get(service);
            assertTrue(paths.size() >= floor,
                    "Route scan for " + service + " found only " + paths.size() + " routes (expected at "
                            + "least " + floor + "). The scanner has probably stopped matching — fix it "
                            + "rather than lowering the floor, or this test silently passes forever. "
                            + "Found: " + paths);
            for (String path : paths) {
                boolean declared = UserScopedRoutes.lookup(service, path) != null;
                boolean excused = NOT_USER_SCOPED.containsKey(service + path);
                if (!declared && !excused) {
                    unclassified.add(service + path);
                }
            }
        });

        assertTrue(unclassified.isEmpty(),
                "Unclassified backend routes: " + unclassified + ". Every gateway-reachable route must "
                        + "either declare where its userId lives in UserScopedRoutes, or be listed in "
                        + "NOT_USER_SCOPED with a reason. See "
                        + "docs/superpowers/specs/2026-08-05-gateway-user-scope-authorization-design.md.");
    }

    // ---- scanners -------------------------------------------------------------------------

    private static final Pattern ROUTE_CONSTANT =
            Pattern.compile("String\\s+(ROUTE_[A-Z0-9_]+)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern SERVICE_CALL =
            Pattern.compile("\\.service\\(\\s*(?:(ROUTE_[A-Z0-9_]+)|\"([^\"]+)\")");
    private static final Pattern PATH_PREFIX = Pattern.compile("pathPrefix\\(\"([^\"]+)\"\\)");
    private static final Pattern REGEX_ROUTE =
            Pattern.compile("\\.regex\\(\\s*\"\\^\"\\s*\\+\\s*(ROUTE_[A-Z0-9_]+)");

    /** Armeria: `.service(ROUTE_X, ...)`, `.service("/literal", ...)`, pathPrefix and regex routes. */
    private static Set<String> armeriaRoutes(Path file) throws IOException {
        String source = Files.readString(file);
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher constant = ROUTE_CONSTANT.matcher(source);
        while (constant.find()) {
            constants.put(constant.group(1), constant.group(2));
        }
        Set<String> paths = new LinkedHashSet<>();
        Matcher call = SERVICE_CALL.matcher(source);
        while (call.find()) {
            String value = call.group(1) != null ? constants.get(call.group(1)) : call.group(2);
            if (value != null && value.startsWith("/")) {
                paths.add(value);
            }
        }
        Matcher prefix = PATH_PREFIX.matcher(source);
        while (prefix.find()) {
            paths.add(prefix.group(1));
        }
        Matcher regex = REGEX_ROUTE.matcher(source);
        while (regex.find()) {
            String value = constants.get(regex.group(1));
            if (value != null) {
                paths.add(value);
            }
        }
        return paths;
    }

    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Delete|Patch)Mapping\\(\\s*(?:value\\s*=\\s*)?(?:\"([^\"]*)\")?");

    /** Spring: class-level @RequestMapping joined with each method mapping's path (possibly empty). */
    private static Set<String> springRoutes(Path directory) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher classMatcher = CLASS_MAPPING.matcher(source);
                String base = classMatcher.find() ? classMatcher.group(1) : "";
                Matcher methodMatcher = METHOD_MAPPING.matcher(source);
                while (methodMatcher.find()) {
                    String suffix = methodMatcher.group(1) == null ? "" : methodMatcher.group(1);
                    String path = base + suffix;
                    if (path.startsWith("/")) {
                        paths.add(path);
                    }
                }
            }
        }
        return paths;
    }
}
