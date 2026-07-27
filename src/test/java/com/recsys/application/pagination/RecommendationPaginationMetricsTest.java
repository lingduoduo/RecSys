package com.recsys.application.pagination;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationPaginationMetricsTest {

    @Test
    void recordsOnlyFixedCursorFailureReasonLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecommendationPaginationMetrics metrics = new RecommendationPaginationMetrics(registry);

        metrics.cursorRejected(CursorFailureReason.SIGNATURE);

        assertThat(registry.get("recsys.pagination.cursor.rejected")
                .tag("reason", "signature").counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters().stream()
                .filter(meter -> meter.getId().getName()
                        .equals("recsys.pagination.cursor.rejected"))
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .filter(tag -> tag.getKey().equals("reason"))
                .map(io.micrometer.core.instrument.Tag::getValue))
                .containsExactlyInAnyOrder(
                        "malformed",
                        "signature",
                        "expired",
                        "query_mismatch",
                        "unsupported",
                        "legacy_disabled");
    }

    @Test
    void recordsLegacyRotationPageAndBudgetCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecommendationPaginationMetrics metrics = new RecommendationPaginationMetrics(registry);

        metrics.legacyAccepted();
        metrics.previousKeyVerified();
        metrics.pageReturned(false);
        metrics.pageReturned(true);
        metrics.budgetExhausted();

        assertThat(registry.get("recsys.pagination.cursor.legacy.accepted")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("recsys.pagination.cursor.previous_key.verified")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("recsys.pagination.page.returned")
                .tag("terminal", "false").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("recsys.pagination.page.returned")
                .tag("terminal", "true").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("recsys.pagination.budget.exhausted")
                .counter().count()).isEqualTo(1.0);
    }
}
