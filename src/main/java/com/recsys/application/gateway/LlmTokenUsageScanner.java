package com.recsys.application.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;

/**
 * Reads the completion-token count an LLM upstream reports, off the response as it goes past.
 *
 * <p>This exists so the token budget can be settled against what a request actually cost rather
 * than the {@code max_tokens} its caller declared. It is fed the same bytes the client receives
 * and never holds the stream: frames are scanned line-by-line and forwarded on regardless, with
 * only a bounded partial-line carry-over retained between chunks. A response larger than the
 * carry-over bound is not a problem — usage is reported in its own line, and only a single
 * <em>unterminated</em> line exceeding {@link #MAX_PENDING_BYTES} is dropped.
 *
 * <p>Both dialects the deployed upstream (Ollama) can speak are understood:
 * <ul>
 *   <li>OpenAI-compatible — {@code data: {...,"usage":{"completion_tokens":N}}}, SSE-framed,
 *       optionally terminated by {@code data: [DONE]}. Also covers the buffered path, whose
 *       aggregated body is the same object without the {@code data:} prefix.</li>
 *   <li>Native Ollama NDJSON — a final {@code {"done":true,"eval_count":N}} line.</li>
 * </ul>
 *
 * <p>When an upstream reports no usage at all, {@link #completionTokens()} is empty and the
 * caller's declared estimate stands. That is a deployment property rather than a caller-controlled
 * one — the upstream is fixed by {@code LLM_SERVICE_URL}, so a caller cannot pick a silent one to
 * evade the budget.
 */
final class LlmTokenUsageScanner {

    /** Cap on a single unterminated line held between chunks. Usage lines are far smaller. */
    private static final int MAX_PENDING_BYTES = 64 * 1024;

    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE = "[DONE]";

    private final ObjectMapper mapper;
    private final StringBuilder pending = new StringBuilder();
    private int completionTokens = -1;

    LlmTokenUsageScanner(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Feeds one chunk of the response body. Never throws on malformed input. */
    void accept(byte[] chunk) {
        if (chunk == null || chunk.length == 0) return;
        pending.append(new String(chunk, StandardCharsets.UTF_8));
        int start = 0;
        for (int i = 0; i < pending.length(); i++) {
            if (pending.charAt(i) == '\n') {
                scanLine(pending.substring(start, i));
                start = i + 1;
            }
        }
        pending.delete(0, start);
        // A single line longer than the bound cannot be a usage line; drop it rather than grow.
        if (pending.length() > MAX_PENDING_BYTES) {
            pending.setLength(0);
        }
    }

    /** Scans whatever never arrived with a trailing newline — a buffered body is one such line. */
    void finish() {
        if (pending.length() > 0) {
            scanLine(pending.toString());
            pending.setLength(0);
        }
    }

    /** The last completion-token count the upstream reported, if it reported one. */
    OptionalInt completionTokens() {
        return completionTokens >= 0 ? OptionalInt.of(completionTokens) : OptionalInt.empty();
    }

    private void scanLine(String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty()) return;
        if (line.startsWith(SSE_DATA_PREFIX)) {
            line = line.substring(SSE_DATA_PREFIX.length()).trim();
        }
        if (line.isEmpty() || SSE_DONE.equals(line) || line.charAt(0) != '{') return;

        JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (Exception e) {
            return; // A partial or non-JSON frame tells us nothing; the stream is unaffected.
        }

        // OpenAI-compatible: usage.completion_tokens. Ollama native: top-level eval_count.
        JsonNode usage = root.path("usage").path("completion_tokens");
        if (!usage.isInt()) {
            usage = root.path("eval_count");
        }
        if (usage.isInt() && usage.asInt() >= 0) {
            completionTokens = usage.asInt();
        }
    }
}
