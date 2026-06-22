package com.recsys.service.retrieval.coldstart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaPolicyTest {

    @Test
    void defaultMovieWarm_includesUserSimilarity() {
        QuotaSpec q = QuotaPolicy.defaultMovie().warm(10);
        assertThat(q.slotsFor("embedding")).isEqualTo(5);
        assertThat(q.slotsFor("user_similarity")).isEqualTo(2);
        assertThat(q.slotsFor("trending")).isEqualTo(2);
        assertThat(q.slotsFor("genre_history")).isEqualTo(1);
        assertThat(q.slotsFor("popularity")).isEqualTo(0);
        assertThat(q.slots().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
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

    @Test
    void defaultOnlineWarm_slotsForLimit10() {
        QuotaSpec q = QuotaPolicy.defaultOnline().warm(10);
        assertThat(q.slotsFor("embedding")).isEqualTo(5);
        assertThat(q.slotsFor("online_recent_history")).isEqualTo(2);
        assertThat(q.slotsFor("user_similarity")).isEqualTo(2);
        assertThat(q.slotsFor("trending")).isEqualTo(1);
        assertThat(q.slotsFor("popularity")).isEqualTo(0);
        assertThat(q.slotsFor("cold_start")).isEqualTo(0);
        int total = q.slotsFor("embedding") + q.slotsFor("online_recent_history")
                + q.slotsFor("user_similarity") + q.slotsFor("trending") + q.slotsFor("popularity");
        assertThat(total).isEqualTo(10);
    }

    @Test
    void defaultOnlineCold_slotsForLimit10() {
        QuotaSpec q = QuotaPolicy.defaultOnline().cold(10);
        assertThat(q.slotsFor("cold_start")).isEqualTo(5);
        assertThat(q.slotsFor("trending")).isEqualTo(2);
        assertThat(q.slotsFor("popularity")).isEqualTo(2);
        assertThat(q.slotsFor("online_recent_history")).isEqualTo(1);
        assertThat(q.slotsFor("embedding")).isEqualTo(0);
        int total = q.slotsFor("cold_start") + q.slotsFor("trending")
                + q.slotsFor("popularity") + q.slotsFor("online_recent_history");
        assertThat(total).isEqualTo(10);
    }

    @Test
    void defaultModelRetrieval_warmIncludesRecentHistory() {
        QuotaSpec warm = QuotaPolicy.defaultModelRetrieval().warm(20);
        assertThat(warm.slots().get("embedding")).isEqualTo(9);               // round(0.45 * 20)
        assertThat(warm.slots().get("online_recent_history")).isEqualTo(3);   // round(0.15 * 20)
        assertThat(warm.slots().get("user_similarity")).isEqualTo(3);         // round(0.15 * 20)
        assertThat(warm.slots().get("trending")).isEqualTo(2);               // round(0.10 * 20)
        assertThat(warm.slots().get("popularity")).isEqualTo(3);            // residual 20-9-3-3-2
        assertThat(warm.slots().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(20);
        assertThat(warm.slots()).doesNotContainKey("cold_start");
    }

    @Test
    void defaultModelRetrieval_coldIsColdStartLed() {
        QuotaSpec cold = QuotaPolicy.defaultModelRetrieval().cold(20);
        assertThat(cold.slots().get("cold_start")).isEqualTo(10);  // round(0.50 * 20)
        assertThat(cold.slots().get("trending")).isEqualTo(5);     // round(0.25 * 20)
        assertThat(cold.slots().get("popularity")).isEqualTo(5);   // residual 20-10-5
        assertThat(cold.slots()).doesNotContainKey("embedding");   // embedding has 0 cold slots
        assertThat(cold.slots()).doesNotContainKey("online_recent_history");
    }
}
