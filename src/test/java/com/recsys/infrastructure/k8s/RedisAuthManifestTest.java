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

    /**
     * Client-side credentials are worthless if the server accepts anonymous connections. Each of
     * these three has a different failure mode when missing: no requirepass and Redis is open;
     * no masterauth and replication stops, so replicas serve indefinitely stale reads rather than
     * failing; no sentinel auth-pass and the sentinels never reach quorum, which presents as a
     * Redis outage rather than as an auth error.
     */
    @Test
    void theRedisServersRequireAuthentication() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();

        Map<String, List<String>> argsByName = new java.util.LinkedHashMap<>();
        for (Map<String, Object> sts : ofKind(docs, "StatefulSet")) {
            List<String> args = listOf(mapAt(sts, "spec", "template", "spec"), "containers").stream()
                    .flatMap(c -> ManifestDocuments.stringListOf(c, "args").stream())
                    .toList();
            argsByName.put(nameOf(sts), args);
        }

        assertThat(argsByName.get("redis-primary"))
                .as("the primary must set --requirepass immediately followed by $(REDIS_PASSWORD), "
                        + "or it accepts anonymous connections regardless of what every client is "
                        + "configured to send — mere presence of the flag isn't enough, since a "
                        + "hardcoded string, an empty value, or a typo'd substitution would also "
                        + "satisfy a containment check")
                .containsSequence("--requirepass", "$(REDIS_PASSWORD)");
        assertThat(argsByName.get("redis-replica"))
                .as("the replica must set --requirepass (it serves reads) and --masterauth "
                        + "(it authenticates to the primary), each immediately followed by "
                        + "$(REDIS_PASSWORD). Without masterauth replication stops and the replica "
                        + "serves indefinitely stale data instead of failing; a flag paired with the "
                        + "wrong or a hardcoded value fails just as silently as a missing flag, so "
                        + "checking adjacency catches what mere containment would not")
                .containsSequence("--requirepass", "$(REDIS_PASSWORD)")
                .containsSequence("--masterauth", "$(REDIS_PASSWORD)");

        String sentinelConf = ofKind(docs, "ConfigMap").stream()
                .filter(c -> "redis-sentinel-config".equals(nameOf(c)))
                .findFirst()
                .map(c -> String.valueOf(mapAt(c, "data").get("sentinel-template.conf")))
                .orElseThrow(() -> new AssertionError("no ConfigMap named redis-sentinel-config"));

        assertThat(sentinelConf)
                .as("the sentinel template must carry the full auth-pass line with its placeholder "
                        + "intact, or the sentinels cannot authenticate to the primary, never reach "
                        + "quorum, and never fail over — which looks exactly like a Redis outage. "
                        + "Checking only the 'sentinel auth-pass mymaster' prefix would still pass if "
                        + "the __REDIS_PASSWORD__ placeholder were accidentally dropped")
                .contains("sentinel auth-pass mymaster __REDIS_PASSWORD__");
    }

    @Test
    void redisProbesDoNotPutThePasswordOnTheCommandLine() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (Map<String, Object> sts : ofKind(baseDocuments(), "StatefulSet")) {
            for (Map<String, Object> container : listOf(mapAt(sts, "spec", "template", "spec"), "containers")) {
                for (String probe : List.of("readinessProbe", "livenessProbe")) {
                    List<String> cmd = ManifestDocuments.stringListOf(
                            mapAt(container, probe, "exec"), "command");
                    if (cmd.contains("-a")) offenders.add(nameOf(sts) + "." + probe);
                }
            }
        }

        assertThat(offenders)
                .as("a redis-cli -a flag puts the password in the process table and echoes it in "
                        + "probe failure output. Set REDISCLI_AUTH in the container env instead")
                .isEmpty();
    }
}
