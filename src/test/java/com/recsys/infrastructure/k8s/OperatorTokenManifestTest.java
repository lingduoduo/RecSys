package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.recsys.infrastructure.k8s.ManifestDocuments.allIn;
import static com.recsys.infrastructure.k8s.ManifestDocuments.listOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.mapAt;
import static com.recsys.infrastructure.k8s.ManifestDocuments.nameOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.ofKind;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workload that enforces the operator tier must be given the credential the tier runs on.
 *
 * <p>{@code SHARD_ADMIN_TOKEN} gates the control-plane routes — {@code /setembedding}, the model
 * version activate/rollback/preload endpoints, {@code /online/ops}, {@code POST /shards/topology}
 * and {@code GET /shards/shard}. Unset means the tier authorizes nobody, which is the right
 * fail-closed behaviour and a completely silent one: a service that starts reading the variable
 * without a matching manifest change comes up, logs one warning, and rejects every operator request
 * until somebody notices a rollback not working. Nothing else in the build would catch it.
 *
 * <p>The requirement is therefore <em>derived</em> from the source that reads the variable rather
 * than restated as a list of workloads — the same principle {@link NetworkPolicyEgressManifestTest}
 * applies to egress destinations. A test asserting "the gateway and online-serving inject the
 * token" would pin today's two instances and stay silent on the third, which is the case that
 * matters.
 *
 * <p>Only one direction is enforced: reader ⇒ manifest. A Deployment injecting a token nobody reads
 * is a stale line, not a broken control.
 *
 * <p><strong>Scope:</strong> this reads {@code k8s/base}. Tests cannot run {@code kubectl
 * kustomize}, so an overlay that patched the env block away would not be caught here — the same
 * boundary {@code CLAUDE.md} records for the NetworkPolicy conformance test.
 */
class OperatorTokenManifestTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
    private static final Path BASE = Path.of("k8s", "base");

    private static final String ENV_VAR = "SHARD_ADMIN_TOKEN";
    private static final String SECRET_NAME = "recsys-online-admin";
    private static final String SECRET_KEY = "admin-token";

    /**
     * The {@code getenv} call, not the bare variable name. {@code AdminTokenGuard} documents the
     * variable in its javadoc while receiving the token as a constructor argument; matching the
     * name alone would demand a manifest for a file that reads nothing.
     */
    private static final String READ_MARKER = "getenv(\"" + ENV_VAR + "\")";

    /** Which Deployment supplies the token to each file that reads it. */
    private static final Map<String, String> READER_WORKLOADS = Map.of(
            "OnlinePredictionServer.java", "recsys-online-serving",
            "MicroserviceGatewayServer.java", "recsys-api-gateway");

    @Test
    void everyReaderOfTheOperatorTokenIsClassified() throws IOException {
        List<String> unclassified = new ArrayList<>();
        for (Path reader : filesReadingTheToken()) {
            if (!READER_WORKLOADS.containsKey(reader.getFileName().toString())) {
                unclassified.add(reader.toString());
            }
        }

        assertThat(unclassified)
                .describedAs("These files read %s but READER_WORKLOADS does not say which Deployment "
                        + "supplies it. Add the mapping and the manifest env block together — a "
                        + "service that enforces the operator tier without the credential rejects "
                        + "every operator request and only says so in one startup warning.", ENV_VAR)
                .isEmpty();
    }

    @Test
    void everyClassifiedWorkloadInjectsTheOperatorToken() throws IOException {
        List<Map<String, Object>> deployments = ofKind(allIn(BASE), "Deployment");
        Set<String> required = new LinkedHashSet<>(READER_WORKLOADS.values());

        List<String> problems = new ArrayList<>();
        for (String workload : required) {
            Map<String, Object> deployment = deployments.stream()
                    .filter(d -> workload.equals(nameOf(d)))
                    .findFirst()
                    .orElse(null);
            if (deployment == null) {
                problems.add(workload + ": no Deployment of that name in " + BASE);
                continue;
            }
            problems.addAll(describeTokenProblems(workload, deployment));
        }

        assertThat(problems)
                .describedAs("Workloads whose code reads %s must be given it from Secret %s/%s.",
                        ENV_VAR, SECRET_NAME, SECRET_KEY)
                .isEmpty();
    }

    /** @return one message per defect in this Deployment's injection of the token; empty when correct. */
    private static List<String> describeTokenProblems(String workload, Map<String, Object> deployment) {
        Map<String, Object> podSpec = mapAt(deployment, "spec", "template", "spec");
        for (Map<String, Object> container : listOf(podSpec, "containers")) {
            for (Map<String, Object> env : listOf(container, "env")) {
                if (!ENV_VAR.equals(env.get("name"))) {
                    continue;
                }
                Map<String, Object> ref = mapAt(env, "valueFrom", "secretKeyRef");
                if (ref == null) {
                    return List.of(workload + ": " + ENV_VAR + " is set, but not from a secretKeyRef");
                }
                List<String> defects = new ArrayList<>();
                if (!SECRET_NAME.equals(ref.get("name"))) {
                    defects.add(workload + ": secret is " + ref.get("name") + ", expected " + SECRET_NAME);
                }
                if (!SECRET_KEY.equals(ref.get("key"))) {
                    defects.add(workload + ": secret key is " + ref.get("key") + ", expected " + SECRET_KEY);
                }
                // Load-bearing: without optional, a cluster that has not been given the Secret
                // cannot schedule the pod at all, which turns a fail-closed tier into an outage.
                if (!Boolean.TRUE.equals(ref.get("optional"))) {
                    defects.add(workload + ": secretKeyRef must set optional: true, so a cluster "
                            + "without the Secret degrades to 403 on operator routes instead of "
                            + "failing to start the pod");
                }
                return defects;
            }
        }
        return List.of(workload + ": no " + ENV_VAR + " entry in any container's env");
    }

    private static List<Path> filesReadingTheToken() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            List<Path> readers = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains(READ_MARKER)) {
                    readers.add(file);
                }
            }
            return readers;
        }
    }
}
