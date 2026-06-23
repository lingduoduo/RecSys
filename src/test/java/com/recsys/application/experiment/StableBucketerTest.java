package com.recsys.application.experiment;
import com.recsys.application.experiment.StableBucketer;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StableBucketerTest {

    private static final int GOLDEN_123_DEFAULT = 8397;

    @Test
    void slotIsDeterministic() {
        assertThat(StableBucketer.slot("123", "default"))
                .isEqualTo(StableBucketer.slot("123", "default"));
    }

    @Test
    void slotIsWithinKeyspace() {
        for (int i = 0; i < 5_000; i++) {
            int slot = StableBucketer.slot(Integer.toString(i), "default");
            assertThat(slot).isGreaterThanOrEqualTo(0).isLessThan(StableBucketer.KEYSPACE);
        }
    }

    @Test
    void sequentialIdsSpreadAcrossKeyspace() {
        // Sequential numeric ids must NOT cluster (the String.hashCode weakness this replaces).
        Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            slots.add(StableBucketer.slot(Integer.toString(i), "default"));
        }
        // With a good hash over 10k ids into a 10k keyspace, expect a high number of distinct slots.
        assertThat(slots.size()).isGreaterThan(6_000);
    }

    @Test
    void differentLayersGiveIndependentSlots() {
        // For most users the two layers differ; assert at least the keys are not identical wholesale.
        int differ = 0;
        for (int i = 0; i < 1_000; i++) {
            if (StableBucketer.slot(Integer.toString(i), "a") != StableBucketer.slot(Integer.toString(i), "b")) {
                differ++;
            }
        }
        assertThat(differ).isGreaterThan(900);
    }

    @Test
    void nullInputsAreHandledAsEmpty() {
        // Null userId/layer must not throw and must stay within the keyspace.
        assertThat(StableBucketer.slot(null, "default")).isGreaterThanOrEqualTo(0).isLessThan(StableBucketer.KEYSPACE);
        assertThat(StableBucketer.slot("123", null)).isGreaterThanOrEqualTo(0).isLessThan(StableBucketer.KEYSPACE);
        assertThat(StableBucketer.slot(null, null)).isGreaterThanOrEqualTo(0).isLessThan(StableBucketer.KEYSPACE);
    }

    @Test
    void slotIsStableForKnownInput() {
        // Golden value: pins the algorithm so a future refactor that changes bucketing is caught.
        assertThat(StableBucketer.slot("123", "default")).isEqualTo(GOLDEN_123_DEFAULT);
    }
}
