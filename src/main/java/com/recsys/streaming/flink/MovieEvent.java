package com.recsys.streaming.flink;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MovieEvent {
    public String eventId;
    public int userId;
    public int movieId;
    public String eventType;
    public long watchMs;
    public Integer rating;
    public long eventTimeMillis;
    public String source;

    public MovieEvent() {}

    public boolean isView() {
        return "view".equalsIgnoreCase(eventType);
    }

    public boolean isLike() {
        return "like".equalsIgnoreCase(eventType);
    }

    public long engagementWeight() {
        if (isLike()) {
            return 3L;
        }
        if (isView()) {
            return 1L;
        }
        return 0L;
    }
}
