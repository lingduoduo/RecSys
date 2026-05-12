package com.recsys.streaming;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineLearnerTest {

    @Test
    void learnUpdatesPositiveAndNegativeItemBiasesFromExperience() {
        OnlineLearner learner = new OnlineLearner(0.5, 0.0, 2.0);
        ExperienceCollector.RecommendationExperience experience = new ExperienceCollector.RecommendationExperience(
                "req-1",
                123,
                100L,
                3,
                List.of(
                        new ExperienceCollector.ItemFeedback(9, 1, 3, "order", Map.of()),
                        new ExperienceCollector.ItemFeedback(11, 2, 0, "impression", Map.of())
                )
        );

        OnlineLearner.OnlineUpdateSummary summary = learner.learn(experience);

        assertEquals(2, summary.updatedItems());
        assertTrue(summary.averageLoss() > 0.0);
        assertTrue(learner.scoreAdjustment(9) > 0.0);
        assertTrue(learner.scoreAdjustment(11) < 0.0);
    }

    @Test
    void learnAllAggregatesUpdatesAndSnapshotIsImmutable() {
        OnlineLearner learner = new OnlineLearner(0.5, 0.0, 2.0);
        ExperienceCollector.RecommendationExperience first = experience("req-1", 9, 3);
        ExperienceCollector.RecommendationExperience second = experience("req-2", 11, 0);

        OnlineLearner.OnlineUpdateSummary summary = learner.learnAll(List.of(first, second));

        assertEquals(2, summary.updatedItems());
        assertEquals(2, learner.snapshotItemBiases().size());
        assertTrue(summary.averageLoss() > 0.0);
        Map<Integer, Double> snapshot = learner.snapshotItemBiases();
        assertTrue(snapshot.containsKey(9));
        assertTrue(snapshot.containsKey(11));
    }

    private static ExperienceCollector.RecommendationExperience experience(String requestId, int movieId, int label) {
        return new ExperienceCollector.RecommendationExperience(
                requestId,
                123,
                100L,
                label,
                List.of(new ExperienceCollector.ItemFeedback(movieId, 1, label, "feedback", Map.of()))
        );
    }
}
