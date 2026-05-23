package com.recsys.microservice;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

record MicroserviceRoute(String name,
                         String prefix,
                         String envVar,
                         String serviceNameEnvVar,
                         String serviceName,
                         URI baseUri,
                         String healthPath) {

    private static final List<MicroserviceRoute> DEFAULTS = List.of(
            fromEnv("catalog", "/api/catalog", "CATALOG_SERVICE_URL", "NACOS_CATALOG_SERVICE_NAME",
                    "recsys-catalog-serving", "http://localhost:6010", "/health"),
            fromEnv("model", "/api/model", "MODEL_SERVICE_URL", "NACOS_MODEL_SERVICE_NAME",
                    "recsys-model-serving", "http://localhost:8080", "/health/ready"),
            fromEnv("online", "/api/online", "ONLINE_SERVICE_URL", "NACOS_ONLINE_SERVICE_NAME",
                    "recsys-online-serving", "http://localhost:7010", "/health")
    );

    MicroserviceRoute {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(envVar, "envVar");
        Objects.requireNonNull(serviceNameEnvVar, "serviceNameEnvVar");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(healthPath, "healthPath");
        if (!prefix.startsWith("/")) {
            throw new IllegalArgumentException("route prefix must start with /");
        }
        if (baseUri.getScheme() == null || baseUri.getHost() == null) {
            throw new IllegalArgumentException("route base URI must include scheme and host: " + baseUri);
        }
    }

    static List<MicroserviceRoute> defaults() {
        return DEFAULTS;
    }

    static MicroserviceRoute match(List<MicroserviceRoute> routes, String path) {
        String normalizedPath = normalizePath(path);
        MicroserviceRoute best = null;
        for (MicroserviceRoute route : routes) {
            if (matchesPrefix(normalizedPath, route.prefix())
                    && (best == null || route.prefix().length() > best.prefix().length())) {
                best = route;
            }
        }
        return best;
    }

    URI rewrite(String requestPath, String query, NacosServiceRegistry registry) {
        String normalizedPath = normalizePath(requestPath);
        if (!matchesPrefix(normalizedPath, prefix)) {
            throw new IllegalArgumentException("path does not match route " + prefix + ": " + requestPath);
        }

        String suffix = normalizedPath.substring(prefix.length());
        if (suffix.isBlank()) {
            suffix = "/";
        }
        URI resolvedBaseUri = registry.resolve(serviceName, baseUri);
        String targetPath = joinPaths(resolvedBaseUri.getPath(), suffix);
        return URI.create(newUri(resolvedBaseUri, targetPath, query));
    }

    URI healthUri(NacosServiceRegistry registry) {
        URI resolvedBaseUri = registry.resolve(serviceName, baseUri);
        return URI.create(newUri(resolvedBaseUri, joinPaths(resolvedBaseUri.getPath(), healthPath), null));
    }

    private static MicroserviceRoute fromEnv(String name,
                                             String prefix,
                                             String envVar,
                                             String serviceNameEnvVar,
                                             String defaultServiceName,
                                             String defaultBaseUri,
                                             String healthPath) {
        String raw = System.getenv().getOrDefault(envVar, defaultBaseUri);
        String serviceName = System.getenv().getOrDefault(serviceNameEnvVar, defaultServiceName);
        return new MicroserviceRoute(name, prefix, envVar, serviceNameEnvVar, serviceName, URI.create(raw), healthPath);
    }

    private static boolean matchesPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String joinPaths(String left, String right) {
        String base = (left == null || left.isBlank()) ? "" : left;
        String suffix = (right == null || right.isBlank()) ? "/" : right;
        if (!base.startsWith("/") && !base.isBlank()) {
            base = "/" + base;
        }
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + suffix;
    }

    private static String newUri(URI base, String path, String query) {
        StringBuilder builder = new StringBuilder();
        builder.append(base.getScheme().toLowerCase(Locale.ROOT)).append("://").append(base.getAuthority());
        builder.append(path);
        if (query != null && !query.isBlank()) {
            builder.append('?').append(query);
        }
        return builder.toString();
    }
}
