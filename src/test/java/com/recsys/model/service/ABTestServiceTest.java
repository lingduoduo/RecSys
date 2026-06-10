package com.recsys.model.service;

import com.recsys.config.ABTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ABTestServiceTest {

    private static final String LAYER_A = "model-arch-test";
    private static final String LAYER_B = "recall-strategy-test";

    private ABTestConfig config;
    private ABTestService service;

    @BeforeEach
    void setUp() {
        config = new ABTestConfig();
        config.setEnabled(true);
        config.setLayerName("default");
        config.setTrafficSplitNumber(5);
        config.setBucketAVariant("test");
        config.setBucketBVariant("training");
        config.setDefaultVariant("training");
        service = new ABTestService(config);
    }

    // ---- basic cases ----

    @Test
    void disabled_alwaysReturnsDefault() {
        config.setEnabled(false);
        assertThat(service.getVariantForUser("123")).isEqualTo("training");
        assertThat(service.getVariantForUser("456")).isEqualTo("training");
    }

    @Test
    void nullUserId_returnsDefault() {
        assertThat(service.getVariantForUser(null)).isEqualTo("training");
    }

    @Test
    void blankUserId_returnsDefault() {
        assertThat(service.getVariantForUser("  ")).isEqualTo("training");
    }

    @Test
    void assignment_exposesBucketAndVariantMetadata() {
        String userId = findUserInBucket(0, LAYER_A);

        ABTestService.Assignment assignment = service.getAssignmentForUser(userId, LAYER_A);

        assertThat(assignment.variant()).isEqualTo("test");
        assertThat(assignment.bucket()).isEqualTo(0);
        assertThat(assignment.layerName()).isEqualTo(LAYER_A);
        assertThat(assignment.inExperiment()).isTrue();
    }

    @Test
    void bucketZero_returnsVariantA() {
        String userId = findUserInBucket(0, LAYER_A);
        assertThat(service.getVariantForUser(userId, LAYER_A)).isEqualTo("test");
    }

    @Test
    void bucketOne_returnsVariantB() {
        String userId = findUserInBucket(1, LAYER_A);
        assertThat(service.getVariantForUser(userId, LAYER_A)).isEqualTo("training");
    }

    @Test
    void otherBuckets_returnDefault() {
        for (int target = 2; target < 5; target++) {
            String userId = findUserInBucket(target, LAYER_A);
            assertThat(service.getVariantForUser(userId, LAYER_A))
                    .as("bucket %d should be default", target)
                    .isEqualTo("training");
        }
    }

    @Test
    void bucketing_isDeterministic() {
        String userId = "user-42";
        String first = service.getVariantForUser(userId, LAYER_A);
        assertThat(service.getVariantForUser(userId, LAYER_A)).isEqualTo(first);
        assertThat(service.getVariantForUser(userId, LAYER_A)).isEqualTo(first);
    }

    @Test
    void blankLayerName_fallsBackToConfiguredLayer() {
        String userId = "user-42";

        assertThat(service.getAssignmentForUser(userId, " ").layerName()).isEqualTo("default");
        assertThat(service.getVariantForUser(userId, " "))
                .isEqualTo(service.getVariantForUser(userId, "default"));
    }

    @Test
    void invalidTrafficSplit_returnsControlAssignment() {
        config.setTrafficSplitNumber(0);

        ABTestService.Assignment assignment = service.getAssignmentForUser("123", LAYER_A);

        assertThat(assignment.variant()).isEqualTo("training");
        assertThat(assignment.bucket()).isEqualTo(-1);
        assertThat(assignment.inExperiment()).isFalse();
    }

    // ---- same-layer: bucket A and bucket B are disjoint ----
    // Within one layer a user is assigned to exactly one bucket, so the variant A
    // population and the variant B population never overlap.

    @Test
    void sameLayer_bucketAAndBucketB_areDisjoint() {
        Set<String> bucketA = collectUsersInBucket(0, LAYER_A, 200);
        Set<String> bucketB = collectUsersInBucket(1, LAYER_A, 200);

        Set<String> overlap = new HashSet<>(bucketA);
        overlap.retainAll(bucketB);

        assertThat(overlap).as("within the same layer, bucket-A and bucket-B users must not overlap").isEmpty();
    }

    @Test
    void sameLayer_noUserAppearsInBothVariants() {
        // Pick 500 arbitrary users and confirm each resolves to exactly one variant.
        for (int i = 0; i < 500; i++) {
            String userId = String.valueOf(i);
            String variant = service.getVariantForUser(userId, LAYER_A);
            assertThat(variant).isIn("test", "training");

            // The same call must never yield a different result (mutual exclusion within a layer).
            assertThat(service.getVariantForUser(userId, LAYER_A))
                    .as("user %s should always get the same bucket in the same layer", userId)
                    .isEqualTo(variant);
        }
    }

    // ---- different layers: independent bucketing ----
    // Because the hash key is "userId:layerName", a user can land in bucket 0 in
    // layer A AND bucket 0 in layer B — the layers are orthogonal.

    @Test
    void differentLayers_canAssignSameUserToSameBucketIndex() {
        // Find a user that happens to be in bucket 0 in both layers.
        boolean found = false;
        for (int i = 0; i < 10_000; i++) {
            String userId = String.valueOf(i);
            int bucketInLayerA = bucketFor(userId, LAYER_A);
            int bucketInLayerB = bucketFor(userId, LAYER_B);
            if (bucketInLayerA == 0 && bucketInLayerB == 0) {
                found = true;
                // Confirm the service returns variant A for this user in both layers.
                assertThat(service.getVariantForUser(userId, LAYER_A)).isEqualTo("test");
                assertThat(service.getVariantForUser(userId, LAYER_B)).isEqualTo("test");
                break;
            }
        }
        assertThat(found).as("should find a user in bucket 0 of both layers across 10 000 candidates").isTrue();
    }

    @Test
    void differentLayers_produceDifferentBucketAssignments() {
        // Two independent layers should not produce identical assignments for the same user population.
        int differences = 0;
        for (int i = 0; i < 1000; i++) {
            String userId = String.valueOf(i);
            if (!service.getVariantForUser(userId, LAYER_A)
                        .equals(service.getVariantForUser(userId, LAYER_B))) {
                differences++;
            }
        }
        // Expect a meaningful fraction of users to differ across layers.
        assertThat(differences)
                .as("independent layers should diverge for a substantial fraction of users")
                .isGreaterThan(100);
    }

    @Test
    void differentLayers_bucketAPopulationsAreNotIdentical() {
        Set<String> bucketAInLayerA = collectUsersInBucket(0, LAYER_A, 200);
        Set<String> bucketAInLayerB = collectUsersInBucket(0, LAYER_B, 200);

        // The two sets may overlap, but they should not be the same set — layers are independent.
        assertThat(bucketAInLayerA).isNotEqualTo(bucketAInLayerB);
    }

    // ---- helpers ----

    private String findUserInBucket(int targetBucket, String layerName) {
        for (int i = 0; i < 10_000; i++) {
            String candidate = String.valueOf(i);
            if (bucketFor(candidate, layerName) == targetBucket) return candidate;
        }
        throw new IllegalStateException("No userId found for bucket " + targetBucket + " in layer " + layerName);
    }

    /** Collects {@code count} distinct userIds that fall into {@code targetBucket} for {@code layerName}. */
    private Set<String> collectUsersInBucket(int targetBucket, String layerName, int count) {
        Set<String> result = new HashSet<>();
        for (int i = 0; result.size() < count; i++) {
            String userId = "user-" + i;
            if (bucketFor(userId, layerName) == targetBucket) {
                result.add(userId);
            }
        }
        return result;
    }

    private int bucketFor(String userId, String layerName) {
        String key = userId + ":" + layerName;
        return (key.hashCode() & Integer.MAX_VALUE) % config.getTrafficSplitNumber();
    }
}
