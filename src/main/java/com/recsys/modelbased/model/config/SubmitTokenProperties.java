package com.recsys.modelbased.model.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "recsys.submit-token")
public class SubmitTokenProperties {

    private boolean enabled = false;

    @Positive
    @Max(86_400)
    private int ttlSeconds = 300;

    private String keyPrefix = "submit_token:";

    private String redisHost = "localhost";

    @Positive
    @Max(65_535)
    private int redisPort = 6379;

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

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "submit_token:" : keyPrefix.trim();
    }

    public String getRedisHost() {
        return redisHost;
    }

    public void setRedisHost(String redisHost) {
        this.redisHost = redisHost == null || redisHost.isBlank() ? "localhost" : redisHost.trim();
    }

    public int getRedisPort() {
        return redisPort;
    }

    public void setRedisPort(int redisPort) {
        this.redisPort = redisPort;
    }
}
