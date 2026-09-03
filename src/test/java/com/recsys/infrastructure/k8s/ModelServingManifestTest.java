package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the model-serving production defaults the 2026-09-03 ONNX hardening design commits to,
 * and the two couplings that make them real.
 *
 * <p>The values themselves: ONNX intra-op 1 / inter-op 1 / SEQUENTIAL, admission cap 8 per
 * two-CPU pod, readiness failure rate 0.05 and average latency 500ms. They are safety defaults,
 * not capacity claims — but a manifest edit that quietly drops one of them back to the demo
 * value in application.yml (0.5 / 2000 / 64) is exactly the regression this file exists to
 * catch, because nothing else would: the service starts fine either way.
 *
 * <p>The couplings: (1) every {@code RECSYS_*} variable this manifest sets must be spelled as a
 * {@code ${...}} placeholder in application.yml. Spring Boot's relaxed binding maps
 * {@code RECSYS_HEALTH_MAX_FAILURE_RATE} to {@code recsys.health.max.failure.rate}, not to
 * {@code max-failure-rate}, so an env var with no placeholder binds to nothing and the default
 * silently wins. (2) Every alert in the model rule group has a promtool fixture, so a rule
 * cannot be added or renamed without a test — the promtool run is not part of the Maven gate.
 *
 * <p>This asserts file contents, not a rendered kustomization (same honest scope as
 * {@link RedisUsernameOverlayTest}).
 */
class ModelServingManifestTest {

    private static final Path DEPLOYMENT = Path.of("k8s", "base", "model-serving.yaml");
    private static final Path CONFIGMAP = Path.of("k8s", "base", "configmap.yaml");
    private static final Path APPLICATION_YML = Path.of("src", "main", "resources", "application.yml");
    private static final Path RULES = Path.of("k8s", "base", "prometheus-rules.yaml");
    private static final Path RULE_TESTS = Path.of("k8s", "base", "prometheus-rules.test.yaml");

    private static final Map<String, String> REQUIRED_CONTAINER_ENV = Map.of(
            "RECSYS_MODEL_ONNX_INTRA_OP_THREADS", "1",
            "RECSYS_MODEL_ONNX_INTER_OP_THREADS", "1",
            "RECSYS_MODEL_ONNX_EXECUTION_MODE", "SEQUENTIAL",
            "RECSYS_HEALTH_MAX_FAILURE_RATE", "0.05",
            "RECSYS_HEALTH_MAX_AVG_LATENCY_MS", "500");

    private static final List<String> MODEL_ALERTS = List.of(
            "ModelServingUnavailable", "ModelServingShedding",
            "ModelInferenceLatencyHigh", "ModelRuntimeLoadFailure");

    @Test
    void modelServingContainerSetsConservativeOnnxAndReadinessDefaults() throws IOException {
        Map<String, String> env = containerEnv(DEPLOYMENT, "recsys-model-serving", "model-serving");

        REQUIRED_CONTAINER_ENV.forEach((name, expected) ->
                assertThat(env.get(name))
                        .as("%s must set %s=%s explicitly; the application.yml default is the demo value",
                                DEPLOYMENT, name, expected)
                        .isEqualTo(expected));
    }

    @Test
    void sharedConfigMapCapsModelAdmissionAtEightPerPod() throws IOException {
        Map<String, Object> configMap = firstDocument(CONFIGMAP);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) configMap.get("data");

        assertThat(String.valueOf(data.get("RECSYS_HEALTH_MAX_CONCURRENT_REQUESTS")))
                .as("model admission is 8 concurrent requests per two-CPU pod, not the 64 the demo shipped with")
                .isEqualTo("8");
    }

    @Test
    void everyModelEnvVarTheManifestsSetHasAPlaceholderInApplicationYml() throws IOException {
        String applicationYml = Files.readString(APPLICATION_YML);
        Map<String, String> env = containerEnv(DEPLOYMENT, "recsys-model-serving", "model-serving");

        for (String name : REQUIRED_CONTAINER_ENV.keySet()) {
            assertThat(env).containsKey(name);
            assertThat(applicationYml)
                    .as("%s is set by %s but has no ${%s:...} placeholder in application.yml, so Spring "
                            + "relaxed binding never sees it and the demo default wins", name, DEPLOYMENT, name)
                    .contains("${" + name + ":");
        }
        assertThat(applicationYml).contains("${RECSYS_HEALTH_MAX_CONCURRENT_REQUESTS:");
    }

    @Test
    void everyModelAlertExistsAndHasAPromtoolFixture() throws IOException {
        String rules = Files.readString(RULES);
        String fixtures = Files.readString(RULE_TESTS);

        for (String alert : MODEL_ALERTS) {
            assertThat(rules).as("%s must define alert %s", RULES, alert).contains("- alert: " + alert);
            assertThat(fixtures).as("%s must exercise alert %s", RULE_TESTS, alert)
                    .contains("alertname: " + alert);
        }
    }

    // --- YAML helpers ---

    private static Map<String, String> containerEnv(Path manifest, String deploymentName,
                                                    String containerName) throws IOException {
        for (Map<String, Object> doc : documents(manifest)) {
            if (!"Deployment".equals(doc.get("kind"))) continue;
            if (!deploymentName.equals(metadataName(doc))) continue;
            List<Map<String, Object>> containers = path(doc, "spec", "template", "spec", "containers");
            for (Map<String, Object> container : containers) {
                if (!containerName.equals(container.get("name"))) continue;
                Map<String, String> env = new LinkedHashMap<>();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> entries = (List<Map<String, Object>>) container.getOrDefault("env", List.of());
                for (Map<String, Object> entry : entries) {
                    Object value = entry.get("value");
                    env.put(String.valueOf(entry.get("name")), value == null ? null : String.valueOf(value));
                }
                return env;
            }
        }
        throw new AssertionError("container " + containerName + " of Deployment " + deploymentName
                + " not found in " + manifest);
    }

    private static String metadataName(Map<String, Object> doc) {
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
        return metadata == null ? null : String.valueOf(metadata.get("name"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T path(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            current = ((Map<String, Object>) current).get(key);
        }
        return (T) current;
    }

    private static Map<String, Object> firstDocument(Path file) throws IOException {
        return documents(file).get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            List<Map<String, Object>> docs = new java.util.ArrayList<>();
            for (Object doc : new Yaml().loadAll(in)) {
                if (doc instanceof Map<?, ?> map) {
                    docs.add((Map<String, Object>) map);
                }
            }
            return docs;
        }
    }
}
