package com.recsys.application.retrieval.multichannel;

import com.recsys.domain.item.MovieCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
