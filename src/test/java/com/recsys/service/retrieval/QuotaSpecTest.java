package com.recsys.service.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
