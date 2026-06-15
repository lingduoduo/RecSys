package com.recsys.service.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaSpecTest {

    @Test
    void warm_embeddingGets60Percent() {
        assertThat(QuotaSpec.warm(10).slotsFor("embedding")).isEqualTo(6);
    }

    @Test
    void warm_trendingGets20Percent() {
        assertThat(QuotaSpec.warm(10).slotsFor("trending")).isEqualTo(2);
    }

    @Test
    void warm_genreHistoryGets15Percent() {
        assertThat(QuotaSpec.warm(10).slotsFor("genre_history")).isEqualTo(2);
    }

    @Test
    void warm_totalSlotsEqualsLimit() {
        int limit = 10;
        QuotaSpec q = QuotaSpec.warm(limit);
        int total = q.slotsFor("embedding") + q.slotsFor("trending")
                + q.slotsFor("genre_history") + q.slotsFor("popularity");
        assertThat(total).isEqualTo(limit);
    }

    @Test
    void cold_coldStartGets50Percent() {
        assertThat(QuotaSpec.cold(10).slotsFor("cold_start")).isEqualTo(5);
    }

    @Test
    void cold_embeddingGetsZero() {
        assertThat(QuotaSpec.cold(10).slotsFor("embedding")).isEqualTo(0);
    }

    @Test
    void cold_totalSlotsEqualsLimit() {
        int limit = 10;
        QuotaSpec q = QuotaSpec.cold(limit);
        int total = q.slotsFor("cold_start") + q.slotsFor("trending")
                + q.slotsFor("popularity") + q.slotsFor("genre_history");
        assertThat(total).isEqualTo(limit);
    }

    @Test
    void slotsFor_unknownChannelReturnsZero() {
        assertThat(QuotaSpec.warm(10).slotsFor("unknown_channel")).isEqualTo(0);
    }

    @Test
    void warm_limit20_slotsProportional() {
        QuotaSpec q = QuotaSpec.warm(20);
        assertThat(q.slotsFor("embedding")).isEqualTo(12);
        assertThat(q.slotsFor("trending")).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 7, 9, 10, 11, 13, 20, 100})
    void cold_totalSlotsAlwaysEqualsLimit(int limit) {
        QuotaSpec q = QuotaSpec.cold(limit);
        int total = q.slotsFor("cold_start") + q.slotsFor("trending")
                + q.slotsFor("popularity") + q.slotsFor("genre_history");
        assertThat(total).isEqualTo(limit);
    }

    @Test
    void slotsFor_nullThrowsNPE() {
        assertThatThrownBy(() -> QuotaSpec.warm(10).slotsFor(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void warm_zeroLimitThrows() {
        assertThatThrownBy(() -> QuotaSpec.warm(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cold_negativeLimitThrows() {
        assertThatThrownBy(() -> QuotaSpec.cold(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
