package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.recsys.infrastructure.k8s.ManifestDocuments.listOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.mapAt;
import static com.recsys.infrastructure.k8s.ManifestDocuments.nameOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.ofKind;
import static com.recsys.infrastructure.k8s.ManifestDocuments.stringListOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * k8s/base/network-policy.yaml is this repo's only L3/L4 access-control list, and
 * docs/system_design/20_AuthN_AuthZ.md rests the whole backend trust model on it: 6010, 7010
 * and 8080 authenticate nobody *because* the policy proves the gateway is their only reachable
 * caller. That argument only holds while the policy's destination set matches the set the
 * services actually dial — and before this test it did not, in six places at once.
 *
 * <p>Egress drift is uniquely nasty because its failure modes are quiet. A blocked service
 * registry silently falls back to static routes and looks like a registry that resolved
 * nothing; a blocked sentinel connection fails at startup in a way that reads as a Redis
 * outage. So the addresses are <em>derived</em> from k8s/base/configmap.yaml rather than
 * restated here: change an upstream's address and the requirement follows it.
 *
 * <p>Ownership cannot be derived the same way — recsys-config is a single ConfigMap
 * envFrom'd into all five workloads, so every service receives LLM_SERVICE_URL and MYSQL_URL
 * in its environment whether or not it dials them. {@link #OWNED_KEYS} is that missing half,
 * and {@link #everyConfigMapUpstreamKeyIsClaimed} is what stops it going stale.
 */
class NetworkPolicyEgressManifestTest {

    private static final Path BASE = Path.of("k8s", "base");

    private static final Path EKS_SHARED = Path.of("k8s", "eks-shared");

    /** Which ConfigMap keys each workload actually dials. See the class comment for why. */
    static final Map<String, Set<String>> OWNED_KEYS = Map.of(
            // The gateway proxies to all three backends and the LLM, and opens its own Redis
            // connection (LettuceClientFactory.routingFromEnv) when SERVICE_REGISTRY_ENABLED
            // is true — which in base means the sentinel path, since REDIS_MODE is "sentinel".
            "recsys-api-gateway", Set.of(
                    "CATALOG_SERVICE_URL", "MODEL_SERVICE_URL", "ONLINE_SERVICE_URL",
                    "USER_PROFILE_SERVICE_URL", "MOVIE_METADATA_SERVICE_URL",
                    "FEATURE_SERVICE_URL", "RECOMMENDATION_RETRIEVAL_SERVICE_URL",
                    "RANKING_SERVICE_URL", "AGENT_WORKFLOW_SERVICE_URL",
                    "OBSERVABILITY_SERVICE_URL", "LLM_SERVICE_URL",
                    "LLM_EXPLANATION_SERVICE_URL", "REDIS_HOST", "REDIS_SENTINEL_NODES"),
            "recsys-catalog-serving", Set.of("REDIS_HOST", "REDIS_SENTINEL_NODES"),
            "recsys-model-serving", Set.of("REDIS_HOST", "REDIS_SENTINEL_NODES"),
            // Online serving writes the outbox rows when MYSQL_ENABLED=true.
            "recsys-online-serving", Set.of("REDIS_HOST", "REDIS_SENTINEL_NODES", "MYSQL_URL"),
            "recsys-outbox-relay", Set.of(
                    "MYSQL_URL", "OUTBOX_KAFKA_BOOTSTRAP_SERVERS", "SAGA_EVENTS_SQS_QUEUE_URL"));

    /**
     * Hosts k8s/base names but does not deploy, so no Service can resolve their pod labels.
     * Resolution is otherwise strict — see {@link #destinationLabels}.
     */
    static final Set<String> EXTERNALLY_DEPLOYED = Set.of("ollama", "mysql", "kafka");

    /** Key shapes that name a network destination. Anything matching these needs an owner. */
    private static final List<String> UPSTREAM_KEY_SUFFIXES =
            List.of("_SERVICE_URL", "_HOST", "_URL", "_NODES", "_BOOTSTRAP_SERVERS");

    private static List<Map<String, Object>> baseDocuments() throws IOException {
        return ManifestDocuments.allIn(BASE);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> configMap(List<Map<String, Object>> docs) {
        Map<String, Object> cm = ofKind(docs, "ConfigMap").stream()
                .filter(d -> "recsys-config".equals(nameOf(d)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ConfigMap named recsys-config in k8s/base"));
        Map<String, String> data = new LinkedHashMap<>();
        ((Map<String, Object>) cm.get("data")).forEach((k, v) -> data.put(k, String.valueOf(v)));
        return data;
    }

    static Map<String, Object> policyFor(String workload, List<Map<String, Object>> docs) {
        return ofKind(docs, "NetworkPolicy").stream()
                .filter(p -> workload.equals(nameOf(p)))
                .findFirst()
                .orElse(null);
    }

    /** A policy that does not list Egress in policyTypes leaves egress unrestricted. */
    static boolean restrictsEgress(Map<String, Object> policy) {
        return stringListOf(mapAt(policy, "spec"), "policyTypes").contains("Egress");
    }

    /**
     * Resolve a host to the labels of the pods behind it. Strict on purpose: an unresolvable
     * host is dead configuration, and a permissive `app: <host>` fallback would hide exactly
     * that. REDIS_HOST "redis" named no Service before this change.
     */
    static Map<String, Object> destinationLabels(String host, List<Map<String, Object>> docs) {
        Map<String, Map<String, Object>> selectors = new LinkedHashMap<>();
        for (Map<String, Object> svc : ofKind(docs, "Service")) {
            Map<String, Object> selector = mapAt(svc, "spec", "selector");
            if (selector != null) selectors.put(nameOf(svc), selector);
        }
        String[] labels = host.split("\\.");
        // StatefulSet pod FQDN: <pod>.<headless-service>.<ns>.svc.cluster.local
        if (labels.length >= 2 && selectors.containsKey(labels[1])) return selectors.get(labels[1]);
        if (selectors.containsKey(labels[0])) return selectors.get(labels[0]);
        // Explicit witness: Map.of("app", x) infers Map<String,String>, which is not a
        // Map<String,Object> and will not compile as this method's return value.
        if (EXTERNALLY_DEPLOYED.contains(labels[0])) return Map.<String, Object>of("app", labels[0]);
        throw new AssertionError("host '" + host + "' resolves to no Service in k8s/base and is not "
                + "listed in EXTERNALLY_DEPLOYED — it is dead configuration, or a Service is missing");
    }

    /** A selector matches a pod when every one of its label pairs is present on that pod. */
    private static boolean selects(Map<String, Object> selector, Map<String, Object> podLabels) {
        return selector != null && !selector.isEmpty()
                && podLabels.entrySet().containsAll(selector.entrySet());
    }

    /**
     * Selector AND port must sit in the SAME rule. Checking "some rule names the destination"
     * and "some rule names the port" independently is the exact false pass
     * ScrapeTargetManifestTest documents having slipped past its own earlier version.
     */
    static boolean permitsEgress(Map<String, Object> policy, Map<String, Object> destLabels, int port) {
        if (!restrictsEgress(policy)) return true;
        return listOf(mapAt(policy, "spec"), "egress").stream().anyMatch(rule -> {
            boolean toDestination = listOf(rule, "to").stream()
                    .anyMatch(to -> selects(mapAt(to, "podSelector", "matchLabels"), destLabels));
            boolean onPort = listOf(rule, "ports").stream()
                    .anyMatch(p -> Integer.valueOf(port).equals(p.get("port")));
            return toDestination && onPort;
        });
    }

    /** Mirror of {@link #permitsEgress}: selector and port must sit in the same ingress rule. */
    static boolean admitsIngress(Map<String, Object> policy, Map<String, Object> sourceLabels, int port) {
        return listOf(mapAt(policy, "spec"), "ingress").stream().anyMatch(rule -> {
            boolean fromSource = listOf(rule, "from").stream()
                    .anyMatch(from -> selects(mapAt(from, "podSelector", "matchLabels"), sourceLabels));
            boolean onPort = listOf(rule, "ports").stream()
                    .anyMatch(p -> Integer.valueOf(port).equals(p.get("port")));
            return fromSource && onPort;
        });
    }

    /**
     * The sentinel pods matched no NetworkPolicy at all, which in Kubernetes means every source
     * is admitted by default — the one failure mode indistinguishable from working correctly.
     * Every other data-tier pod in this namespace is ingress-restricted; these were missed
     * because the redis policy selects app: redis and sentinels carry app: redis-sentinel.
     */
    @Test
    void sentinelPodsAreIngressRestricted() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();

        Map<String, Object> policy = ofKind(docs, "NetworkPolicy").stream()
                .filter(p -> Map.of("app", "redis-sentinel").equals(mapAt(p, "spec", "podSelector", "matchLabels")))
                .findFirst()
                .orElse(null);

        assertThat(policy)
                .as("no NetworkPolicy selects app: redis-sentinel, so Kubernetes admits ingress "
                        + "from every pod in the cluster to port 26379 — sentinels can rewrite a "
                        + "client's view of which node is primary")
                .isNotNull();

        Set<String> notAdmitted = new TreeSet<>();
        List<String> expectedSources = List.of(
                "recsys-api-gateway", "recsys-catalog-serving",
                "recsys-model-serving", "recsys-online-serving");
        for (String source : expectedSources) {
            // Map.<String, Object>of — the inferred Map<String,String> does not match the
            // Map<String,Object> parameter.
            if (!admitsIngress(policy, Map.<String, Object>of("app", source), 26379)) {
                notAdmitted.add(source);
            }
        }
        // Sentinels gossip among themselves to agree on a primary; without this they cannot
        // reach quorum and never fail over.
        if (!admitsIngress(policy, Map.<String, Object>of("app", "redis-sentinel"), 26379)) {
            notAdmitted.add("redis-sentinel (sentinel-to-sentinel quorum)");
        }

        assertThat(notAdmitted)
                .as("the sentinel policy must admit 26379 from every workload granted egress to it "
                        + "and from the sentinels themselves; a policy that blocks one of these is "
                        + "worse than no policy, because it fails at startup instead of at review")
                .isEmpty();
    }

    @Test
    void everyDeclaredUpstreamIsPermittedByEgress() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();
        Map<String, String> cfg = configMap(docs);

        Set<String> unreachable = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : OWNED_KEYS.entrySet()) {
            String workload = entry.getKey();
            Map<String, Object> policy = policyFor(workload, docs);
            assertThat(policy).as("no NetworkPolicy named %s in k8s/base", workload).isNotNull();
            if (!restrictsEgress(policy)) continue;

            for (String key : entry.getValue()) {
                for (Upstream upstream : Upstream.parse(key, cfg)) {
                    Map<String, Object> destLabels = destinationLabels(upstream.host(), docs);
                    if (!permitsEgress(policy, destLabels, upstream.port())) {
                        unreachable.add(workload + " -> " + key + " (" + upstream.host()
                                + ":" + upstream.port() + ", pods " + destLabels + ")");
                    }
                }
            }
        }

        assertThat(unreachable)
                .as("each of these is an endpoint a workload dials with no egress rule permitting "
                        + "it, so under an enforcing CNI the connection is dropped. The failures are "
                        + "quiet in different ways and need different fixes: a blocked service "
                        + "registry falls back to static routes and logs nothing unusual, a blocked "
                        + "sentinel connection fails at startup looking like a Redis outage, and a "
                        + "blocked MySQL connection surfaces only on the first outbox append. Add a "
                        + "matching egress rule to k8s/base/network-policy.yaml")
                .isEmpty();
    }

    /**
     * Both ends enforce independently, so a granted egress rule is worthless if the destination's
     * own policy does not admit the source. This is the half that is easy to forget, because the
     * egress side is the one you edit when you notice the connection failing.
     */
    @Test
    void everyPermittedEgressIsAdmittedByItsDestination() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();
        Map<String, String> cfg = configMap(docs);
        List<Map<String, Object>> policies = ofKind(docs, "NetworkPolicy");

        Set<String> blocked = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : OWNED_KEYS.entrySet()) {
            String workload = entry.getKey();
            Map<String, Object> sourcePolicy = policyFor(workload, docs);
            if (sourcePolicy == null || !restrictsEgress(sourcePolicy)) continue;
            Map<String, Object> sourceLabels = mapAt(sourcePolicy, "spec", "podSelector", "matchLabels");

            for (String key : entry.getValue()) {
                for (Upstream upstream : Upstream.parse(key, cfg)) {
                    Map<String, Object> destLabels = destinationLabels(upstream.host(), docs);

                    // Only destinations governed by a policy restrict ingress; ollama and mysql
                    // are deployed outside k8s/base and have none.
                    Map<String, Object> destPolicy = policies.stream()
                            .filter(p -> {
                                Map<String, Object> sel = mapAt(p, "spec", "podSelector", "matchLabels");
                                return sel != null && !sel.isEmpty()
                                        && destLabels.entrySet().containsAll(sel.entrySet())
                                        && stringListOf(mapAt(p, "spec"), "policyTypes").contains("Ingress");
                            })
                            .findFirst()
                            .orElse(null);
                    if (destPolicy == null) continue;

                    if (!admitsIngress(destPolicy, sourceLabels, upstream.port())) {
                        blocked.add(workload + " -> " + upstream.host() + ":" + upstream.port()
                                + " (egress granted, but NetworkPolicy " + nameOf(destPolicy)
                                + " does not admit " + sourceLabels + ")");
                    }
                }
            }
        }

        assertThat(blocked)
                .as("these connections are permitted on the way out and dropped on the way in. "
                        + "NetworkPolicy is enforced at both ends, so granting egress alone changes "
                        + "nothing observable — the connection still fails, and it fails identically "
                        + "to having no egress rule at all. Add the matching ingress rule")
                .isEmpty();
    }

    /**
     * The drift catcher. Adding an upstream to the ConfigMap without claiming it here is exactly
     * how the policy fell six rules behind reality — every one of those gaps was a config change
     * that nobody paired with a policy change. Claiming a key is cheap; the claim is what makes
     * {@link #everyDeclaredUpstreamIsPermittedByEgress} require a rule for it.
     */
    @Test
    void everyConfigMapUpstreamKeyIsClaimed() throws IOException {
        Map<String, String> cfg = configMap(baseDocuments());

        Set<String> claimed = OWNED_KEYS.values().stream()
                .flatMap(Set::stream).collect(java.util.stream.Collectors.toSet());

        Set<String> unclaimed = new TreeSet<>();
        for (String key : cfg.keySet()) {
            boolean isUpstream = UPSTREAM_KEY_SUFFIXES.stream().anyMatch(key::endsWith);
            if (isUpstream && !claimed.contains(key)) unclaimed.add(key);
        }

        assertThat(unclaimed)
                .as("these ConfigMap keys name a network destination that no workload claims in "
                        + "OWNED_KEYS. Either add the key to the workload that dials it — which "
                        + "will then require a matching egress rule — or, if nothing dials it, "
                        + "delete it from the ConfigMap rather than leaving dead configuration "
                        + "that reads like a live dependency")
                .isEmpty();
    }

    /**
     * The relay declares policyTypes: [Ingress] deliberately: its whole job is reaching MySQL,
     * Kafka and (in EKS) ElastiCache, so an egress allow-list there would black-hole delivery the
     * moment it drifted. That makes it satisfy the egress assertion trivially, which is precisely
     * why it needs asserting — a later edit "completing" the policy with an Egress type would
     * pass every other test in this class while stopping outbox delivery in production.
     */
    @Test
    void theOutboxRelayEgressIsDeliberatelyUnrestricted() throws IOException {
        Map<String, Object> policy = policyFor("recsys-outbox-relay", baseDocuments());

        assertThat(policy).as("no NetworkPolicy named recsys-outbox-relay in k8s/base").isNotNull();
        assertThat(restrictsEgress(policy))
                .as("recsys-outbox-relay must NOT declare Egress in policyTypes. Its destinations "
                        + "(MySQL, Kafka, ElastiCache) are partly external and partly per-overlay, "
                        + "so an allow-list here silently stops outbox delivery. If you are adding "
                        + "Egress deliberately, extend OWNED_KEYS to cover every destination it "
                        + "dials and delete this assertion in the same commit")
                .isFalse();
    }

    /**
     * Both EKS overlays scale in-cluster Redis to zero and point REDIS_HOST at an ElastiCache
     * endpoint outside the cluster. A podSelector cannot match an external address, and nothing
     * patched the policy, so every Redis call in EKS was unpermitted.
     *
     * <p>Deliberately a shape check, not a correctness check: the CIDR is an operator-supplied
     * REPLACE_ME value like the wafv2-acl-arn placeholder, so this asserts the rule exists on
     * both Redis ports and stops there. Whether the CIDR is the right one is a deployment-time
     * question no unit test can answer.
     */
    @Test
    void theEksOverlayPatchesEgressForExternalRedis() throws IOException {
        List<Map<String, Object>> docs = ManifestDocuments.allIn(EKS_SHARED);

        List<Map<String, Object>> patches = ofKind(docs, "NetworkPolicy");
        assertThat(patches)
                .as("k8s/eks-shared has no NetworkPolicy patch. Both region overlays replace "
                        + "in-cluster Redis with ElastiCache, which no podSelector in k8s/base can "
                        + "match, so the serving pods have no permitted route to Redis at all")
                .isNotEmpty();

        Set<String> missing = new TreeSet<>();
        for (String workload : List.of("recsys-api-gateway", "recsys-catalog-serving",
                "recsys-model-serving", "recsys-online-serving")) {
            Map<String, Object> patch = patches.stream()
                    .filter(p -> workload.equals(nameOf(p)))
                    .findFirst()
                    .orElse(null);
            if (patch == null) {
                missing.add(workload + " (no patch)");
                continue;
            }
            for (int port : new int[] {6379, 26379}) {
                boolean hasCidrRule = listOf(mapAt(patch, "spec"), "egress").stream()
                        .anyMatch(rule -> listOf(rule, "to").stream()
                                .anyMatch(to -> mapAt(to, "ipBlock") != null)
                                && listOf(rule, "ports").stream()
                                        .anyMatch(p -> Integer.valueOf(port).equals(p.get("port"))));
                if (!hasCidrRule) missing.add(workload + " (no ipBlock rule on " + port + ")");
            }
        }

        assertThat(missing)
                .as("each of these needs an ipBlock egress rule on both 6379 and 26379 in "
                        + "k8s/eks-shared/network-policy-elasticache-patch.yaml. ElastiCache is "
                        + "reached by IP, not by pod label")
                .isEmpty();
    }
}
