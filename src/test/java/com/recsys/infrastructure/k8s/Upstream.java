package com.recsys.infrastructure.k8s;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * A host:port a workload dials, derived from a ConfigMap value. Derivation rather than a
 * hand-written list is the point: the addresses live in exactly one place, so changing an
 * upstream's address in the ConfigMap re-points the requirement automatically.
 */
record Upstream(String host, int port) {

    static List<Upstream> parse(String key, Map<String, String> configMap) {
        String value = configMap.getOrDefault(key, "");
        if (value.isBlank()) return List.of();

        if ("REDIS_HOST".equals(key)) {
            int port = Integer.parseInt(configMap.getOrDefault("REDIS_PORT", "6379").strip());
            return List.of(new Upstream(value.strip(), port));
        }
        if (key.endsWith("_NODES") || key.endsWith("_BOOTSTRAP_SERVERS")) {
            return java.util.Arrays.stream(value.split(","))
                    .map(String::strip)
                    .filter(s -> !s.isBlank())
                    .map(Upstream::fromHostPort)
                    .toList();
        }
        if (value.startsWith("jdbc:")) {
            // jdbc:mysql://host:port/db — strip the jdbc: prefix so URI sees a normal scheme.
            return List.of(fromUri(URI.create(value.substring("jdbc:".length()))));
        }
        return List.of(fromUri(URI.create(value.strip())));
    }

    private static Upstream fromHostPort(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) throw new IllegalArgumentException("no port in node entry: " + hostPort);
        return new Upstream(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)));
    }

    private static Upstream fromUri(URI uri) {
        int port = uri.getPort();
        if (port < 0) port = "https".equals(uri.getScheme()) ? 443 : 80;
        return new Upstream(uri.getHost(), port);
    }
}
