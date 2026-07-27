package com.recsys.application.pagination;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Bounded pagination counters without query-derived or cursor-derived labels. */
public final class RecommendationPaginationMetrics {
    private final Map<CursorFailureReason, Counter> cursorRejected;
    private final Counter legacyAccepted;
    private final Counter previousKeyVerified;
    private final Counter nonTerminalPageReturned;
    private final Counter terminalPageReturned;
    private final Counter budgetExhausted;

    public RecommendationPaginationMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        cursorRejected = new EnumMap<>(CursorFailureReason.class);
        for (CursorFailureReason reason : CursorFailureReason.values()) {
            cursorRejected.put(reason, Counter.builder("recsys.pagination.cursor.rejected")
                    .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
        legacyAccepted = Counter.builder("recsys.pagination.cursor.legacy.accepted")
                .register(registry);
        previousKeyVerified = Counter.builder(
                        "recsys.pagination.cursor.previous_key.verified")
                .register(registry);
        nonTerminalPageReturned = pageCounter(registry, false);
        terminalPageReturned = pageCounter(registry, true);
        budgetExhausted = Counter.builder("recsys.pagination.budget.exhausted")
                .register(registry);
    }

    void cursorRejected(CursorFailureReason reason) {
        cursorRejected.get(Objects.requireNonNull(reason, "reason")).increment();
    }

    void legacyAccepted() {
        legacyAccepted.increment();
    }

    void previousKeyVerified() {
        previousKeyVerified.increment();
    }

    void pageReturned(boolean terminal) {
        (terminal ? terminalPageReturned : nonTerminalPageReturned).increment();
    }

    void budgetExhausted() {
        budgetExhausted.increment();
    }

    private static Counter pageCounter(MeterRegistry registry, boolean terminal) {
        return Counter.builder("recsys.pagination.page.returned")
                .tag("terminal", Boolean.toString(terminal))
                .register(registry);
    }
}
