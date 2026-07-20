package com.recsys.application.retrieval.multichannel;

import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.resilience.FaultInjector;
import com.recsys.application.retrieval.RecallChannel;
import com.recsys.application.retrieval.coldstart.QuotaPolicy;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Per-port wiring for {@link MultiChannelRecallService}. Built via {@link #builder()} and
 * consumed by {@link MultiChannelRecallService#from(RecallConfig)}. {@code userEmbeddingStore}
 * may be null (disables cold-start detection — legacy merge).
 */
public record RecallConfig(
        List<RecallChannel> channels,
        QuotaPolicy quotaPolicy,
        long channelTimeoutMs,
        ExecutorService executor,
        ChannelHealthMonitor healthMonitor,
        FaultInjector faultInjector,
        EmbeddingStore userEmbeddingStore,
        RecallDegradationMetrics recallMetrics) {

    public static Builder builder() { return new Builder(); }

    static long readLongEnv(String name, long defaultValue) {
        return parseLongOrDefault(System.getenv(name), defaultValue);
    }

    static long parseLongOrDefault(String raw, long defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static final class Builder {
        private List<RecallChannel> channels;
        private QuotaPolicy quotaPolicy = QuotaPolicy.defaultMovie();
        private long channelTimeoutMs = readLongEnv("RECALL_CHANNEL_TIMEOUT_MS", 200L);
        private ExecutorService executor;
        private ChannelHealthMonitor healthMonitor = new ChannelHealthMonitor();
        private FaultInjector faultInjector = FaultInjector.NOOP;
        private EmbeddingStore userEmbeddingStore;
        private RecallDegradationMetrics recallMetrics = new RecallDegradationMetrics();

        public Builder channels(List<RecallChannel> channels) { this.channels = channels; return this; }
        public Builder quotaPolicy(QuotaPolicy quotaPolicy) { this.quotaPolicy = quotaPolicy; return this; }
        public Builder channelTimeoutMs(long ms) { this.channelTimeoutMs = ms; return this; }
        public Builder executor(ExecutorService executor) { this.executor = executor; return this; }
        public Builder healthMonitor(ChannelHealthMonitor m) { this.healthMonitor = m; return this; }
        public Builder faultInjector(FaultInjector fi) { this.faultInjector = fi; return this; }
        public Builder userEmbeddingStore(EmbeddingStore store) { this.userEmbeddingStore = store; return this; }
        public Builder recallMetrics(RecallDegradationMetrics metrics) {
            this.recallMetrics = metrics == null ? new RecallDegradationMetrics() : metrics;
            return this;
        }

        public RecallConfig build() {
            if (channels == null || channels.isEmpty()) {
                throw new IllegalArgumentException("at least one recall channel is required");
            }
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(healthMonitor, "healthMonitor");
            if (channelTimeoutMs < 1L) {
                throw new IllegalArgumentException("channelTimeoutMs must be >= 1, got: " + channelTimeoutMs);
            }
            return new RecallConfig(channels,
                    quotaPolicy == null ? QuotaPolicy.defaultMovie() : quotaPolicy,
                    channelTimeoutMs, executor, healthMonitor,
                    faultInjector == null ? FaultInjector.NOOP : faultInjector,
                    userEmbeddingStore,
                    recallMetrics);
        }
    }
}
