package com.recsys.api.serving;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HttpCachingTest {

    @Test
    void publicCache_rendersSMaxAgeAndStaleWhileRevalidate() {
        assertThat(HttpCaching.publicCache(3600, 86400))
                .isEqualTo("public, s-maxage=3600, stale-while-revalidate=86400, stale-if-error=86400");
    }

    @Test
    void etagFor_isQuotedAndStableForSameBody() {
        byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
        String first = HttpCaching.etagFor(body);
        String second = HttpCaching.etagFor(body);
        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("\"").endsWith("\"");
        assertThat(first).hasSize(34); // 32 hex chars + 2 quotes
    }

    @Test
    void etagFor_differsForDifferentBodies() {
        assertThat(HttpCaching.etagFor("{\"id\":1}".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(HttpCaching.etagFor("{\"id\":2}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void matches_returnsTrueForIdenticalTag() {
        assertThat(HttpCaching.matches("\"abc\"", "\"abc\"")).isTrue();
    }

    @Test
    void matches_usesWeakComparisonSoWeakPrefixIsIgnored() {
        assertThat(HttpCaching.matches("W/\"abc\"", "\"abc\"")).isTrue();
    }

    @Test
    void matches_handlesCommaSeparatedList() {
        assertThat(HttpCaching.matches("\"zzz\", \"abc\"", "\"abc\"")).isTrue();
    }

    @Test
    void matches_returnsTrueForWildcard() {
        assertThat(HttpCaching.matches("*", "\"abc\"")).isTrue();
    }

    @Test
    void matches_returnsFalseForDifferentTag() {
        assertThat(HttpCaching.matches("\"zzz\"", "\"abc\"")).isFalse();
    }

    @Test
    void matches_returnsFalseForNullOrBlankHeader() {
        assertThat(HttpCaching.matches(null, "\"abc\"")).isFalse();
        assertThat(HttpCaching.matches("   ", "\"abc\"")).isFalse();
    }
}
