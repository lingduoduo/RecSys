package com.recsys.application.retrieval.multichannel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecallTaskMetricsTest {

    private static final String METER = "recsys.model.recall.tasks";

    @Test
    void countsRejectionsAndTimeoutsPerConfiguredChannel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecallTaskMetrics metrics = new RecallTaskMetrics(registry, List.of("embedding", "trending"));

        metrics.recordRejected("embedding");
        metrics.recordTimeout("trending");
        metrics.recordTimeout("trending");

        assertThat(registry.get(METER).tags("result", "rejected", "channel", "embedding").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(METER).tags("result", "timeout", "channel", "trending").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get(METER).tags("result", "timeout", "channel", "embedding").counter().count())
                .isZero();
    }

    @Test
    void countersArePreRegisteredAtZeroSoAbsenceIsNeverAmbiguous() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new RecallTaskMetrics(registry, List.of("embedding"));

        assertThat(registry.get(METER).tags("result", "rejected", "channel", "embedding").counter().count()).isZero();
        assertThat(registry.get(METER).tags("result", "timeout", "channel", "embedding").counter().count()).isZero();
    }

    @Test
    void unknownChannelNamesCollapseToOneLabelSoCardinalityStaysClosed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecallTaskMetrics metrics = new RecallTaskMetrics(registry, List.of("embedding"));

        metrics.recordRejected("not-configured-" + System.nanoTime());
        metrics.recordTimeout(null);

        assertThat(registry.get(METER).tags("result", "rejected", "channel", "unknown").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(METER).tags("result", "timeout", "channel", "unknown").counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters().stream()
                .map(m -> m.getId().getTag("channel"))
                .distinct())
                .containsExactlyInAnyOrder("embedding", "unknown");
    }

    @Test
    void noopInstanceAcceptsEverythingSilently() {
        RecallTaskMetrics.NOOP.recordRejected("anything");
        RecallTaskMetrics.NOOP.recordTimeout("anything");
    }
}
