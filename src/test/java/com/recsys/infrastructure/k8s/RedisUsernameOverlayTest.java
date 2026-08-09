package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An overlay that repoints Redis at ElastiCache must also clear {@code REDIS_USERNAME}.
 *
 * <p>{@code k8s/base} gives each workload its own Redis ACL user, defined in
 * {@code redis-users.acl.template} and loaded by the in-cluster StatefulSets via {@code --aclfile}.
 * The EKS overlays scale those StatefulSets to zero and point {@code REDIS_HOST} at ElastiCache,
 * which reads no ACL file at all — its access control is RBAC user groups created through the AWS
 * API, and nothing in this repo creates them.
 *
 * <p>So the pairing is load-bearing: with the username still set, every workload sends
 * {@code AUTH catalog <password>} to an ElastiCache that has no {@code catalog} user. That fails
 * outright rather than degrading, and would take out every Redis-backed feature in the region on
 * rollout. This shipped that way in PR #284 and was caught by an audit rather than by the gate.
 *
 * <p><strong>Scope, stated honestly.</strong> This asserts a coupling between files; it does not
 * render the kustomizations, so it cannot see a patch that fails to apply or an env var overridden
 * somewhere unexpected. No test in this repo renders an overlay, which is precisely how the
 * collision reached {@code main}. A rendering test would be strictly better and needs the
 * {@code kustomize} binary on CI.
 */
class RedisUsernameOverlayTest {

    private static final Path SHARED = Path.of("k8s", "eks-shared", "kustomization.yaml");
    private static final String CLEARING_PATCH = "redis-username-elasticache-patch.yaml";
    private static final String ELASTICACHE_PATCH = "redis-elasticache-patch.yaml";

    private static final List<Path> REGION_OVERLAYS =
            List.of(Path.of("k8s", "eks"), Path.of("k8s", "eks-us-west-2"));

    /**
     * The clearing patch lives in the shared component, so every region overlay inherits it. If it
     * is ever dropped from here, both regions regress at once and nothing else would notice.
     */
    @Test
    void theSharedComponentClearsTheRedisUsername() throws IOException {
        assertThat(Files.readString(SHARED))
                .as("%s must list %s — without it, every EKS workload sends an ACL username to an "
                        + "ElastiCache that has no such user and AUTH fails outright", SHARED,
                        CLEARING_PATCH)
                .contains(CLEARING_PATCH);
    }

    /**
     * A region overlay repoints Redis at ElastiCache on its own. It only inherits the clearing
     * patch by composing the shared component, so an overlay that did one without the other would
     * be broken in exactly the way PR #284 was.
     */
    @Test
    void everyOverlayThatRepointsRedisAlsoComposesTheSharedComponent() throws IOException {
        List<String> problems = new ArrayList<>();

        for (Path overlay : REGION_OVERLAYS) {
            Path kustomization = overlay.resolve("kustomization.yaml");
            assertThat(kustomization).as("%s is missing", kustomization).exists();
            String text = Files.readString(kustomization);

            boolean repointsRedis = text.contains(ELASTICACHE_PATCH);
            boolean composesShared = text.contains("eks-shared");

            if (repointsRedis && !composesShared) {
                problems.add(overlay + " repoints Redis at ElastiCache but does not compose "
                        + "../eks-shared, so REDIS_USERNAME is never cleared");
            }
        }

        // A silently-empty scan would pass this test while proving nothing.
        assertThat(REGION_OVERLAYS).isNotEmpty();
        assertThat(problems).isEmpty();
    }

    /**
     * The clearing patch must name every workload that carries a {@code REDIS_USERNAME} in
     * {@code k8s/base}. A workload added to base without a corresponding entry here would keep its
     * ACL username in EKS — the same failure, one workload at a time.
     */
    @Test
    void theClearingPatchCoversEveryWorkloadThatSetsAUsername() throws IOException {
        Path patch = Path.of("k8s", "eks-shared", CLEARING_PATCH);
        String patchText = Files.readString(patch);

        List<String> uncovered = new ArrayList<>();
        for (Path manifest : Files.list(Path.of("k8s", "base")).sorted().toList()) {
            String text = Files.readString(manifest);
            if (!text.contains("name: REDIS_USERNAME")) {
                continue;
            }
            for (String line : text.lines().toList()) {
                if (!line.strip().startsWith("name: recsys-")) {
                    continue;
                }
                String workload = line.strip().substring("name: ".length());
                if (!patchText.contains("name: " + workload)) {
                    uncovered.add(workload + " (" + manifest.getFileName() + ")");
                }
                break;
            }
        }

        assertThat(uncovered)
                .as("every k8s/base workload setting REDIS_USERNAME must be cleared by %s", patch)
                .isEmpty();
    }
}
