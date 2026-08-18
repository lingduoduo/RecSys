package com.recsys.infrastructure.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestOutcomeTest {

    @Test
    void aFastSuccessfulRequestWarrantsNoEvent() {
        assertThat(RequestOutcome.classify(200, 10, 500)).isNull();
    }

    @Test
    void aSlowSuccessfulRequestIsSlow() {
        assertThat(RequestOutcome.classify(200, 501, 500)).isEqualTo("slow");
    }

    @Test
    void theThresholdIsExclusive() {
        assertThat(RequestOutcome.classify(200, 500, 500)).isNull();
    }

    @Test
    void anyServerErrorIsFailedRegardlessOfSpeed() {
        assertThat(RequestOutcome.classify(500, 1, 500)).isEqualTo("failed");
        assertThat(RequestOutcome.classify(503, 1, 500)).isEqualTo("failed");
    }

    /**
     * 4xx is the one class an external caller can generate at will. Making it a log trigger
     * hands anyone with a URL the ability to fill a bounded, drop-on-full HEC queue -- and the
     * drops are indiscriminate, so it would take the ERROR events with it.
     */
    @Test
    void clientErrorsAreNotLoggedUnlessAlsoSlow() {
        assertThat(RequestOutcome.classify(400, 1, 500)).isNull();
        assertThat(RequestOutcome.classify(404, 1, 500)).isNull();
        assertThat(RequestOutcome.classify(429, 1, 500)).isNull();
        assertThat(RequestOutcome.classify(400, 900, 500)).isEqualTo("slow");
    }

    /**
     * SplunkHecEventSerializer silently drops MDC entries whose names collide with the five
     * event payload fields or the five envelope fields. A field named "time" would vanish with
     * no error anywhere. Asserted against the serializer's own set so adding a colliding field
     * later fails the build rather than the search.
     */
    @Test
    void noMdcFieldCollidesWithTheSerializersReservedKeys() {
        assertThat(RequestOutcome.MDC_KEYS)
                .isNotEmpty()
                .doesNotContainAnyElementsOf(SplunkHecEventSerializer.reservedKeys());
    }
}
