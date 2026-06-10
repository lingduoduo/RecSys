package com.recsys.service.retrieval;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.streaming.ops.FaultInjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

public class MultiChannelRecallService {
    private static final Logger log = LoggerFactory.getLogger(MultiChannelRecallService.class);
    private static final long DEFAULT_CHANNEL_TIMEOUT_MS = 200L;

    private final List<RecallChannel> channels;
    private final ChannelHealthMonitor healthMonitor;
    private final ExecutorService executor;
    private final long channelTimeoutMs;
    private final FaultInjector faultInjector;

    // Convenience constructor for tests. Production callers should supply a WorkerBulkhead
    // via the 5-arg constructor — ForkJoinPool.commonPool() is unsuitable for blocking I/O.
    public MultiChannelRecallService(List<RecallChannel> channels) {
        this(channels, new ChannelHealthMonitor(), ForkJoinPool.commonPool(),
                DEFAULT_CHANNEL_TIMEOUT_MS, FaultInjector.NOOP);
    }

    public MultiChannelRecallService(List<RecallChannel> channels,
                                     ChannelHealthMonitor healthMonitor,
                                     ExecutorService executor,
                                     long channelTimeoutMs,
                                     FaultInjector faultInjector) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("at least one recall channel is required");
        }
        this.channels         = List.copyOf(channels);
        this.healthMonitor    = Objects.requireNonNull(healthMonitor, "healthMonitor");
        this.executor         = Objects.requireNonNull(executor, "executor");
        this.channelTimeoutMs = Math.max(1L, channelTimeoutMs);
        this.faultInjector    = faultInjector == null ? FaultInjector.NOOP : faultInjector;
    }

    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit <= 0) return List.of();

        // Submit all available channels in parallel with a per-channel timeout.
        List<CompletableFuture<ChannelResult>> futures = new ArrayList<>(channels.size());
        for (RecallChannel channel : channels) {
            if (!healthMonitor.isAvailable(channel.name())) {
                log.debug("Channel '{}' is in backoff — skipping", channel.name());
                continue;
            }
            String name = channel.name();
            CompletableFuture<ChannelResult> future = CompletableFuture
                    .supplyAsync(() -> {
                        faultInjector.maybeInject("channel:" + name);
                        return new ChannelResult(name, channel.recall(query, limit), null);
                    }, executor)
                    .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> new ChannelResult(name, List.of(), ex));
            futures.add(future);
        }

        // Wait for all (timeout is already enforced by orTimeout above).
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        // Merge results and update channel health.
        Map<String, MovieCandidate> merged = new LinkedHashMap<>();
        for (CompletableFuture<ChannelResult> future : futures) {
            ChannelResult result = future.join(); // already complete
            if (result.error() != null) {
                healthMonitor.recordFailure(result.channel());
                Throwable err = result.error();
                log.warn("Channel '{}' failed: {}", result.channel(),
                        err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName());
                continue;
            }
            healthMonitor.recordSuccess(result.channel());
            for (MovieCandidate c : result.candidates()) {
                if (query.excludedItemIds().contains(c.itemId())) continue;
                merged.merge(c.itemId(), c,
                        (existing, incoming) ->
                                incoming.score() > existing.score() ? incoming : existing);
            }
        }

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(MovieCandidate::score).reversed()
                        .thenComparing(MovieCandidate::itemId))
                .limit(limit)
                .toList();
    }

    private record ChannelResult(String channel, List<MovieCandidate> candidates, Throwable error) {
        ChannelResult { Objects.requireNonNull(candidates, "candidates"); }
    }
}
