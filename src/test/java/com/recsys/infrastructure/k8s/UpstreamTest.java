package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The conformance test is only as good as its address derivation: a key whose value it
 * silently fails to parse becomes an upstream it silently stops requiring a rule for.
 */
class UpstreamTest {

    @Test
    void parsesHttpServiceUrl() {
        Map<String, String> cfg = Map.of("CATALOG_SERVICE_URL", "http://recsys-catalog-serving:6010");
        assertThat(Upstream.parse("CATALOG_SERVICE_URL", cfg))
                .containsExactly(new Upstream("recsys-catalog-serving", 6010));
    }

    @Test
    void defaultsSchemePortWhenUrlOmitsIt() {
        Map<String, String> cfg = Map.of("A_SERVICE_URL", "https://example.internal");
        assertThat(Upstream.parse("A_SERVICE_URL", cfg))
                .containsExactly(new Upstream("example.internal", 443));
    }

    @Test
    void parsesJdbcMysqlUrl() {
        Map<String, String> cfg = Map.of("MYSQL_URL", "jdbc:mysql://mysql:3306/recsys");
        assertThat(Upstream.parse("MYSQL_URL", cfg))
                .containsExactly(new Upstream("mysql", 3306));
    }

    @Test
    void pairsRedisHostWithRedisPort() {
        // REDIS_PORT deliberately differs from Upstream.parse's own 6379 fallback default, so
        // this fails against an implementation that ignores REDIS_PORT and hardcodes 6379 —
        // proving the port is actually read rather than coincidentally matching a default.
        Map<String, String> cfg = Map.of("REDIS_HOST", "redis", "REDIS_PORT", "6380");
        assertThat(Upstream.parse("REDIS_HOST", cfg))
                .containsExactly(new Upstream("redis", 6380));
    }

    /**
     * A *_SERVICE_URL value written without a scheme (e.g. a bare "host:port") parses to a URI
     * whose getHost() is null. Without a guard, downstream label resolution NPEs on host.split()
     * with a message naming neither the offending key nor its value.
     */
    @Test
    void schemeLessServiceUrlThrowsWithKeyInMessage() {
        Map<String, String> cfg = Map.of("CATALOG_SERVICE_URL", "recsys-catalog-serving:6010");
        assertThatThrownBy(() -> Upstream.parse("CATALOG_SERVICE_URL", cfg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CATALOG_SERVICE_URL");
    }

    @Test
    void splitsCommaSeparatedNodeLists() {
        Map<String, String> cfg = Map.of("REDIS_SENTINEL_NODES",
                "redis-sentinel-0.redis-sentinel-headless.recsys.svc.cluster.local:26379,"
                        + "redis-sentinel-1.redis-sentinel-headless.recsys.svc.cluster.local:26379");
        assertThat(Upstream.parse("REDIS_SENTINEL_NODES", cfg)).containsExactly(
                new Upstream("redis-sentinel-0.redis-sentinel-headless.recsys.svc.cluster.local", 26379),
                new Upstream("redis-sentinel-1.redis-sentinel-headless.recsys.svc.cluster.local", 26379));
    }

    @Test
    void parsesBootstrapServers() {
        Map<String, String> cfg = Map.of("OUTBOX_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        assertThat(Upstream.parse("OUTBOX_KAFKA_BOOTSTRAP_SERVERS", cfg))
                .containsExactly(new Upstream("kafka", 9092));
    }

    /**
     * SAGA_EVENTS_SQS_QUEUE_URL and GATEWAY_COGNITO_ISSUER are both "" in k8s/base. A blank
     * value is a disabled feature, not an upstream — it must yield no requirement rather than
     * throwing and taking the whole conformance test down with it.
     */
    @Test
    void blankValueYieldsNoUpstream() {
        assertThat(Upstream.parse("SAGA_EVENTS_SQS_QUEUE_URL",
                Map.of("SAGA_EVENTS_SQS_QUEUE_URL", ""))).isEmpty();
        assertThat(Upstream.parse("ABSENT_SERVICE_URL", Map.of())).isEmpty();
    }
}
