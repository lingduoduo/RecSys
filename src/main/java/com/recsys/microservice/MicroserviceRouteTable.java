package com.recsys.microservice;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

final class MicroserviceRouteTable {
    private final Map<String, MicroserviceRoute> exactPrefixes;
    private final List<MicroserviceRoute> longestFirst;

    MicroserviceRouteTable(List<MicroserviceRoute> routes) {
        Objects.requireNonNull(routes, "routes");
        this.exactPrefixes = routes.stream()
                .collect(Collectors.toUnmodifiableMap(MicroserviceRoute::prefix, Function.identity()));
        this.longestFirst = routes.stream()
                .sorted(Comparator.comparingInt((MicroserviceRoute route) -> route.prefix().length()).reversed())
                .toList();
    }

    MicroserviceRoute match(String path) {
        String normalizedPath = MicroserviceRoute.normalizePath(path);
        MicroserviceRoute exact = exactPrefixes.get(normalizedPath);
        if (exact != null) {
            return exact;
        }
        for (MicroserviceRoute route : longestFirst) {
            if (MicroserviceRoute.matchesPrefix(normalizedPath, route.prefix())) {
                return route;
            }
        }
        return null;
    }
}
