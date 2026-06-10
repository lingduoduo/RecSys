package com.recsys.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Spring-managed binding for all Redis connection and pool settings.
 * Mirrors every env var previously read directly by RedisConnectionFactory.
 *
 * application.yml prefix: recsys.redis
 */
@Validated
@ConfigurationProperties(prefix = "recsys.redis")
public class RedisProperties {

    /** Deployment mode: standalone or sentinel. */
    @NotBlank
    private String mode = "standalone";

    // ── Standalone ────────────────────────────────────────────────────────────

    @NotBlank
    private String host = "localhost";

    @Min(1) @Max(65535)
    private int port = 6379;

    private String password = "";

    @Positive
    private int timeoutMs = 2000;

    // ── Sentinel ──────────────────────────────────────────────────────────────

    private String sentinelMaster = "mymaster";

    /** Comma-separated sentinel endpoints, e.g. host1:26379,host2:26379 */
    private String sentinelNodes = "";

    // ── Read replicas ─────────────────────────────────────────────────────────

    /** Comma-separated replica specs: host:port@az, e.g. redis-b:6379@us-east-1b */
    private String replicaNodes = "";

    // ── Connection pool ───────────────────────────────────────────────────────

    @Valid
    private Pool pool = new Pool();

    // ── Getters / setters ─────────────────────────────────────────────────────

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password == null ? "" : password; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public String getSentinelMaster() { return sentinelMaster; }
    public void setSentinelMaster(String sentinelMaster) { this.sentinelMaster = sentinelMaster; }

    public String getSentinelNodes() { return sentinelNodes; }
    public void setSentinelNodes(String sentinelNodes) { this.sentinelNodes = sentinelNodes == null ? "" : sentinelNodes; }

    public String getReplicaNodes() { return replicaNodes; }
    public void setReplicaNodes(String replicaNodes) { this.replicaNodes = replicaNodes == null ? "" : replicaNodes; }

    public Pool getPool() { return pool; }
    public void setPool(Pool pool) { this.pool = pool; }

    public boolean isSentinelMode() { return "sentinel".equalsIgnoreCase(mode); }
    public boolean hasPassword() { return password != null && !password.isBlank(); }

    public static class Pool {

        @Positive
        private int maxTotal = 50;

        @Positive
        private int maxIdle = 10;

        @Min(0)
        private int minIdle = 2;

        @Positive
        private int maxWaitMs = 250;

        private boolean testOnBorrow = true;

        public int getMaxTotal() { return maxTotal; }
        public void setMaxTotal(int maxTotal) { this.maxTotal = maxTotal; }

        public int getMaxIdle() { return maxIdle; }
        public void setMaxIdle(int maxIdle) { this.maxIdle = maxIdle; }

        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }

        public int getMaxWaitMs() { return maxWaitMs; }
        public void setMaxWaitMs(int maxWaitMs) { this.maxWaitMs = maxWaitMs; }

        public boolean isTestOnBorrow() { return testOnBorrow; }
        public void setTestOnBorrow(boolean testOnBorrow) { this.testOnBorrow = testOnBorrow; }
    }
}
