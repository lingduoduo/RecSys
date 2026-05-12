package com.recsys.streaming.flink;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MovieEvent {
    private static final long MIN_VIEW_WATCH_MS = 30_000L;

    public String eventId;
    public int userId;
    public int movieId;
    public String eventType;
    public long watchMs;
    public Integer rating;
    public long eventTimeMillis;
    public String source;

    public MovieEvent() {}

    public boolean isImpression() {
        return matches("impression") || matches("exposure") || matches("show");
    }

    public boolean isView() {
        return matches("view") || matches("watch");
    }

    public boolean isClick() {
        return matches("click");
    }

    public boolean isLike() {
        return matches("like");
    }

    public boolean isOrder() {
        return matches("order") || matches("purchase");
    }

    public boolean updatesRecentHistory() {
        return isClick() || isLike() || isOrder() || (isView() && watchMs >= MIN_VIEW_WATCH_MS);
    }

    public int trainingLabel() {
        if (isOrder()) {
            return 3;
        }
        if (isLike() || rating != null && rating >= 4) {
            return 2;
        }
        if (isClick() || isView() && watchMs >= MIN_VIEW_WATCH_MS) {
            return 1;
        }
        return 0;
    }

    public long engagementWeight() {
        if (isOrder()) {
            return 8L;
        }
        if (isLike()) {
            return 3L;
        }
        if (isClick()) {
            return 2L;
        }
        if (isView() && watchMs >= MIN_VIEW_WATCH_MS) {
            return 1L;
        }
        return 0L;
    }

    private boolean matches(String expected) {
        return expected.equalsIgnoreCase(eventType);
    }
}
