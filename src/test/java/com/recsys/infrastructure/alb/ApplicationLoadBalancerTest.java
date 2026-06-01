package com.recsys.infrastructure.alb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationLoadBalancerTest {

    private ApplicationLoadBalancer alb;

    @BeforeEach
    void setUp() {
        alb = ApplicationLoadBalancer.builder()
                .targetGroup(AlbTargetGroup.builder("recsys-serving")
                        .protocol("HTTP")
                        .target("10.0.1.1", 6010)
                        .target("10.0.1.2", 6010)
                        .build())
                .targetGroup(AlbTargetGroup.builder("model-serving")
                        .protocol("HTTP")
                        .target("10.0.2.1", 8080)
                        .build())
                .targetGroup(AlbTargetGroup.builder("online-serving")
                        .protocol("HTTP")
                        .target("10.0.3.1", 7010)
                        .build())
                .listener(AlbListener.builder()
                        .protocol("HTTP")
                        .port(80)
                        .rule(ListenerRule.builder()
                                .priority(1)
                                .condition(PathPatternCondition.of("/api/models/*", "/api/ranking/*"))
                                .forwardTo("model-serving")
                                .build())
                        .rule(ListenerRule.builder()
                                .priority(2)
                                .condition(PathPatternCondition.of("/api/online/*"))
                                .forwardTo("online-serving")
                                .build())
                        .defaultRule("recsys-serving")
                        .build())
                .build();
    }

    // ── Path-pattern routing ──────────────────────────────────────────────────

    @Test
    void routesModelPathToModelServingGroup() {
        Optional<AlbTarget> target = alb.route(80, "/api/models/predict", "example.com", "POST");
        assertThat(target).isPresent();
        assertThat(target.get().host()).isEqualTo("10.0.2.1");
    }

    @Test
    void routesRankingPathToModelServingGroup() {
        Optional<AlbTarget> target = alb.route(80, "/api/ranking/score", "example.com", "POST");
        assertThat(target).isPresent();
        assertThat(target.get().host()).isEqualTo("10.0.2.1");
    }

    @Test
    void routesOnlinePathToOnlineServingGroup() {
        Optional<AlbTarget> target = alb.route(80, "/api/online/predict", "example.com", "GET");
        assertThat(target).isPresent();
        assertThat(target.get().host()).isEqualTo("10.0.3.1");
    }

    @Test
    void unmatched_pathFallsToDefaultRule() {
        Optional<AlbTarget> target = alb.route(80, "/api/recommendations", "example.com", "GET");
        assertThat(target).isPresent();
        assertThat(target.get().host()).isIn("10.0.1.1", "10.0.1.2");
    }

    // ── Priority ordering ────────────────────────────────────────────────────

    @Test
    void higherPriorityRuleWinsWhenBothCouldMatch() {
        // Rule with priority 1 (/api/models/*) should beat priority 2
        ApplicationLoadBalancer overlapping = ApplicationLoadBalancer.builder()
                .targetGroup(AlbTargetGroup.builder("group-a").target("a.host", 80).build())
                .targetGroup(AlbTargetGroup.builder("group-b").target("b.host", 80).build())
                .listener(AlbListener.builder()
                        .protocol("HTTP").port(80)
                        .rule(ListenerRule.builder().priority(1)
                                .condition(PathPatternCondition.of("/api/*"))
                                .forwardTo("group-a").build())
                        .rule(ListenerRule.builder().priority(2)
                                .condition(PathPatternCondition.of("/api/models/*"))
                                .forwardTo("group-b").build())
                        .defaultRule("group-b")
                        .build())
                .build();

        Optional<AlbTarget> target = overlapping.route(80, "/api/models/v1", null, "GET");
        assertThat(target).isPresent();
        assertThat(target.get().host()).isEqualTo("a.host");
    }

    // ── Round-robin load balancing ───────────────────────────────────────────

    @Test
    void roundRobinsAcrossHealthyTargetsInGroup() {
        Set<String> seenHosts = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            alb.route(80, "/api/recommendations", "example.com", "GET")
                    .ifPresent(t -> seenHosts.add(t.host()));
        }
        assertThat(seenHosts).containsExactlyInAnyOrder("10.0.1.1", "10.0.1.2");
    }

    // ── Unhealthy target handling ─────────────────────────────────────────────

    @Test
    void unhealthyTargetIsSkipped() {
        alb.targetGroup("recsys-serving").ifPresent(tg ->
                tg.updateHealth("10.0.1.1", 6010, AlbTarget.TargetHealth.UNHEALTHY));

        for (int i = 0; i < 10; i++) {
            Optional<AlbTarget> target = alb.route(80, "/health", "example.com", "GET");
            assertThat(target).isPresent();
            assertThat(target.get().host()).isEqualTo("10.0.1.2");
        }
    }

    @Test
    void allTargetsUnhealthyReturnsEmpty() {
        alb.targetGroup("recsys-serving").ifPresent(tg -> {
            tg.updateHealth("10.0.1.1", 6010, AlbTarget.TargetHealth.UNHEALTHY);
            tg.updateHealth("10.0.1.2", 6010, AlbTarget.TargetHealth.UNHEALTHY);
        });

        Optional<AlbTarget> target = alb.route(80, "/health", "example.com", "GET");
        assertThat(target).isEmpty();
    }

    @Test
    void drainingTargetIsSkipped() {
        alb.targetGroup("model-serving").ifPresent(tg ->
                tg.updateHealth("10.0.2.1", 8080, AlbTarget.TargetHealth.DRAINING));

        Optional<AlbTarget> target = alb.route(80, "/api/models/predict", "example.com", "POST");
        assertThat(target).isEmpty();
    }

    // ── No matching listener ─────────────────────────────────────────────────

    @Test
    void unknownPortReturnsEmpty() {
        Optional<AlbTarget> target = alb.route(8443, "/api/models/predict", "example.com", "POST");
        assertThat(target).isEmpty();
    }

    // ── Protocol enforcement ─────────────────────────────────────────────────

    @Test
    void rejectsUnsupportedProtocolOnListener() {
        assertThatThrownBy(() ->
                AlbListener.builder().protocol("TCP").port(80).defaultRule("grp").build()
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");
    }

    @Test
    void rejectsUnsupportedProtocolOnTargetGroup() {
        assertThatThrownBy(() ->
                AlbTargetGroup.builder("grp").protocol("gRPC").target("h", 80).build()
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");
    }

    @Test
    void acceptsHttpsProtocol() {
        ApplicationLoadBalancer httpsAlb = ApplicationLoadBalancer.builder()
                .targetGroup(AlbTargetGroup.builder("secure-backend")
                        .protocol("HTTPS")
                        .target("secure.host", 443)
                        .build())
                .listener(AlbListener.builder()
                        .protocol("HTTPS").port(443)
                        .defaultRule("secure-backend")
                        .build())
                .build();

        Optional<AlbTarget> target = httpsAlb.route(443, "/api/secure", null, "GET");
        assertThat(target).isPresent();
        assertThat(target.get().host()).isEqualTo("secure.host");
    }

    // ── Path pattern glob matching ────────────────────────────────────────────

    @Test
    void globStarMatchesAnySequence() {
        assertThat(PathPatternCondition.matchesGlob("/api/*", "/api/models")).isTrue();
        assertThat(PathPatternCondition.matchesGlob("/api/*", "/api/models/v1/predict")).isTrue();
        assertThat(PathPatternCondition.matchesGlob("/api/*", "/other/path")).isFalse();
    }

    @Test
    void globQuestionMatchesExactlyOneChar() {
        assertThat(PathPatternCondition.matchesGlob("/v?/predict", "/v1/predict")).isTrue();
        assertThat(PathPatternCondition.matchesGlob("/v?/predict", "/v12/predict")).isFalse();
        assertThat(PathPatternCondition.matchesGlob("/v?/predict", "/v/predict")).isFalse();
    }

    @Test
    void globExactMatchWithNoWildcard() {
        assertThat(PathPatternCondition.matchesGlob("/health", "/health")).isTrue();
        assertThat(PathPatternCondition.matchesGlob("/health", "/health/ready")).isFalse();
    }

    @Test
    void globSuffixWildcard() {
        assertThat(PathPatternCondition.matchesGlob("*.jpg", "/images/photo.jpg")).isTrue();
        assertThat(PathPatternCondition.matchesGlob("*.jpg", "/images/photo.png")).isFalse();
    }

    // ── Builder validation ────────────────────────────────────────────────────

    @Test
    void listenerRequiresDefaultRule() {
        assertThatThrownBy(() ->
                AlbListener.builder().protocol("HTTP").port(80).build()
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ruleRequiresPriorityAndConditionAndTarget() {
        assertThatThrownBy(() ->
                ListenerRule.builder().forwardTo("group").build()
        ).isInstanceOf(IllegalStateException.class).hasMessageContaining("priority");
    }

    @Test
    void duplicateListenerPortRejected() {
        assertThatThrownBy(() ->
                ApplicationLoadBalancer.builder()
                        .targetGroup(AlbTargetGroup.builder("g").target("h", 80).build())
                        .listener(AlbListener.builder().protocol("HTTP").port(80).defaultRule("g").build())
                        .listener(AlbListener.builder().protocol("HTTP").port(80).defaultRule("g").build())
                        .build()
        ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
    }

    @Test
    void duplicateTargetGroupNameRejected() {
        assertThatThrownBy(() ->
                ApplicationLoadBalancer.builder()
                        .targetGroup(AlbTargetGroup.builder("same").target("h1", 80).build())
                        .targetGroup(AlbTargetGroup.builder("same").target("h2", 80).build())
                        .listener(AlbListener.builder().protocol("HTTP").port(80).defaultRule("same").build())
                        .build()
        ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
    }
}
