package com.recsys.infrastructure.alb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class AlbListener {

    private static final List<String> SUPPORTED_PROTOCOLS = List.of("HTTP", "HTTPS");

    private final String protocol;
    private final int port;
    // sorted: explicit rules by ascending priority, then the single default rule last
    private final List<ListenerRule> rules;

    private AlbListener(String protocol, int port, List<ListenerRule> rules) {
        this.protocol = protocol;
        this.port = port;
        this.rules = List.copyOf(rules);
    }

    public String protocol() { return protocol; }
    public int port() { return port; }

    public Optional<String> route(String path, String host, String method) {
        return rules.stream()
                .filter(r -> r.evaluate(path, host, method))
                .map(ListenerRule::targetGroupName)
                .findFirst();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String protocol;
        private int port = -1;
        private final List<ListenerRule> explicitRules = new ArrayList<>();
        private String defaultTargetGroup;

        public Builder protocol(String protocol) {
            String upper = Objects.requireNonNull(protocol, "protocol").toUpperCase(Locale.ROOT);
            if (!SUPPORTED_PROTOCOLS.contains(upper)) {
                throw new IllegalArgumentException(
                        "ALB only supports HTTP and HTTPS, got: " + protocol);
            }
            this.protocol = upper;
            return this;
        }

        public Builder port(int port) {
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
            this.port = port;
            return this;
        }

        public Builder rule(ListenerRule rule) {
            Objects.requireNonNull(rule, "rule");
            if (rule.isDefault()) throw new IllegalArgumentException("use defaultRule() for the default rule");
            explicitRules.add(rule);
            return this;
        }

        public Builder defaultRule(String targetGroupName) {
            this.defaultTargetGroup = Objects.requireNonNull(targetGroupName, "targetGroupName");
            return this;
        }

        public AlbListener build() {
            if (protocol == null) throw new IllegalStateException("protocol is required");
            if (port == -1) throw new IllegalStateException("port is required");
            if (defaultTargetGroup == null) throw new IllegalStateException("defaultRule is required");

            List<ListenerRule> ordered = new ArrayList<>(explicitRules);
            ordered.sort(Comparator.comparingInt(ListenerRule::priority));
            ordered.add(ListenerRule.defaultRule(defaultTargetGroup));
            return new AlbListener(protocol, port, ordered);
        }
    }
}
