package com.recsys.modelbased.model.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized config for readiness probe thresholds and metrics rolling window.
 * All values are validated at startup — misconfiguration fails fast rather than silently.
 *
 * Override via application.yml or environment variables, e.g.:
 *   RECSYS_HEALTH_MAX_FAILURE_RATE=0.3
 */
@Validated
@ConfigurationProperties(prefix = "recsys.health")
public class HealthProperties {

    /** Width of the rolling window used for recent failure rate, latency, and throughput. */
    @Positive
    private int windowSeconds = 60;

    /** Minimum requests in the window before readiness thresholds are enforced. Prevents false draining on cold start. */
    @Positive
    private int minSampleSize = 5;

    /** Fraction of requests [0.0, 1.0] that may fail before the instance is pulled from load-balancer rotation. */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double maxFailureRate = 0.5;

    /** Average inference latency (ms) above which the instance is pulled from load-balancer rotation. */
    @Positive
    private double maxAvgLatencyMs = 2000.0;

    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }

    public int getMinSampleSize() { return minSampleSize; }
    public void setMinSampleSize(int minSampleSize) { this.minSampleSize = minSampleSize; }

    public double getMaxFailureRate() { return maxFailureRate; }
    public void setMaxFailureRate(double maxFailureRate) { this.maxFailureRate = maxFailureRate; }

    public double getMaxAvgLatencyMs() { return maxAvgLatencyMs; }
    public void setMaxAvgLatencyMs(double maxAvgLatencyMs) { this.maxAvgLatencyMs = maxAvgLatencyMs; }
}
