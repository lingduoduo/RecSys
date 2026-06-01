package com.recsys.infrastructure.alb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Layer-7 load balancer: evaluates listener rules in priority order and
 * round-robins across healthy targets in the matched target group.
 */
public final class ApplicationLoadBalancer {

    private final Map<Integer, AlbListener> listeners;
    private final Map<String, AlbTargetGroup> targetGroups;

    private ApplicationLoadBalancer(Map<Integer, AlbListener> listeners,
                                    Map<String, AlbTargetGroup> targetGroups) {
        this.listeners = Map.copyOf(listeners);
        this.targetGroups = Map.copyOf(targetGroups);
    }

    public Optional<AlbTarget> route(int port, String path, String host, String method) {
        return Optional.ofNullable(listeners.get(port))
                .flatMap(l -> l.route(path, host, method))
                .flatMap(tgName -> Optional.ofNullable(targetGroups.get(tgName)))
                .flatMap(AlbTargetGroup::nextTarget);
    }

    public List<AlbListener> listeners() {
        return List.copyOf(listeners.values());
    }

    public List<AlbTargetGroup> targetGroups() {
        return List.copyOf(targetGroups.values());
    }

    public Optional<AlbTargetGroup> targetGroup(String name) {
        return Optional.ofNullable(targetGroups.get(name));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<AlbListener> listeners = new ArrayList<>();
        private final List<AlbTargetGroup> targetGroups = new ArrayList<>();

        public Builder listener(AlbListener listener) {
            Objects.requireNonNull(listener, "listener");
            boolean duplicate = listeners.stream().anyMatch(l -> l.port() == listener.port());
            if (duplicate) {
                throw new IllegalArgumentException("duplicate listener port: " + listener.port());
            }
            listeners.add(listener);
            return this;
        }

        public Builder targetGroup(AlbTargetGroup group) {
            Objects.requireNonNull(group, "group");
            boolean duplicate = targetGroups.stream().anyMatch(g -> g.name().equals(group.name()));
            if (duplicate) {
                throw new IllegalArgumentException("duplicate target group name: " + group.name());
            }
            targetGroups.add(group);
            return this;
        }

        public ApplicationLoadBalancer build() {
            if (listeners.isEmpty()) throw new IllegalStateException("at least one listener is required");
            if (targetGroups.isEmpty()) throw new IllegalStateException("at least one target group is required");

            Map<Integer, AlbListener> listenerMap = listeners.stream()
                    .collect(Collectors.toUnmodifiableMap(AlbListener::port, Function.identity()));
            Map<String, AlbTargetGroup> groupMap = targetGroups.stream()
                    .collect(Collectors.toUnmodifiableMap(AlbTargetGroup::name, Function.identity()));

            return new ApplicationLoadBalancer(listenerMap, groupMap);
        }
    }
}
