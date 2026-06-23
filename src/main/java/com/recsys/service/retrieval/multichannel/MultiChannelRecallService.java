package com.recsys.service.retrieval.multichannel;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.online.ops.FaultInjector;
import com.recsys.service.retrieval.RecallChannel;
import com.recsys.service.retrieval.RecallScoring;
import com.recsys.service.retrieval.coldstart.QuotaPolicy;
import com.recsys.service.retrieval.coldstart.QuotaSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final EmbeddingStore userEmbeddingStore;
    private final QuotaPolicy quotaPolicy;

    // Convenience constructor for tests. Production callers should supply a WorkerBulkhead
    // via the 5-arg constructor — ForkJoinPool.commonPool() is unsuitable for blocking I/O.
    public MultiChannelRecallService(List<RecallChannel> channels) {
        this(channels, new ChannelHealthMonitor(), ForkJoinPool.commonPool(),
                DEFAULT_CHANNEL_TIMEOUT_MS, FaultInjector.NOOP, null);
    }

    public MultiChannelRecallService(List<RecallChannel> channels,
                                     ChannelHealthMonitor healthMonitor,
                                     ExecutorService executor,
                                     long channelTimeoutMs,
                                     FaultInjector faultInjector) {
        this(channels, healthMonitor, executor, channelTimeoutMs, faultInjector, null);
    }

    public MultiChannelRecallService(List<RecallChannel> channels,
                                     ChannelHealthMonitor healthMonitor,
                                     ExecutorService executor,
                                     long channelTimeoutMs,
                                     FaultInjector faultInjector,
                                     EmbeddingStore userEmbeddingStore) {
        this(channels, healthMonitor, executor, channelTimeoutMs, faultInjector,
                userEmbeddingStore, QuotaPolicy.defaultMovie());
    }

    public MultiChannelRecallService(List<RecallChannel> channels,
                                     ChannelHealthMonitor healthMonitor,
                                     ExecutorService executor,
                                     long channelTimeoutMs,
                                     FaultInjector faultInjector,
                                     EmbeddingStore userEmbeddingStore,
                                     QuotaPolicy quotaPolicy) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("at least one recall channel is required");
        }
        this.channels            = List.copyOf(channels);
        this.healthMonitor       = Objects.requireNonNull(healthMonitor, "healthMonitor");
        this.executor            = Objects.requireNonNull(executor, "executor");
        this.channelTimeoutMs    = Math.max(1L, channelTimeoutMs);
        this.faultInjector       = faultInjector == null ? FaultInjector.NOOP : faultInjector;
        this.userEmbeddingStore  = userEmbeddingStore;
        this.quotaPolicy         = quotaPolicy == null ? QuotaPolicy.defaultMovie() : quotaPolicy;
    }

    /** Builds a service from a per-port {@link RecallConfig}. */
    public static MultiChannelRecallService from(RecallConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        return new MultiChannelRecallService(
                config.channels(),
                config.healthMonitor(),
                config.executor(),
                config.channelTimeoutMs(),
                config.faultInjector(),
                config.userEmbeddingStore(),
                config.quotaPolicy());
    }

    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit <= 0) return List.of();

        QuotaSpec quota = null;
        if (userEmbeddingStore != null) {
            try {
                int userId = Integer.parseInt(query.userId());
                boolean isCold = userEmbeddingStore.getEmbedding(userId) == null;
                quota = isCold ? quotaPolicy.cold(limit) : quotaPolicy.warm(limit);
            } catch (NumberFormatException e) {
                quota = quotaPolicy.cold(limit);
            }
        }

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
                        // Over-fetch to limit so gap fill can pick unselected candidates
                        return new ChannelResult(name, channel.recall(query, limit), null);
                    }, executor)
                    .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> new ChannelResult(name, List.of(), ex));
            futures.add(future);
        }

        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        Map<String, List<MovieCandidate>> channelResults = new LinkedHashMap<>();
        for (CompletableFuture<ChannelResult> future : futures) {
            ChannelResult result = future.join();
            if (result.error() != null) {
                healthMonitor.recordFailure(result.channel());
                Throwable err = result.error();
                log.warn("Channel '{}' failed: {}", result.channel(),
                        err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName());
                continue;
            }
            healthMonitor.recordSuccess(result.channel());
            List<MovieCandidate> sorted = result.candidates().stream()
                    .sorted(RecallScoring.BY_SCORE_DESC)
                    .toList();
            channelResults.put(result.channel(), sorted);
        }

        if (quota == null) {
            return legacyMerge(channelResults, query, limit);
        }
        return quotaMerge(channelResults, quota, query, limit);
    }

    private List<MovieCandidate> legacyMerge(Map<String, List<MovieCandidate>> channelResults,
                                              RecommendationQuery query, int limit) {
        Map<String, MovieCandidate> merged = new LinkedHashMap<>();
        for (List<MovieCandidate> candidates : channelResults.values()) {
            for (MovieCandidate c : candidates) {
                if (query.excludedItemIds().contains(c.itemId())) continue;
                merged.merge(c.itemId(), c,
                        (existing, incoming) ->
                                incoming.score() > existing.score() ? incoming : existing);
            }
        }
        return merged.values().stream()
                .sorted(RecallScoring.BY_SCORE_DESC)
                .limit(limit)
                .toList();
    }

    private List<MovieCandidate> quotaMerge(Map<String, List<MovieCandidate>> channelResults,
                                             QuotaSpec quota,
                                             RecommendationQuery query, int limit) {
        Set<String> selectedIds = new LinkedHashSet<>();
        List<MovieCandidate> result = new ArrayList<>();

        for (RecallChannel channel : channels) {
            String name = channel.name();
            List<MovieCandidate> candidates = channelResults.getOrDefault(name, List.of());
            int channelSlots = quota.slotsFor(name);
            int count = 0;
            for (MovieCandidate c : candidates) {
                if (count >= channelSlots) break;
                if (!selectedIds.contains(c.itemId()) && !query.excludedItemIds().contains(c.itemId())) {
                    result.add(c);
                    selectedIds.add(c.itemId());
                    count++;
                }
            }
        }

        if (result.size() < limit) {
            Map<String, MovieCandidate> gapPool = new LinkedHashMap<>();
            for (List<MovieCandidate> candidates : channelResults.values()) {
                for (MovieCandidate c : candidates) {
                    if (!selectedIds.contains(c.itemId()) && !query.excludedItemIds().contains(c.itemId())) {
                        gapPool.merge(c.itemId(), c,
                                (a, b) -> b.score() > a.score() ? b : a);
                    }
                }
            }
            gapPool.values().stream()
                    .sorted(RecallScoring.BY_SCORE_DESC)
                    .limit(limit - result.size())
                    .forEach(result::add);
        }

        result.sort(RecallScoring.BY_SCORE_DESC);
        return result.size() > limit ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
    }

    private record ChannelResult(String channel, List<MovieCandidate> candidates, Throwable error) {
        ChannelResult { Objects.requireNonNull(candidates, "candidates"); }
    }
}
