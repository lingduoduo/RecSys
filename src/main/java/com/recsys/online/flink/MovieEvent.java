package com.recsys.online.flink;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MovieEvent {
    private static final long MIN_VIEW_WATCH_MS = 30_000L;

    @JsonAlias("event_id")
    public String eventId;
    public int userId;
    public int movieId;
    @JsonAlias("event_type")
    public String eventType;
    public long watchMs;
    public long dwellMs;
    public Integer rating;
    public long eventTimeMillis;
    public String source;
    public Map<String, String> features;
    private String sessionIdField;

    public MovieEvent() {}

    @JsonSetter("user_id")
    public void setUserIdStr(String raw) {
        this.userId = parseIdSuffix(raw);
    }

    @JsonSetter("item_id")
    public void setItemIdStr(String raw) {
        this.movieId = parseIdSuffix(raw);
    }

    @JsonSetter("timestamp_ms")
    public void setTimestampMs(long ms) {
        this.eventTimeMillis = ms;
    }

    @JsonSetter("session_id")
    public void setSessionIdField(String sessionId) {
        this.sessionIdField = sessionId;
    }

    private static int parseIdSuffix(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        int lastUnderscore = raw.lastIndexOf('_');
        String suffix = lastUnderscore >= 0 ? raw.substring(lastUnderscore + 1) : raw;
        try {
            return Math.abs(Integer.parseInt(suffix));
        } catch (NumberFormatException e) {
            return Math.abs(raw.hashCode()) % Integer.MAX_VALUE;
        }
    }

    public boolean hasEventIdentity() {
        return eventId != null && !eventId.isBlank();
    }

    public String idempotencyKey() {
        if (hasEventIdentity()) {
            return eventId.trim();
        }
        return userId + ":" + movieId + ":" + eventType + ":" + eventTimeMillis;
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
        if (sessionIdField != null && !sessionIdField.isBlank()) {
            return sessionIdField.trim();
        }
        if (features == null || features.isEmpty()) {
            return "";
        }
        String sid = firstNonBlank(features.get("sessionId"), features.get("session_id"));
        return sid != null ? sid : "";
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
        if (isOrder()) return 3;
        if (isLike() || isRating() && rating != null && rating >= 4) return 2;
        if (isClick()
                || isSearch()
                || isView() && watchMs >= MIN_VIEW_WATCH_MS
                || isDwell() && dwellMs >= 10_000L) return 1;
        return 0;
    }

    public long engagementWeight() {
        if (isOrder()) return 8L;
        if (isLike()) return 3L;
        if (isRating()) return rating != null && rating >= 4 ? 4L : 0L;
        if (isClick()) return 2L;
        if (isView() && watchMs >= MIN_VIEW_WATCH_MS) return 1L;
        if (isDwell() && dwellMs >= 10_000L) return 1L;
        return 0L;
    }

    private boolean matches(String expected) {
        return expected.equalsIgnoreCase(eventType);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        if (second != null && !second.isBlank()) return second.trim();
        return null;
    }
}
