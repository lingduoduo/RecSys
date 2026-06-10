package com.recsys.online.flink;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MovieEvent {
    private static final long MIN_VIEW_WATCH_MS = 30_000L;

    public String eventId;
    public int userId;
    public int movieId;
    public String eventType;
    public long watchMs;
    public long dwellMs;
    public Integer rating;
    public long eventTimeMillis;
    public String source;
    public Map<String, String> features;

    public MovieEvent() {}

    public boolean hasEventIdentity() {
        return eventId != null && !eventId.isBlank();
    }

    public String idempotencyKey() {
        if (hasEventIdentity()) {
            return eventId.trim();
        }
        return userId + ":" + movieId + ":" + eventType + ":" + eventTimeMillis;
    }

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

    public boolean isRating() {
        return matches("rating");
    }

    public boolean isDwell() {
        return matches("dwell");
    }

    public boolean isSearch() {
        return matches("search");
    }

    public boolean isOrder() {
        return matches("order") || matches("purchase");
    }

    public boolean hasSessionIdentity() {
        return sessionId() != null && !sessionId().isBlank();
    }

    public String sessionId() {
        if (features == null || features.isEmpty()) {
            return "";
        }
        String sessionId = firstNonBlank(features.get("sessionId"), features.get("session_id"));
        if (sessionId != null) {
            return sessionId;
        }
        return "";
    }

    public boolean contributesCtr() {
        return isImpression() || isClick() || isView() || isDwell() || isLike() || isRating();
    }

    public boolean updatesRecentHistory() {
        if (movieId <= 0 || isSearch()) {
            return false;
        }
        return isClick()
                || isLike()
                || isOrder()
                || isRating()
                || (isView() && watchMs >= MIN_VIEW_WATCH_MS)
                || (isDwell() && dwellMs >= 10_000L);
    }

    public int trainingLabel() {
        if (isOrder()) {
            return 3;
        }
        if (isLike() || isRating() && rating != null && rating >= 4) {
            return 2;
        }
        if (isClick()
                || isSearch()
                || isView() && watchMs >= MIN_VIEW_WATCH_MS
                || isDwell() && dwellMs >= 10_000L) {
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
        if (isRating()) {
            return rating != null && rating >= 4 ? 4L : 0L;
        }
        if (isClick()) {
            return 2L;
        }
        if (isView() && watchMs >= MIN_VIEW_WATCH_MS) {
            return 1L;
        }
        if (isDwell() && dwellMs >= 10_000L) {
            return 1L;
        }
        return 0L;
    }

    private boolean matches(String expected) {
        return expected.equalsIgnoreCase(eventType);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }
}
