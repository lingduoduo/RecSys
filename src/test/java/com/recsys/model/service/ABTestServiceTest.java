package com.recsys.model.service;

import com.recsys.config.ABTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ABTestServiceTest {

    private static final String LAYER = "default";

    private ABTestConfig config;
    private ABTestService service;

    @BeforeEach
    void setUp() {
        config = new ABTestConfig();
        config.setEnabled(true);
        config.setLayerName(LAYER);
        config.setBucketAPercent(20);
        config.setBucketBPercent(20);
        config.setBucketAVariant("test");
        config.setBucketBVariant("training");
        config.setDefaultVariant("training");
        service = new ABTestService(config);
    }

    @Test
    void disabled_alwaysReturnsDefault() {
        config.setEnabled(false);
        assertThat(service.getVariantForUser("123")).isEqualTo("training");
        assertThat(service.getVariantForUser("456")).isEqualTo("training");
    }

    @Test
    void nullOrBlankUserId_returnsControl() {
        assertThat(service.getVariantForUser(null)).isEqualTo("training");
        assertThat(service.getVariantForUser("  ")).isEqualTo("training");
        ABTestService.Assignment a = service.getAssignmentForUser("  ");
        assertThat(a.slot()).isEqualTo(-1);
        assertThat(a.inExperiment()).isFalse();
    }

    @Test
    void assignmentVariantMatchesSlotRanges() {
        // A = slot [0,2000), B = [2000,4000), control = [4000,10000) for 20/20.
        for (int i = 0; i < 2_000; i++) {
            String userId = Integer.toString(i);
            ABTestService.Assignment a = service.getAssignmentForUser(userId);
            int slot = StableBucketer.slot(userId, LAYER);
            if (slot < 2_000) {
                assertThat(a.variant()).isEqualTo("test");
                assertThat(a.inExperiment()).isTrue();
            } else if (slot < 4_000) {
                assertThat(a.variant()).isEqualTo("training");   // bucket B variant
                assertThat(a.inExperiment()).isTrue();
            } else {
                assertThat(a.variant()).isEqualTo("training");   // control
                assertThat(a.inExperiment()).isFalse();
            }
            assertThat(a.slot()).isEqualTo(slot);
            assertThat(a.layerName()).isEqualTo(LAYER);
        }
    }

    @Test
    void roughlyTwentyPercentInA() {
        int inA = 0, n = 10_000;
        for (int i = 0; i < n; i++) {
            if ("test".equals(service.getVariantForUser(Integer.toString(i)))) inA++;
        }
        // 20% target; allow generous tolerance for hash noise.
        assertThat(inA).isBetween(1_500, 2_500);
    }

    @Test
    void changingBPercentDoesNotReshuffleA() {
        // Record A-members at 20/20, then widen B to 30 and confirm every A-member stays in A.
        java.util.List<String> aMembers = new java.util.ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            String u = Integer.toString(i);
            if ("test".equals(service.getVariantForUser(u))) aMembers.add(u);
        }
        config.setBucketBPercent(30);
        for (String u : aMembers) {
            assertThat(service.getVariantForUser(u)).as("A-member %s after B 20->30", u).isEqualTo("test");
        }
    }

    @Test
    void blankLayerName_fallsBackToConfiguredLayer() {
        ABTestService.Assignment a = service.getAssignmentForUser("123", "   ");
        assertThat(a.layerName()).isEqualTo("default");
    }

    @Test
    void defaultVariantAccessor() {
        assertThat(service.defaultVariant()).isEqualTo("training");
    }
}
