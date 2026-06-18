package com.recsys.service.retrieval.coldstart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaPolicyTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 7, 10, 12, 20, 50, 100})
    void defaultMovieWarm_matchesLegacyQuotaSpec(int limit) {
        assertThat(QuotaPolicy.defaultMovie().warm(limit).slots())
                .isEqualTo(QuotaSpec.warm(limit).slots());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 7, 10, 12, 20, 50, 100})
    void defaultMovieCold_matchesLegacyQuotaSpec(int limit) {
        assertThat(QuotaPolicy.defaultMovie().cold(limit).slots())
                .isEqualTo(QuotaSpec.cold(limit).slots());
    }

    @Test
    void customPolicy_assignsResidualTheRemainder_andNeverExceedsLimit() {
        Map<String, Double> warm = new LinkedHashMap<>();
        warm.put("embedding", 0.50);
        warm.put("trending", 0.30);
        QuotaPolicy policy = new QuotaPolicy(warm, "popularity", warm, "popularity");

        QuotaSpec q = policy.warm(10);
        assertThat(q.slotsFor("embedding")).isEqualTo(5);
        assertThat(q.slotsFor("trending")).isEqualTo(3);
        assertThat(q.slotsFor("popularity")).isEqualTo(2); // residual = 10 - 5 - 3
        int total = q.slotsFor("embedding") + q.slotsFor("trending") + q.slotsFor("popularity");
        assertThat(total).isEqualTo(10);
    }

    @Test
    void limitMustBePositive() {
        assertThatThrownBy(() -> QuotaPolicy.defaultMovie().warm(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuotaPolicy.defaultMovie().cold(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void residualChannelMustNotAppearInFractionMap() {
        Map<String, Double> bad = new LinkedHashMap<>();
        bad.put("popularity", 0.5);
        assertThatThrownBy(() -> new QuotaPolicy(bad, "popularity", bad, "popularity"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeFractionRejected() {
        Map<String, Double> bad = new LinkedHashMap<>();
        bad.put("embedding", -0.1);
        Map<String, Double> ok = new LinkedHashMap<>();
        ok.put("embedding", 0.5);
        assertThatThrownBy(() -> new QuotaPolicy(bad, "popularity", ok, "popularity"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullMapOrResidualRejected() {
        Map<String, Double> ok = new LinkedHashMap<>();
        ok.put("embedding", 0.5);
        assertThatThrownBy(() -> new QuotaPolicy(null, "popularity", ok, "popularity"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QuotaPolicy(ok, null, ok, "popularity"))
                .isInstanceOf(NullPointerException.class);
    }
}
