package com.recsys.application.online;

import com.recsys.infrastructure.messaging.ExperienceCollector;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.util.Pool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

public class OnlineLearner {
    private static final double DEFAULT_LEARNING_RATE = 0.08;
    private static final double DEFAULT_L2 = 0.001;
    private static final double DEFAULT_MAX_ABS_BIAS = 2.0;
    private static final int DEFAULT_MAX_ITEM_COUNT = 10_000;

    private final double learningRate;
    private final double l2;
    private final double maxAbsBias;
    private final int maxItemCount;
    private final ConcurrentHashMap<Integer, Double> itemBias = new ConcurrentHashMap<>();
    private volatile long lastEvictMs = 0L;
    private static final long EVICT_INTERVAL_MS = 5_000L;

    public OnlineLearner() {
        this(DEFAULT_LEARNING_RATE, DEFAULT_L2, DEFAULT_MAX_ABS_BIAS, DEFAULT_MAX_ITEM_COUNT);
    }

    public OnlineLearner(double learningRate, double l2, double maxAbsBias) {
        this(learningRate, l2, maxAbsBias, DEFAULT_MAX_ITEM_COUNT);
    }

    public OnlineLearner(double learningRate, double l2, double maxAbsBias, int maxItemCount) {
        if (learningRate <= 0.0) {
            throw new IllegalArgumentException("learningRate must be positive");
        }
        if (l2 < 0.0) {
            throw new IllegalArgumentException("l2 must be non-negative");
        }
        if (maxAbsBias <= 0.0) {
            throw new IllegalArgumentException("maxAbsBias must be positive");
        }
        if (maxItemCount <= 0) {
            throw new IllegalArgumentException("maxItemCount must be positive");
        }
        this.learningRate = learningRate;
        this.l2 = l2;
        this.maxAbsBias = maxAbsBias;
        this.maxItemCount = maxItemCount;
    }

    public OnlineUpdateSummary learn(ExperienceCollector.RecommendationExperience experience) {
        if (experience == null || experience.items() == null || experience.items().isEmpty()) {
            return new OnlineUpdateSummary(0, 0.0);
        }

        double loss = 0.0;
        int updates = 0;
        for (ExperienceCollector.ItemFeedback item : experience.items()) {
            if (item == null || item.movieId() <= 0) {
                continue;
            }
            double target = normalizedLabel(item.label());
            loss += updateItemBias(item.movieId(), target);
            updates++;
        }

        evictIfNeeded();
        return new OnlineUpdateSummary(updates, updates == 0 ? 0.0 : loss / updates);
    }

    public OnlineUpdateSummary learnAll(Iterable<ExperienceCollector.RecommendationExperience> experiences) {
        if (experiences == null) {
            return new OnlineUpdateSummary(0, 0.0);
        }

        int totalUpdates = 0;
        double weightedLoss = 0.0;
        for (ExperienceCollector.RecommendationExperience experience : experiences) {
            OnlineUpdateSummary summary = learn(experience);
            totalUpdates += summary.updatedItems();
            weightedLoss += summary.averageLoss() * summary.updatedItems();
        }
        return new OnlineUpdateSummary(totalUpdates, totalUpdates == 0 ? 0.0 : weightedLoss / totalUpdates);
    }

    public double scoreAdjustment(int movieId) {
        return itemBias.getOrDefault(movieId, 0.0);
    }

    public Map<Integer, Double> snapshotItemBiases() {
        return Collections.unmodifiableMap(new HashMap<>(itemBias));
    }

    /**
     * Writes all item biases to Redis using a pipeline for a single round-trip.
     * Call periodically (e.g. every N experiences) so biases survive process restarts.
     */
    public void flushToRedis(Pool<Jedis> pool, String keyPrefix) {
        Map<Integer, Double> snapshot = new HashMap<>(itemBias);
        if (snapshot.isEmpty()) return;
        try (Jedis jedis = pool.getResource()) {
            Pipeline pipe = jedis.pipelined();
            for (Map.Entry<Integer, Double> e : snapshot.entrySet()) {
                pipe.set(keyPrefix + ":" + e.getKey(), Double.toString(e.getValue()));
            }
            pipe.sync();
        }
    }

    /**
     * Populates item biases from Redis on startup to resume learning after a restart.
     */
    public void loadFromRedis(Pool<Jedis> pool, String keyPrefix) {
        ScanParams scanParams = new ScanParams().match(keyPrefix + ":*").count(500);
        try (Jedis jedis = pool.getResource()) {
            String cursor = "0";
            do {
                ScanResult<String> res = jedis.scan(cursor, scanParams);
                List<String> keys = res.getResult();
                if (!keys.isEmpty()) {
                    List<String> vals = jedis.mget(keys.toArray(new String[0]));
                    for (int i = 0; i < keys.size(); i++) {
                        String val = vals.get(i);
                        if (val == null || val.isBlank()) continue;
                        String key = keys.get(i);
                        int sep = key.lastIndexOf(':');
                        if (sep < 0 || sep + 1 >= key.length()) continue;
                        try {
                            int movieId = Integer.parseInt(key.substring(sep + 1));
                            itemBias.put(movieId, Double.parseDouble(val));
                        } catch (NumberFormatException ignore) {}
                    }
                }
                cursor = res.getCursor();
            } while (!"0".equals(cursor));
        }
    }

    private void evictIfNeeded() {
        if (itemBias.size() <= maxItemCount) return;
        // Rate-limit eviction: building a toEvict-sized heap over the full 10 K-entry map
        // on every learn() call past the limit burns CPU and allocates GC-pressuring
        // Map.Entry objects. Run at most once every 5 s instead.
        long now = System.currentTimeMillis();
        if (now - lastEvictMs < EVICT_INTERVAL_MS) return;
        lastEvictMs = now;

        int size = itemBias.size();
        if (size <= maxItemCount) return;
        // Evict down to 90% of maxItemCount to build headroom and reduce eviction frequency.
        int toEvict = size - maxItemCount + maxItemCount / 10;
        PriorityQueue<Map.Entry<Integer, Double>> heap = new PriorityQueue<>(
                toEvict + 1,
                Comparator.comparingDouble((Map.Entry<Integer, Double> e) -> Math.abs(e.getValue())).reversed());
        for (Map.Entry<Integer, Double> e : itemBias.entrySet()) {
            heap.offer(e);
            if (heap.size() > toEvict) {
                heap.poll();
            }
        }
        heap.forEach(e -> itemBias.remove(e.getKey()));
    }

    private double updateItemBias(int movieId, double target) {
        double[] loss = new double[1];
        itemBias.compute(movieId, (ignored, currentValue) -> {
            double current = currentValue == null ? 0.0 : currentValue;
            double prediction = sigmoid(current);
            double error = target - prediction;
            loss[0] = logisticLoss(target, prediction);
            double gradient = error - (l2 * current);
            return clamp(current + learningRate * gradient);
        });
        return loss[0];
    }

    private static double normalizedLabel(int label) {
        return Math.max(0.0, Math.min(1.0, label / 3.0));
    }

    private static double sigmoid(double value) {
        return 1.0 / (1.0 + Math.exp(-value));
    }

    private static double logisticLoss(double target, double prediction) {
        double p = Math.max(1e-12, Math.min(1.0 - 1e-12, prediction));
        return -target * Math.log(p) - (1.0 - target) * Math.log(1.0 - p);
    }

    private double clamp(double value) {
        return Math.max(-maxAbsBias, Math.min(maxAbsBias, value));
    }

    public record OnlineUpdateSummary(int updatedItems, double averageLoss) {}
}
