package com.recsys.application.retrieval.multichannel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.recsys.application.retrieval.multichannel.RecallResult.DegradationOutcome.*;

class RecallResultTest {

    @Test
    void nullsAreRejected() {
        assertThatThrownBy(() -> new RecallResult(null, Set.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecallResult(List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void degradedChannelsIsUnmodifiable() {
        RecallResult r = new RecallResult(List.of(), Set.of("trending"));
        assertThat(r.degradedChannels()).containsExactly("trending");
        assertThatThrownBy(() -> r.degradedChannels().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void explicitOutcomeIsRetained() {
        assertThat(new RecallResult(List.of(), Set.of(), HEALTHY).outcome())
                .isEqualTo(HEALTHY);
    }

    @Test
    void healthyOutcomeRejectsDegradedChannels() {
        assertThatThrownBy(() -> new RecallResult(List.of(), Set.of("trending"), HEALTHY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void degradedOutcomeRequiresDegradedChannels() {
        assertThatThrownBy(() -> new RecallResult(List.of(), Set.of(), PARTIAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecallResult(List.of(), Set.of(), ALL_CHANNELS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fallbackDoesNotRequireChannelFailure() {
        assertThat(new RecallResult(List.of(), Set.of(), FALLBACK).outcome())
                .isEqualTo(FALLBACK);
    }

    @Test
    void compatibilityConstructorDerivesOnlyHealthyOrPartial() {
        assertThat(new RecallResult(List.of(), Set.of()).outcome()).isEqualTo(HEALTHY);
        assertThat(new RecallResult(List.of(), Set.of("trending")).outcome()).isEqualTo(PARTIAL);
    }
}
