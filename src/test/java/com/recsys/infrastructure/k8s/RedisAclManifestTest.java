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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code redis-users.acl.template} must stay byte-for-byte the rule set that was exercised
 * against a real {@code redis-server}.
 *
 * <p>Before this, all five Redis clients authenticated as {@code default} with one shared
 * password and the whole keyspace. The gateway could overwrite item embeddings; model serving,
 * which writes nothing at all, could {@code FLUSHALL}.
 *
 * <p><b>Why the assertion is the exact ordered token list, not a derived summary.</b> The
 * earlier version of this test derived a write-pattern set per user and compared that. It stayed
 * green under five mutations that each break a real server, because a summary throws away
 * exactly the information Redis acts on:
 *
 * <ol>
 *   <li>{@code ~i2vEmb:*} → {@code %W~i2vEmb:*} — same write set, but reads are then denied;</li>
 *   <li>{@code +@scripting} deleted from {@code online} — every {@code EVAL} fails, and no
 *       write pattern changes;</li>
 *   <li>{@code +@dangerous} appended after {@code -@dangerous} — measured: {@code FLUSHALL}
 *       then returns {@code OK}. Command rules apply in file order, so only the order
 *       distinguishes this from the intended rule;</li>
 *   <li>a password rule replaced with {@code nopass} — a check that only inspects rules starting
 *       with {@code >} never sees it, and the user then needs no credential at all;</li>
 *   <li>{@code -@all} moved after the grants — it revokes everything it follows, so the user
 *       can do nothing. Again purely positional.</li>
 * </ol>
 *
 * <p>Three of those five are invisible to any content-only check, so the assertion has to be
 * positional. {@link #EXPECTED_RULES} is therefore the literal token sequence, and changing the
 * template means changing it here and re-running the verification described below.
 *
 * <p>{@code default} is deliberately still in the template — {@code requirepass} has no per-user
 * meaning in ACL SETUSER syntax, so a Redis 6+ aclfile needs an explicit {@code default} line or
 * authentication is silently disabled for any client that doesn't send {@code AUTH <user> <pass>}
 * (the Flink job, and any legacy client that predates this rollout, both AUTH as {@code default}
 * with one password). It is not a service user: nothing in this repo sets
 * {@code REDIS_USERNAME=default}.
 *
 * <p>Scope: this compares two committed files. It cannot see a rendered Secret, a running
 * server, or ElastiCache — the EKS overlays point Redis at ElastiCache, whose RBAC user groups
 * are a different mechanism entirely and are not covered here at all. The grants themselves were
 * derived from {@code docs/system_design/redis-consumer-inventory.md} and then exercised
 * command-by-command against {@code redis-server} 8.6.2 with this template rendered as the
 * {@code --aclfile}; that measurement, not this test, is what establishes that they are
 * sufficient.
 */
class RedisAclManifestTest {

    private static final Path BASE = Path.of("k8s", "base");
    private static final Path TEMPLATE = BASE.resolve("redis-users.acl.template");

    private static final String DEFAULT_USER = "default";

    /**
     * The exact rule tokens of each line, in file order, with the leading {@code user <name>}
     * stripped. Every entry was derived from the per-user roll-up in
     * {@code docs/system_design/redis-consumer-inventory.md} §4 and verified against a live
     * server.
     *
     * <p>Notes that a future narrowing must not lose:
     *
     * <ul>
     *   <li>{@code ~topk:*} is read-write for all three serving users even though none of them
     *       writes a trending key. {@code ShardedTopKStore}'s canonical-snapshot script passes
     *       {@code topk:{<window>}:value} and {@code :version} as {@code KEYS}, and Redis
     *       requires full read-write permission on every key handed to a script — a read-only
     *       one included. {@code %R~topk:*} fails at {@code EVAL} time.</li>
     *   <li>{@code ~sr:*} and {@code ~rate:online:*} are deliberately at the bare namespace
     *       because <b>two Lua scripts touch keys they never declare in {@code KEYS}</b>. The
     *       online rate limiter declares {@code rate:online:<bucket>} and then builds
     *       {@code KEYS[1]..':'..windowId} inside the script; {@code ShardedRecordStore}'s
     *       write script declares {@code sr:seq:}, {@code sr:dev:} and {@code sr:stream:} and
     *       builds {@code sr:rec:<shard>:<seq>} from {@code ARGV[1]}. Narrowing either glob to
     *       the declared keys compiles, passes review, and fails only at runtime — the declared
     *       {@code KEYS} list does not reveal the gap. {@code ~sr:*} also has to cover both
     *       shard key formats ({@code sr:dev:0:x} and the hash-tagged {@code sr:dev:{0}:x}) and
     *       the {@code sr:g<v>:} generation prefixes.</li>
     *   <li>{@code online} carries {@code +info} <i>after</i> {@code -@dangerous}, and the order
     *       is the point: {@code INFO} carries {@code @dangerous}, so the blanket revoke strips
     *       it and {@code RedisCacheStatsProbe} silently reports {@code unavailable} without the
     *       explicit re-grant.</li>
     *   <li>{@code %R~*:updated_at} is read-only on purpose: {@code RedisFeatureVersionSampler}
     *       only ever {@code SCAN}s and {@code GET}s those keys; the Flink job writes them as
     *       {@code default}.</li>
     *   <li>{@code online} is <b>not</b> granted a keyspace-wide read.
     *       {@code RedisPersistentKeyProbe} {@code SCAN}s the whole keyspace and {@code TTL}s
     *       every key it sees, and SCAN is not ACL-filtered (measured), so a foreign key on a
     *       page makes its whole sample return {@code unavailable}. Granting {@code %R~*} to fix
     *       that would hand online serving read access to {@code login:*} and
     *       {@code submit_token:*}; the degraded probe is the cheaper loss. See the design doc.
     *       </li>
     *   <li>{@code gateway} has no {@code +@write} and no {@code ~svc:registry:recsys-api-gateway}:
     *       it has no {@code ServiceRegistrar} and {@code MGET} is its only Redis command.
     *       {@code reconciliation} has no {@code user:*:recent_movies} pattern: that string is
     *       the <i>member</i> of a {@code SISMEMBER}, not a key, and ACL key patterns never see
     *       members.</li>
     * </ul>
     */
    private static final Map<String, List<String>> EXPECTED_RULES = Map.of(
            "default", List.of(
                    "on", ">__REDIS_PASSWORD__", "~*", "&*", "+@all"),
            "catalog", List.of(
                    "on", ">__CATALOG_PASSWORD__",
                    "-@all", "+@read", "+@write", "+@connection", "+@scripting", "-@dangerous",
                    "~i2vEmb:*", "~u2vEmb:*", "~topk:*", "~svc:registry:recsys-catalog-serving",
                    "%R~global:item_popularity"),
            "model", List.of(
                    "on", ">__MODEL_PASSWORD__",
                    "-@all", "+@read", "+@write", "+@connection", "+@scripting", "-@dangerous",
                    "~submit_token:*", "~login:*", "~topk:*", "~svc:registry:recsys-model-serving",
                    "%R~i2vEmb:*", "%R~u2vEmb:*", "%R~user:*:recent_movies",
                    "%R~global:item_popularity"),
            "online", List.of(
                    "on", ">__ONLINE_PASSWORD__",
                    "-@all", "+@read", "+@write", "+@connection", "+@scripting", "-@dangerous",
                    "+info",
                    "~sr:*", "~shard:topology", "~rate:online:*", "~topk:*", "~bias:item:*",
                    "~recsys:replica-lag-probe:*", "~svc:registry:recsys-online-serving",
                    "%R~u2vEmb:*", "%R~user:*:recent_movies", "%R~global:item_popularity",
                    "%R~lineage:event:*", "%R~*:updated_at"),
            "gateway", List.of(
                    "on", ">__GATEWAY_PASSWORD__",
                    "-@all", "+@read", "+@connection", "-@dangerous",
                    "%R~svc:registry:*"),
            "reconciliation", List.of(
                    "on", ">__RECONCILIATION_PASSWORD__",
                    "-@all", "+@read", "+@connection", "-@dangerous",
                    "%R~lineage:event:*"));

    /** Which ACL user each Redis-using workload authenticates as. */
    private static final Map<String, String> EXPECTED_USERNAMES = Map.of(
            "recsys-api-gateway", "gateway",
            "recsys-catalog-serving", "catalog",
            "recsys-model-serving", "model",
            "recsys-online-serving", "online",
            "recsys-outbox-reconciliation", "reconciliation");

    /**
     * The whole point of the rewrite: the assertion is the literal token sequence, so any of the
     * five mutations above changes it and fails here with the offending user named.
     */
    @Test
    void everyUsersRuleTokensAreExactlyWhatWasVerifiedAgainstARealServer() throws IOException {
        Map<String, List<String>> actual = usersInTemplate();

        // A silently-empty scan would pass an equality check against an empty expectation.
        assertThat(actual)
                .as("no users parsed from %s — the scan found nothing to check", TEMPLATE)
                .isNotEmpty();

        assertThat(actual.keySet())
                .as("%s defines a different set of users than this test verified", TEMPLATE)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_RULES.keySet());

        for (Map.Entry<String, List<String>> expected : new TreeMap<>(EXPECTED_RULES).entrySet()) {
            assertThat(actual.get(expected.getKey()))
                    .as("ACL rules for user '%s' differ from the verified set. Rule order is "
                            + "load-bearing: Redis applies command rules left to right, so "
                            + "moving -@all after the grants revokes them and appending "
                            + "+@dangerous after -@dangerous re-grants FLUSHALL. If this change "
                            + "is intended, re-run the per-user verification against a real "
                            + "redis-server before updating this list.", expected.getKey())
                    .containsExactlyElementsOf(expected.getValue());
        }
    }

    /**
     * Redis aborts startup on any non-blank aclfile line that does not begin with {@code user},
     * so this file cannot carry comments — including the ones a reader would most want. Anything
     * explanatory has to live in this test or in the design doc instead.
     */
    @Test
    void theTemplateContainsOnlyUserLines() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> lines = Files.readAllLines(TEMPLATE);
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (trimmed.isEmpty() || trimmed.startsWith("user ")) continue;
            offenders.add((i + 1) + ": " + trimmed);
        }
        assertThat(offenders)
                .as("redis-server refuses to start on a non-'user' aclfile line, comments "
                        + "included")
                .isEmpty();
    }

    /**
     * A service user that cannot complete a handshake, or that keeps a dangerous command, is
     * broken. Redundant with the exact-token assertion by construction, and kept anyway because
     * it names the specific failure rather than printing a token diff — and because it is the
     * check a newly added user would want first.
     */
    @Test
    void everyUserDeniesDangerousCommandsAndPermitsTheHandshake() throws IOException {
        List<String> problems = new ArrayList<>();
        usersInTemplate().forEach((name, rules) -> {
            if (DEFAULT_USER.equals(name)) return;
            if (!rules.contains("-@all")) problems.add(name + " does not start from -@all");
            if (!rules.contains("+@connection")) problems.add(name + " cannot AUTH/HELLO/PING");
            if (!rules.contains("-@dangerous")) problems.add(name + " keeps @dangerous");
            if (rules.contains("+@dangerous")) {
                problems.add(name + " re-grants the whole @dangerous class; only individual "
                        + "commands (+info) may be re-granted");
            }
            int allIdx = rules.indexOf("-@all");
            int dangerousIdx = rules.indexOf("-@dangerous");
            if (allIdx >= 0 && dangerousIdx >= 0 && dangerousIdx < allIdx) {
                problems.add(name + " applies -@dangerous before -@all, which re-grants it");
            }
            // -@all revokes everything to its left, so any grant preceding it is dead.
            for (int i = 0; i < allIdx; i++) {
                String rule = rules.get(i);
                if (rule.startsWith("+") || rule.startsWith("~") || rule.startsWith("%")) {
                    problems.add(name + " grants " + rule + " before -@all, which revokes it");
                }
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

    /**
     * No real credential may be committed, and no user may be passwordless. Checking only rules
     * that start with {@code >} misses {@code nopass} entirely, which is why the positive
     * requirement — exactly one placeholder rule per user — is asserted rather than the negative
     * one.
     */
    @Test
    void everyUserRequiresExactlyOnePlaceholderPassword() throws IOException {
        List<String> problems = new ArrayList<>();
        usersInTemplate().forEach((name, rules) -> {
            List<String> passwordRules = rules.stream().filter(r -> r.startsWith(">")).toList();
            if (passwordRules.size() != 1) {
                problems.add(name + " has " + passwordRules.size() + " password rules, expected 1");
            }
            passwordRules.forEach(r -> {
                if (!r.matches(">__[A-Z_]+_PASSWORD__")) {
                    problems.add(name + " has a non-placeholder password rule: " + r);
                }
            });
            for (String disabler : List.of("nopass", "resetpass")) {
                if (rules.contains(disabler)) {
                    problems.add(name + " carries '" + disabler + "', so it authenticates with "
                            + "no credential at all");
                }
            }
        });
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
