package com.recsys.application.pagination;

import com.recsys.domain.recommendation.RecommendationQuery;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RecommendationQueryFingerprintTest {

    @Test
    void exclusionsAreCanonicalAndLimitDoesNotAffectFingerprint() {
        var a = new RecommendationQuery("u1", 10, Set.of("3", "1"), null);
        var b = new RecommendationQuery("u1", 50, Set.of("1", "3"), null);

        assertThat(RecommendationQueryFingerprint.of(a))
                .isEqualTo(RecommendationQueryFingerprint.of(b));
    }

    @Test
    void userAndExclusionsAffectFingerprint() {
        assertThat(RecommendationQueryFingerprint.of(query("u1", Set.of("1"))))
                .isNotEqualTo(RecommendationQueryFingerprint.of(query("u2", Set.of("1"))))
                .isNotEqualTo(RecommendationQueryFingerprint.of(query("u1", Set.of("2"))));
    }

    @Test
    void fingerprintIsLowercaseSha256Hex() {
        assertThat(RecommendationQueryFingerprint.of(query("u1", Set.of("seen"))))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsLoneSurrogatesInsteadOfReplacingThemWithQuestionMarks() {
        assertThat(RecommendationQueryFingerprint.of(query("?", Set.of("seen"))))
                .matches("[0-9a-f]{64}");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecommendationQueryFingerprint.of(query("\ud800", Set.of("seen"))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecommendationQueryFingerprint.of(query("u1", Set.of("\ud800"))));
    }

    private static RecommendationQuery query(String userId, Set<String> exclusions) {
        return new RecommendationQuery(userId, 10, exclusions, null);
    }
}
