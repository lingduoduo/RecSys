package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
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
     * Workloads that open a Redis connection, mapped to the ACL user each authenticates as. The
     * outbox relay is deliberately absent: it dials MySQL and Kafka only, and granting it a Redis
     * credential would widen its blast radius for nothing.
     *
     * <p>The ACL user name is the join key across three artifacts that must agree or the workload
     * gets {@code WRONGPASS}: {@code REDIS_USERNAME=<user>} in the manifest, the Secret key
     * {@code redis-<user>-password} the manifest reads {@code REDIS_PASSWORD} from, and the
     * {@code >__<USER>_PASSWORD__} placeholder on that user's line in
     * {@code redis-users.acl.template}. {@link #everyRedisClientReceivesItsOwnRedisPassword}
     * checks all three against each other.
     */
    private static final Map<String, String> REDIS_CLIENT_USERS = Map.of(
            "recsys-api-gateway", "gateway",
            "recsys-catalog-serving", "catalog",
            "recsys-model-serving", "model",
            "recsys-online-serving", "online",
            "recsys-outbox-reconciliation", "reconciliation");

    private static final Set<String> REDIS_CLIENTS = REDIS_CLIENT_USERS.keySet();

    /**
     * The {@code default} user's credential, and the one every workload used to share. Redis
     * itself still reads this key ({@code redis-cluster.yaml}'s {@code --requirepass} and
     * {@code REDISCLI_AUTH}), so it must keep existing — but no application workload may point at
     * it again. That regression would not fail anything at runtime: every service would connect
     * successfully, as {@code default}, with the whole keyspace and {@code +@all}, and the entire
     * per-service ACL split would be silently inert.
     */
    private static final String DEFAULT_USER_SECRET_KEY = "redis-password";

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

    /**
     * Each workload must take {@code REDIS_PASSWORD} from <b>its own</b> Secret key, matching the
     * ACL user it authenticates as.
     *
     * <p>This used to require the shared {@code redis-password} key for all five. That was the
     * pre-ACL arrangement and it is now the failure mode rather than the requirement: the ACL
     * template gives every user a distinct {@code __<USER>_PASSWORD__} placeholder, so five
     * workloads sharing one key means four of them get {@code WRONGPASS} — measured against a real
     * server — or, if every placeholder is rendered to the same value, the per-user split is
     * decorative.
     *
     * <p>Pointing a workload back at {@code redis-password} is the quiet version of that: it is
     * the {@code default} user's credential, so the pod starts, connects, and gets {@code ~* +@all}
     * with no error anywhere. Asserted explicitly rather than left to the per-user check, because
     * this is the regression that looks healthiest.
     */
    @Test
    void everyRedisClientReceivesItsOwnRedisPassword() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();
        List<String> problems = new java.util.ArrayList<>();
        Set<String> unseen = new TreeSet<>(REDIS_CLIENTS);
        Map<String, String> keyToWorkload = new java.util.LinkedHashMap<>();

        for (String kind : List.of("Deployment", "CronJob")) {
            for (Map<String, Object> workload : ofKind(docs, kind)) {
                String name = nameOf(workload);
                String user = REDIS_CLIENT_USERS.get(name);
                if (user == null) continue;
                unseen.remove(name);

                String expectedKey = "redis-" + user + "-password";
                List<Map<String, Object>> env = envOf(workload);

                String username = env.stream()
                        .filter(e -> "REDIS_USERNAME".equals(e.get("name")))
                        .map(e -> String.valueOf(e.get("value")))
                        .findFirst().orElse(null);
                if (!user.equals(username)) {
                    problems.add(name + " sets REDIS_USERNAME=" + username + ", expected " + user);
                }

                Map<String, Object> ref = env.stream()
                        .filter(e -> "REDIS_PASSWORD".equals(e.get("name")))
                        .map(e -> mapAt(e, "valueFrom", "secretKeyRef"))
                        .filter(java.util.Objects::nonNull)
                        .findFirst().orElse(null);
                if (ref == null) {
                    problems.add(name + " wires no REDIS_PASSWORD from a Secret at all");
                    continue;
                }
                if (!"recsys-secrets".equals(ref.get("name"))) {
                    problems.add(name + " reads REDIS_PASSWORD from Secret " + ref.get("name"));
                }
                String key = String.valueOf(ref.get("key"));
                if (DEFAULT_USER_SECRET_KEY.equals(key)) {
                    problems.add(name + " reads the shared " + DEFAULT_USER_SECRET_KEY + " key, "
                            + "which is the default user's full-access credential — it would "
                            + "connect successfully and ignore its ACL user entirely");
                } else if (!expectedKey.equals(key)) {
                    problems.add(name + " reads REDIS_PASSWORD from key " + key + ", expected "
                            + expectedKey);
                }
                String clash = keyToWorkload.put(key, name);
                if (clash != null) {
                    problems.add(name + " shares Secret key " + key + " with " + clash);
                }
            }
        }

        for (String name : unseen) {
            problems.add(name + " has no Deployment or CronJob in " + BASE);
        }

        assertThat(problems)
                .as("every Redis client must authenticate as its own ACL user with its own "
                        + "credential. LettuceClientFactory refuses to start without a password, so "
                        + "a missing key is a CrashLoopBackOff at rollout; a wrong-but-present key "
                        + "is WRONGPASS; and the shared default-user key is the silent one, where "
                        + "everything connects and the ACL split does nothing")
                .isEmpty();
    }

    /**
     * The Secret key each workload names must have a matching user line in the ACL template, and
     * vice versa. A rename on either side is invisible until rollout, where it presents as
     * {@code WRONGPASS} from one service while the other four are healthy.
     */
    @Test
    void everyWorkloadsSecretKeyHasAMatchingAclUserPlaceholder() throws IOException {
        Path template = BASE.resolve("redis-users.acl.template");
        Set<String> placeholders = new TreeSet<>();
        for (String line : Files.readAllLines(template)) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("user ")) continue;
            String[] tokens = trimmed.split("\\s+");
            for (String token : tokens) {
                if (token.startsWith(">__")) placeholders.add(tokens[1] + " " + token);
            }
        }

        Set<String> expected = new TreeSet<>();
        for (String user : REDIS_CLIENT_USERS.values()) {
            expected.add(user + " >__" + user.toUpperCase(java.util.Locale.ROOT) + "_PASSWORD__");
        }
        expected.add("default >__REDIS_PASSWORD__");

        assertThat(placeholders)
                .as("%s must define exactly one placeholder-password line per ACL user, named for "
                        + "that user. The workload manifests read redis-<user>-password from "
                        + "recsys-secrets, so whoever renders this template has to produce one "
                        + "Secret key per placeholder — a mismatch is WRONGPASS at rollout", template)
                .isEqualTo(expected);
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

    /**
     * The guard has an opt-out, and an opt-out with no detector is a matter of time. Sharp edge 2
     * of 20_AuthN_AuthZ is this exact drift, already realised once: {@code GATEWAY_ALLOW_ANONYMOUS}
     * sits in the base configmap and only the eks-shared component turns it off, so an overlay that
     * composes base alone inherits anonymous access silently. The realistic path here is an operator
     * adding {@code REDIS_ALLOW_NO_AUTH} to a configmap while debugging a crash-loop and leaving it;
     * the guard cannot object, because the flag is exactly how you tell it not to.
     *
     * <p>Every overlay is scanned, not just base — an overlay is where the flag would be added, and
     * it is where the other tests here do not look. Comment lines are skipped so the manifests can
     * still say why the flag is absent.
     */
    @Test
    void noManifestOptsOutOfRedisAuthentication() throws IOException {
        Set<String> offenders = new TreeSet<>();
        try (var paths = Files.walk(Path.of("k8s"))) {
            for (Path p : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .toList()) {
                for (String line : Files.readAllLines(p)) {
                    if (line.strip().startsWith("#")) continue;
                    if (line.contains("REDIS_ALLOW_NO_AUTH")) offenders.add(p.toString());
                }
            }
        }

        assertThat(offenders)
                .as("REDIS_ALLOW_NO_AUTH is the escape hatch for a passwordless local Redis. In a "
                        + "cluster it silently restores the unauthenticated default-user connection "
                        + "this change exists to end, and it does it without a single failing pod — "
                        + "which is why it must not be reachable from any manifest. If a cluster "
                        + "genuinely has no Redis password, fix the Secret")
                .isEmpty();
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
