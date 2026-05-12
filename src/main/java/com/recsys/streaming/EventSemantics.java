package com.recsys.streaming;

import java.util.Locale;
import java.util.Set;

final class EventSemantics {
    private static final long MIN_VIEW_WATCH_MS = 30_000L;
    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            "impression", "exposure", "show",
            "view", "watch", "click", "like",
            "order", "purchase"
    );

    private EventSemantics() {}

    static String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        String normalized = eventType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EVENT_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported eventType: " + eventType);
        }
        return normalized;
    }

    static int labelFor(String eventType, long watchMs, Integer rating) {
        if (isOrder(eventType)) {
            return 3;
        }
        if (is(eventType, "like") || rating != null && rating >= 4) {
            return 2;
        }
        if (is(eventType, "click") || isView(eventType) && watchMs >= MIN_VIEW_WATCH_MS) {
            return 1;
        }
        return 0;
    }

    static boolean isView(String eventType) {
        return is(eventType, "view") || is(eventType, "watch");
    }

    static boolean isOrder(String eventType) {
        return is(eventType, "order") || is(eventType, "purchase");
    }

    private static boolean is(String actual, String expected) {
        return expected.equals(actual);
    }
}
