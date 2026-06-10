package com.recsys.online.event;

import java.util.Locale;
import java.util.Set;

public final class EventSemantics {
    private static final long MIN_VIEW_WATCH_MS = 30_000L;
    private static final long MIN_DWELL_MS = 10_000L;
    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            "view", "watch", "click", "like", "rating", "dwell", "search",
            "order", "purchase"
    );

    private EventSemantics() {}

    public static String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        String normalized = eventType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EVENT_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported eventType: " + eventType);
        }
        return normalized;
    }

    public static int labelFor(String eventType, long watchMs, Integer rating) {
        if (isOrder(eventType)) {
            return 3;
        }
        if (is(eventType, "like") || is(eventType, "rating") && rating != null && rating >= 4) {
            return 2;
        }
        if (is(eventType, "click")
                || isSearch(eventType)
                || isView(eventType) && watchMs >= MIN_VIEW_WATCH_MS
                || isDwell(eventType) && watchMs >= MIN_DWELL_MS) {
            return 1;
        }
        return 0;
    }

    public static boolean isView(String eventType) {
        return is(eventType, "view") || is(eventType, "watch");
    }

    public static boolean isOrder(String eventType) {
        return is(eventType, "order") || is(eventType, "purchase");
    }

    public static boolean isSearch(String eventType) {
        return is(eventType, "search");
    }

    public static boolean isDwell(String eventType) {
        return is(eventType, "dwell");
    }

    public static long minDwellMs() {
        return MIN_DWELL_MS;
    }

    private static boolean is(String actual, String expected) {
        return expected.equals(actual);
    }
}
