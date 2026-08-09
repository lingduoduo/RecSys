package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each workload's Redis ACL user may write exactly the prefixes its code writes.
 *
 * <p>Before this, all five Redis clients authenticated as {@code default} with one shared
 * password and the whole keyspace. The gateway could overwrite item embeddings; model serving,
 * which writes nothing at all, could {@code FLUSHALL}.
 *
 * <p>The interesting direction is over-permission, and it is invisible to any test that asks
 * "can this service reach what it needs" — a user granted {@code ~*} passes that trivially,
 * which is exactly the state this replaces. So the assertion is equality: the write patterns in
 * {@code redis-users.acl.template} must equal {@link #EXPECTED_WRITE_PATTERNS}, neither more nor
 * less.
 *
 * <p>{@code default} is deliberately still in the template — {@code requirepass} has no per-user
 * meaning in ACL SETUSER syntax, so a Redis 6+ aclfile needs an explicit {@code default} line or
 * authentication is silently disabled for any client that doesn't send {@code AUTH <user> <pass>}
 * (the Flink job, and any legacy client that predates this rollout, both AUTH as {@code default}
 * with one password). It is not a service user: nothing in this repo sets
 * {@code REDIS_USERNAME=default}, so it is checked separately from the five workload users
 * instead of folded into {@link #EXPECTED_WRITE_PATTERNS}.
 *
 * <p>Scope: this compares two committed files. It cannot see a rendered Secret, a running
 * server, or ElastiCache — the EKS overlays point Redis at ElastiCache, whose RBAC user groups
 * are a different mechanism entirely and are not covered here at all.
 */
class RedisAclManifestTest {

    private static final Path BASE = Path.of("k8s", "base");
    private static final Path TEMPLATE = BASE.resolve("redis-users.acl.template");

    private static final String DEFAULT_USER = "default";

    /**
     * Write access per user, derived from the call graph rather than from prefix names. The
     * audit that prompted this work recorded "cleanly disjoint key ownership"; it is not — three
     * services read the same embedding and trending keyspace, and only writes can be split.
     *
     * <p>{@code topk:} and {@code u2vEmb:} appear in nobody's write set on purpose: no class in
     * src/main/java writes them ({@code ShardedTopKStore} exposes no write method), so the Flink
     * job writes both as {@code default}.
     *
     * <p>{@code default} is intentionally absent from this map — see the class Javadoc.
     */
    private static final Map<String, Set<String>> EXPECTED_WRITE_PATTERNS = Map.of(
            "catalog", Set.of("i2vEmb:*", "u2vEmb:*", "topk:*",
                    "svc:registry:recsys-catalog-serving"),
            "model", Set.of("submit_token:*", "login:*", "topk:*",
                    "svc:registry:recsys-model-serving"),
            "online", Set.of("sr:*", "shard:topology", "rate:online:*", "topk:*",
                    "svc:registry:recsys-online-serving"),
            "gateway", Set.of("svc:registry:recsys-api-gateway"),
            "reconciliation", Set.of());

    /** Which ACL user each Redis-using workload authenticates as. */
    private static final Map<String, String> EXPECTED_USERNAMES = Map.of(
            "recsys-api-gateway", "gateway",
            "recsys-catalog-serving", "catalog",
            "recsys-model-serving", "model",
            "recsys-online-serving", "online",
            "recsys-outbox-reconciliation", "reconciliation");

    @Test
    void everyUsersWriteAccessMatchesWhatItsServiceWrites() throws IOException {
        Map<String, Set<String>> actual = new TreeMap<>();
        for (Map.Entry<String, List<String>> user : usersInTemplate().entrySet()) {
            if (DEFAULT_USER.equals(user.getKey())) continue;
            Set<String> writes = new TreeSet<>();
            for (String rule : user.getValue()) {
                // `~p` is read+write; `%W~p` is write-only. `%R~p` is read-only and is not a
                // write grant. Anything else is a command rule.
                if (rule.startsWith("~")) {
                    writes.add(rule.substring(1));
                } else if (rule.startsWith("%W~")) {
                    writes.add(rule.substring(3));
                } else if (rule.startsWith("%RW~")) {
                    writes.add(rule.substring(4));
                }
            }
            actual.put(user.getKey(), writes);
        }

        // A silently-empty scan would pass this test while proving nothing.
        assertThat(actual)
                .as("no users parsed from %s — the scan found nothing to check", TEMPLATE)
                .isNotEmpty();
        assertThat(actual)
                .as("each ACL user must be able to write exactly what its service writes")
                .isEqualTo(new TreeMap<>(EXPECTED_WRITE_PATTERNS));
    }

    /**
     * A service user that cannot complete a handshake, or that keeps a dangerous command, is
     * broken. {@code default} is excluded: it is deliberately full-access (see class Javadoc)
     * and is covered by {@link #theDefaultUserExistsAndIsFullAccess()} instead.
     */
    @Test
    void everyUserDeniesDangerousCommandsAndPermitsTheHandshake() throws IOException {
        List<String> problems = new ArrayList<>();
        usersInTemplate().forEach((name, rules) -> {
            if (DEFAULT_USER.equals(name)) return;
            if (!rules.contains("-@all")) problems.add(name + " does not start from -@all");
            if (!rules.contains("+@connection")) problems.add(name + " cannot AUTH/HELLO/PING");
            if (!rules.contains("-@dangerous")) problems.add(name + " keeps @dangerous");
            int allIdx = rules.indexOf("-@all");
            int dangerousIdx = rules.indexOf("-@dangerous");
            if (allIdx >= 0 && dangerousIdx >= 0 && dangerousIdx < allIdx) {
                problems.add(name + " applies -@dangerous before -@all, which re-grants it");
            }
        });
        assertThat(problems).isEmpty();
    }

    /**
     * An aclfile that omits {@code default} silently disables Redis authentication entirely for
     * any client that AUTHs as {@code default} (the Flink job, and any client whose Secret
     * predates this rollout) — measured on a real server, and the single worst failure mode on
     * this branch. So {@code default} must exist, and must remain full-access: narrowing it here
     * would silently break the Flink job's writes to {@code u2vEmb:*} and {@code topk:*} without
     * touching a single line of application code.
     */
    @Test
    void theDefaultUserExistsAndIsFullAccess() throws IOException {
        Map<String, List<String>> users = usersInTemplate();
        assertThat(users)
                .as("redis-users.acl.template must define the default user, or Redis "
                        + "authentication is silently disabled for any client that doesn't "
                        + "name a user")
                .containsKey(DEFAULT_USER);

        List<String> rules = users.get(DEFAULT_USER);
        assertThat(rules).contains("~*", "&*", "+@all");
    }

    /** No real credential may be committed; every user carries a placeholder. */
    @Test
    void everyUserPasswordIsAPlaceholder() throws IOException {
        List<String> problems = new ArrayList<>();
        usersInTemplate().forEach((name, rules) -> rules.stream()
                .filter(r -> r.startsWith(">"))
                .forEach(r -> {
                    if (!r.matches(">__[A-Z_]+_PASSWORD__")) {
                        problems.add(name + " has a non-placeholder password rule: " + r);
                    }
                }));
        assertThat(problems).isEmpty();
    }

    /** Every Redis-using workload must name a user the template actually defines. */
    @Test
    void everyWorkloadAuthenticatesAsItsOwnUser() throws IOException {
        Set<String> defined = usersInTemplate().keySet();
        Map<String, String> actual = new TreeMap<>();

        for (Map<String, Object> doc : ManifestDocuments.allIn(BASE)) {
            // Each workload's file also carries a Service document with the identical
            // metadata.name (e.g. both the api-gateway Deployment and its Service are named
            // recsys-api-gateway) — restrict to the two kinds that actually have a pod spec, or
            // the Service doc is picked up too and fails the null check below.
            String kind = String.valueOf(doc.get("kind"));
            if (!"Deployment".equals(kind) && !"CronJob".equals(kind)) continue;
            String name = ManifestDocuments.nameOf(doc);
            if (!EXPECTED_USERNAMES.containsKey(name)) continue;
            String username = envValue(doc, "REDIS_USERNAME");
            assertThat(username)
                    .as("%s sets no REDIS_USERNAME, so it still authenticates as default", name)
                    .isNotNull();
            assertThat(defined)
                    .as("%s names REDIS_USERNAME=%s, which the ACL template does not define",
                            name, username)
                    .contains(username);
            actual.put(name, username);
        }

        assertThat(actual)
                .as("every Redis-using workload must authenticate as its own ACL user")
                .isEqualTo(new TreeMap<>(EXPECTED_USERNAMES));
    }

    /** user name -> its rule tokens, in file order. */
    private static Map<String, List<String>> usersInTemplate() throws IOException {
        Map<String, List<String>> users = new LinkedHashMap<>();
        for (String line : Files.readAllLines(TEMPLATE)) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("user ")) continue;
            String[] tokens = trimmed.split("\\s+");
            List<String> rules = new ArrayList<>(List.of(tokens).subList(2, tokens.length));
            users.put(tokens[1], rules);
        }
        return users;
    }

    /**
     * The literal {@code value:} of an env entry in a workload's containers, or null.
     *
     * <p>A Deployment's pod spec is at {@code spec.template.spec}; a CronJob's is two levels
     * deeper, at {@code spec.jobTemplate.spec.template.spec}. Both are checked so the
     * reconciliation CronJob is covered by the same assertion as the four Deployments.
     */
    private static String envValue(Map<String, Object> doc, String key) {
        List<Map<String, Object>> podSpecs = new ArrayList<>();
        Map<String, Object> deploymentPod = ManifestDocuments.mapAt(doc, "spec", "template", "spec");
        if (deploymentPod != null) podSpecs.add(deploymentPod);
        Map<String, Object> cronPod =
                ManifestDocuments.mapAt(doc, "spec", "jobTemplate", "spec", "template", "spec");
        if (cronPod != null) podSpecs.add(cronPod);

        for (Map<String, Object> podSpec : podSpecs) {
            for (Map<String, Object> container : ManifestDocuments.listOf(podSpec, "containers")) {
                for (Map<String, Object> entry : ManifestDocuments.listOf(container, "env")) {
                    if (key.equals(entry.get("name")) && entry.get("value") != null) {
                        return String.valueOf(entry.get("value"));
                    }
                }
            }
        }
        return null;
    }
}
