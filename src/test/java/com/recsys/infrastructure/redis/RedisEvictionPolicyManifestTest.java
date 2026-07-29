package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis holds a mix of cache and authoritative state, so the eviction policy is a
 * correctness invariant, not a tuning knob.
 *
 * <p>Everything cache-like carries an explicit TTL — Flink features and user embeddings
 * via {@code SETEX}, trending top-K via {@code EXPIRE}, the service registry via
 * {@code PX}, sharded records via {@code EXPIRE}. The keys with <em>no</em> TTL are the
 * authoritative ones: {@code shard:topology} and the classpath-seeded embeddings. Under
 * {@code allkeys-lru} those are eviction candidates, and an evicted {@code shard:topology}
 * is silently recreated at version 1 by {@code ShardTopologyStore.bootstrap} — resetting a
 * resharded cluster's generation and addressing data under the wrong key prefix.
 *
 * <p>{@code volatile-lru} confines eviction to TTL-bearing keys, so memory pressure
 * degrades the cache and fails loudly (OOM on write) rather than silently corrupting state.
 */
class RedisEvictionPolicyManifestTest {

    private static final List<Path> REDIS_MANIFESTS = List.of(
            Path.of("k8s", "base", "redis-cluster.yaml"),
            Path.of("docker-compose.streaming.yml"));

    /** Configuration lines only — an explanatory comment naming a policy is not a setting. */
    private static List<String> settings(Path manifest) throws IOException {
        return Files.readAllLines(manifest).stream()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .toList();
    }

    @Test
    void everyRedisDeclarationEvictsOnlyTtlBearingKeys() throws IOException {
        for (Path manifest : REDIS_MANIFESTS) {
            assertThat(manifest).exists();
            List<String> settings = settings(manifest);

            assertThat(settings)
                    .as("%s must not let Redis evict keys that have no TTL", manifest)
                    .noneMatch(line -> line.contains("allkeys-"));
            assertThat(settings)
                    .as("%s declares maxmemory, so it must also pin the eviction policy", manifest)
                    .anyMatch(line -> line.contains("volatile-lru"));
        }
    }

    /**
     * In EKS the in-cluster Redis StatefulSets are scaled to zero and ElastiCache serves
     * instead, so {@code k8s/base/redis-cluster.yaml} — where the policy is pinned — is
     * inert there. ElastiCache is provisioned out-of-band (this repo has no IaC), so the
     * only thing the repo can enforce is that the requirement is stated where an operator
     * setting up the cluster will read it.
     */
    @Test
    void theElastiCacheOverlaysDocumentTheSameEvictionRequirement() throws IOException {
        List<Path> elastiCachePatches = List.of(
                Path.of("k8s", "eks", "redis-elasticache-patch.yaml"),
                Path.of("k8s", "eks-us-west-2", "redis-elasticache-patch.yaml"));

        for (Path patch : elastiCachePatches) {
            assertThat(patch).exists();
            String content = Files.readString(patch);

            assertThat(content)
                    .as("%s: EKS runs ElastiCache, so the eviction requirement must be stated "
                            + "here — the base manifest's policy does not apply", patch)
                    .contains("maxmemory-policy")
                    .contains("volatile-lru");
        }
    }

    @Test
    void everyDeclaredMaxmemoryIsPairedWithAnEvictionPolicy() throws IOException {
        for (Path manifest : REDIS_MANIFESTS) {
            List<String> settings = settings(manifest);
            long memoryLimits = settings.stream().filter(l -> l.contains("--maxmemory")
                    && !l.contains("--maxmemory-policy")).count();
            long policies = settings.stream().filter(l -> l.contains("--maxmemory-policy")).count();

            assertThat(policies)
                    .as("%s: every --maxmemory needs its own --maxmemory-policy, else Redis "
                            + "falls back to the noeviction default", manifest)
                    .isEqualTo(memoryLimits);
        }
    }
}
