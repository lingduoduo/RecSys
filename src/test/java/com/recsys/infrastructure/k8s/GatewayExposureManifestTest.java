package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An anonymous gateway must not be reachable from outside the cluster.
 *
 * <p>{@code k8s/base} paired {@code GATEWAY_ALLOW_ANONYMOUS: "true"} with a {@code LoadBalancer}
 * Service annotated {@code aws-load-balancer-scheme: internet-facing}, while the EKS overlays did
 * the opposite — {@code ClusterIP} and {@code "false"}. The hardened configuration was
 * cluster-internal and the wide-open one was the only thing on the internet.
 *
 * <p>Worse than a missing check: {@code GatewayAuthenticator} short-circuits when authentication is
 * disabled, so the {@code PROTECTED_PREFIXES} never-public guard is never consulted either. The
 * routes that declare themselves protected were as open as the rest. Nothing in the repo read
 * {@code GATEWAY_ALLOW_ANONYMOUS} at all.
 *
 * <p>Base keeps the opt-in deliberately — {@code GatewayAuthenticator.fromEnvironment} refuses to
 * start without a credential source, and base has none, so flipping the flag would stop base
 * booting. What base gives up is the exposure.
 *
 * <p><strong>Scope, stated rather than implied.</strong> This reads files; it does not render
 * kustomizations, so the overlay assertion is a coupling between texts and cannot see a patch that
 * fails to apply. Both of this week's overlay defects reached {@code main} through exactly that
 * hole. A test that renders each overlay would subsume this one and needs the {@code kustomize}
 * binary on CI.
 */
class GatewayExposureManifestTest {

    private static final Path BASE = Path.of("k8s", "base");
    private static final String GATEWAY_SERVICE = "recsys-api-gateway";
    private static final String ANONYMOUS_KEY = "GATEWAY_ALLOW_ANONYMOUS";
    private static final String SCHEME_ANNOTATION =
            "service.beta.kubernetes.io/aws-load-balancer-scheme";

    /** Service types that publish a Service outside the cluster. */
    private static final List<String> EXTERNAL_TYPES = List.of("LoadBalancer", "NodePort");

    private static final List<Path> OVERLAYS =
            List.of(Path.of("k8s", "eks"), Path.of("k8s", "eks-us-west-2"),
                    Path.of("k8s", "eks-shared"));

    @Test
    void anAnonymousGatewayIsNotExposedOutsideTheCluster() throws IOException {
        List<Map<String, Object>> docs = ManifestDocuments.allIn(BASE);

        String anonymous = anonymousSetting(docs);
        assertThat(anonymous)
                .as("no ConfigMap in %s defines %s — the scan found nothing to check",
                        BASE, ANONYMOUS_KEY)
                .isNotNull();

        Map<String, Object> service = gatewayService(docs);
        assertThat(service)
                .as("no Service named %s in %s — the scan found nothing to check",
                        GATEWAY_SERVICE, BASE)
                .isNotNull();

        if (!"true".equalsIgnoreCase(anonymous)) {
            return; // base authenticates; exposure is then a separate decision
        }

        List<String> problems = new ArrayList<>();
        Map<String, Object> spec = ManifestDocuments.mapAt(service, "spec");
        Object type = spec == null ? null : spec.get("type");
        if (type != null && EXTERNAL_TYPES.contains(String.valueOf(type))) {
            problems.add("Service type is " + type
                    + ", which publishes the gateway outside the cluster");
        }

        Map<String, Object> annotations = ManifestDocuments.mapAt(service, "metadata", "annotations");
        Object scheme = annotations == null ? null : annotations.get(SCHEME_ANNOTATION);
        if (scheme != null) {
            problems.add(SCHEME_ANNOTATION + " is set to " + scheme);
        }

        assertThat(problems)
                .as("%s sets %s=true, so its gateway Service must stay cluster-internal. "
                        + "Flipping the flag instead is not the fix: fromEnvironment refuses to "
                        + "start without a credential source, which base does not have",
                        BASE, ANONYMOUS_KEY)
                .isEmpty();
    }

    /**
     * The overlays are where internet-facing exposure belongs, and they must authenticate. Asserted
     * over file text because this test does not render — see the class comment.
     */
    @Test
    void anyOverlayThatExposesTheGatewayAlsoRequiresAuthentication() throws IOException {
        List<String> problems = new ArrayList<>();
        boolean scanned = false;

        for (Path overlay : OVERLAYS) {
            if (!Files.isDirectory(overlay)) {
                continue;
            }
            StringBuilder combined = new StringBuilder();
            for (Path file : Files.list(overlay).sorted().toList()) {
                if (file.toString().endsWith(".yaml")) {
                    combined.append(Files.readString(file)).append('\n');
                }
            }
            scanned = true;
            String text = combined.toString();
            boolean exposes = text.contains(SCHEME_ANNOTATION + ": internet-facing")
                    || text.contains("type: LoadBalancer")
                    || text.contains("type: NodePort");
            boolean authenticates = text.contains(ANONYMOUS_KEY + ": \"false\"");

            if (exposes && !authenticates) {
                problems.add(overlay + " exposes a Service outside the cluster without setting "
                        + ANONYMOUS_KEY + "=\"false\"");
            }
        }

        assertThat(scanned).as("no overlay directory was scanned").isTrue();
        assertThat(problems).isEmpty();
    }

    private static String anonymousSetting(List<Map<String, Object>> docs) {
        for (Map<String, Object> doc : ManifestDocuments.ofKind(docs, "ConfigMap")) {
            Map<String, Object> data = ManifestDocuments.mapAt(doc, "data");
            Object value = data == null ? null : data.get(ANONYMOUS_KEY);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static Map<String, Object> gatewayService(List<Map<String, Object>> docs) {
        for (Map<String, Object> doc : ManifestDocuments.ofKind(docs, "Service")) {
            if (GATEWAY_SERVICE.equals(ManifestDocuments.nameOf(doc))) {
                return doc;
            }
        }
        return null;
    }
}
