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
}
