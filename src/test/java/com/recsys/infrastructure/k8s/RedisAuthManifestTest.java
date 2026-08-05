package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.recsys.infrastructure.k8s.ManifestDocuments.listOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.mapAt;
import static com.recsys.infrastructure.k8s.ManifestDocuments.nameOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.ofKind;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis holds the embeddings, device history, the service registry and the login-token to
 * API-key mapping, and until 2026-08 every service reached it as the unauthenticated default
 * user — REDIS_PASSWORD was supported by LettuceClientFactory and set by no manifest.
 *
 * <p>This is the drift catcher for that. The runtime guard refuses to start without a credential,
 * so a workload that loses its REDIS_PASSWORD wiring fails loudly rather than silently
 * downgrading — but it fails in a cluster, at rollout time. This fails in CI instead.
 */
class RedisAuthManifestTest {

    private static final Path BASE = Path.of("k8s", "base");

    /**
     * Workloads that open a Redis connection. The outbox relay is deliberately absent: it dials
     * MySQL and Kafka only, and granting it a Redis credential would widen its blast radius for
     * nothing.
     */
    private static final Set<String> REDIS_CLIENTS = Set.of(
            "recsys-api-gateway", "recsys-catalog-serving", "recsys-model-serving",
            "recsys-online-serving", "recsys-outbox-reconciliation");

    private static List<Map<String, Object>> baseDocuments() throws IOException {
        return ManifestDocuments.allIn(BASE);
    }

    /** Container env entries across Deployments, CronJobs and StatefulSets, by workload name. */
    private static List<Map<String, Object>> envOf(Map<String, Object> workload) {
        Map<String, Object> podSpec = mapAt(workload, "spec", "template", "spec");
        if (podSpec == null) {
            // CronJob: spec.jobTemplate.spec.template.spec
            podSpec = mapAt(workload, "spec", "jobTemplate", "spec", "template", "spec");
        }
        return listOf(podSpec, "containers").stream()
                .flatMap(c -> listOf(c, "env").stream())
                .toList();
    }

    @Test
    void everyRedisClientReceivesTheRedisPassword() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();

        Set<String> missing = new TreeSet<>(REDIS_CLIENTS);
        for (String kind : List.of("Deployment", "CronJob")) {
            for (Map<String, Object> workload : ofKind(docs, kind)) {
                if (!REDIS_CLIENTS.contains(nameOf(workload))) continue;
                boolean wired = envOf(workload).stream().anyMatch(e -> {
                    if (!"REDIS_PASSWORD".equals(e.get("name"))) return false;
                    Map<String, Object> ref = mapAt(e, "valueFrom", "secretKeyRef");
                    return ref != null && "recsys-secrets".equals(ref.get("name"))
                            && "redis-password".equals(ref.get("key"));
                });
                if (wired) missing.remove(nameOf(workload));
            }
        }

        assertThat(missing)
                .as("these workloads dial Redis with no REDIS_PASSWORD wired from the "
                        + "redis-password key of recsys-secrets. LettuceClientFactory refuses to "
                        + "start without it, so each of these is a CrashLoopBackOff at rollout — "
                        + "and before the guard existed it was a silent unauthenticated connection")
                .isEmpty();
    }

    @Test
    void theOutboxRelayIsNotGivenARedisCredential() throws IOException {
        Map<String, Object> relay = ofKind(baseDocuments(), "Deployment").stream()
                .filter(d -> "recsys-outbox-relay".equals(nameOf(d)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Deployment named recsys-outbox-relay"));

        assertThat(envOf(relay).stream().map(e -> e.get("name")))
                .as("the relay dials MySQL and Kafka only. Handing it a Redis credential widens "
                        + "the blast radius of a relay compromise for no functional gain; if the "
                        + "relay genuinely starts using Redis, add it to REDIS_CLIENTS in the same "
                        + "commit that adds the code")
                .doesNotContain("REDIS_PASSWORD");
    }
}
