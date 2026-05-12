package com.recsys.streaming;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class OnlineLearner {
    private static final double DEFAULT_LEARNING_RATE = 0.08;
    private static final double DEFAULT_L2 = 0.001;
    private static final double DEFAULT_MAX_ABS_BIAS = 2.0;

    private final double learningRate;
    private final double l2;
    private final double maxAbsBias;
    private final ConcurrentHashMap<Integer, Double> itemBias = new ConcurrentHashMap<>();

    public OnlineLearner() {
        this(DEFAULT_LEARNING_RATE, DEFAULT_L2, DEFAULT_MAX_ABS_BIAS);
    }

    public OnlineLearner(double learningRate, double l2, double maxAbsBias) {
        if (learningRate <= 0.0) {
            throw new IllegalArgumentException("learningRate must be positive");
        }
        if (l2 < 0.0) {
            throw new IllegalArgumentException("l2 must be non-negative");
        }
        if (maxAbsBias <= 0.0) {
            throw new IllegalArgumentException("maxAbsBias must be positive");
        }
        this.learningRate = learningRate;
        this.l2 = l2;
        this.maxAbsBias = maxAbsBias;
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
