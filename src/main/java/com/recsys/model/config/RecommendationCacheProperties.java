package com.recsys.model.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "recsys.recommendation-cache")
public class RecommendationCacheProperties {

    private boolean enabled = true;

    @Positive
    private int ttlSeconds = 300;

    @Positive
    @Max(100_000)
    private int maxEntries = 10_000;

    private boolean coldStartEnabled = true;

    @Positive
    private int coldStartTtlSeconds = 3600;

    @Min(1)
    @Max(100)
    private int coldStartMaxK = 100;

    @Positive
    @Max(60_000)
    private long computeWaitTimeoutMillis = 2_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public boolean isColdStartEnabled() {
        return coldStartEnabled;
    }

    public void setColdStartEnabled(boolean coldStartEnabled) {
        this.coldStartEnabled = coldStartEnabled;
    }

    public int getColdStartTtlSeconds() {
        return coldStartTtlSeconds;
    }

    public void setColdStartTtlSeconds(int coldStartTtlSeconds) {
        this.coldStartTtlSeconds = coldStartTtlSeconds;
    }

    public int getColdStartMaxK() {
        return coldStartMaxK;
    }

    public void setColdStartMaxK(int coldStartMaxK) {
        this.coldStartMaxK = coldStartMaxK;
    }

    public long getComputeWaitTimeoutMillis() {
        return computeWaitTimeoutMillis;
    }

    public void setComputeWaitTimeoutMillis(long computeWaitTimeoutMillis) {
        this.computeWaitTimeoutMillis = computeWaitTimeoutMillis;
    }
}
