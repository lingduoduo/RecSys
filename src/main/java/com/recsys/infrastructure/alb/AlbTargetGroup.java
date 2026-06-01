package com.recsys.infrastructure.alb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class AlbTargetGroup {

    private static final List<String> SUPPORTED_PROTOCOLS = List.of("HTTP", "HTTPS");

    private final String name;
    private final String protocol;
    private final CopyOnWriteArrayList<AlbTarget> targets;
    private final AtomicInteger counter = new AtomicInteger(0);

    private AlbTargetGroup(String name, String protocol, List<AlbTarget> targets) {
        this.name = name;
        this.protocol = protocol;
        this.targets = new CopyOnWriteArrayList<>(targets);
    }

    public String name() { return name; }
    public String protocol() { return protocol; }

    public Optional<AlbTarget> nextTarget() {
        List<AlbTarget> healthy = targets.stream().filter(AlbTarget::isRoutable).toList();
        if (healthy.isEmpty()) return Optional.empty();
        int idx = Math.abs(counter.getAndIncrement() % healthy.size());
        return Optional.of(healthy.get(idx));
    }

    public void registerTarget(AlbTarget target) {
        targets.add(Objects.requireNonNull(target, "target"));
    }

    public void updateHealth(String host, int port, AlbTarget.TargetHealth health) {
        for (int i = 0; i < targets.size(); i++) {
            AlbTarget t = targets.get(i);
            if (t.host().equals(host) && t.port() == port) {
                targets.set(i, t.withHealth(health));
                return;
            }
        }
    }

    public List<AlbTarget> targets() {
        return List.copyOf(targets);
    }

    public void deregisterTarget(String host, int port) {
        targets.removeIf(t -> t.host().equals(host) && t.port() == port);
    }

    public int healthyCount() {
        return (int) targets.stream().filter(AlbTarget::isRoutable).count();
    }

    public int totalCount() {
        return targets.size();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String protocol = "HTTP";
        private final List<AlbTarget> targets = new ArrayList<>();

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            this.name = name;
        }

        public Builder protocol(String protocol) {
            String upper = Objects.requireNonNull(protocol, "protocol").toUpperCase(Locale.ROOT);
            if (!SUPPORTED_PROTOCOLS.contains(upper)) {
                throw new IllegalArgumentException(
                        "ALB only supports HTTP and HTTPS, got: " + protocol);
            }
            this.protocol = upper;
            return this;
        }

        public Builder target(String host, int port) {
            targets.add(AlbTarget.of(host, port));
            return this;
        }

        public Builder target(AlbTarget target) {
            targets.add(Objects.requireNonNull(target, "target"));
            return this;
        }

        public AlbTargetGroup build() {
            return new AlbTargetGroup(name, protocol, targets);
        }
    }
}
