package com.recsys.application.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class LlmTokenUsageScannerTest {

    private final LlmTokenUsageScanner scanner = new LlmTokenUsageScanner(new ObjectMapper());

    private void feed(String s) {
        scanner.accept(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsUsageFromOpenAiStyleSseFrames() {
        feed("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n");
        feed("data: {\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":42}}\n\n");
        feed("data: [DONE]\n\n");

        assertThat(scanner.completionTokens()).hasValue(42);
    }

    @Test
    void readsEvalCountFromNativeOllamaNdjson() {
        feed("{\"response\":\"hi\",\"done\":false}\n");
        feed("{\"done\":true,\"prompt_eval_count\":9,\"eval_count\":42}\n");

        assertThat(scanner.completionTokens()).hasValue(42);
    }

    /**
     * Chunk boundaries are a network artifact, not a frame boundary — a usage line split across two
     * {@code HttpData}s is the ordinary case for a large frame, and losing it would silently make
     * the settle-up a no-op exactly when it matters.
     */
    @Test
    void readsUsageSplitAcrossChunkBoundaries() {
        feed("data: {\"usage\":{\"comple");
        feed("tion_tokens\":42}}\n\n");

        assertThat(scanner.completionTokens()).hasValue(42);
    }

    /** A buffered body arrives whole, with no trailing newline to trigger a line scan. */
    @Test
    void readsUsageFromAnUnterminatedBufferedBody() {
        feed("{\"choices\":[{\"text\":\"hi\"}],\"usage\":{\"completion_tokens\":42}}");
        assertThat(scanner.completionTokens()).isEmpty();

        scanner.finish();

        assertThat(scanner.completionTokens()).hasValue(42);
    }

    @Test
    void reportsNothingWhenTheUpstreamReportsNoUsage() {
        feed("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n");
        feed("data: [DONE]\n\n");
        scanner.finish();

        assertThat(scanner.completionTokens()).isEqualTo(OptionalInt.empty());
    }

    @Test
    void survivesMalformedAndNonJsonFrames() {
        feed(": keep-alive\n\n");
        feed("data: {not json\n\n");
        feed("\n\n");
        feed("data: {\"usage\":{\"completion_tokens\":7}}\n\n");
        scanner.finish();

        assertThat(scanner.completionTokens()).hasValue(7);
    }
}
