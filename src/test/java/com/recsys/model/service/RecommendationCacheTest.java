package com.recsys.model.service;

import com.recsys.config.RecommendationCacheProperties;
import com.recsys.model.dto.ScoredItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationCacheTest {

    @Test
    void getOrCompute_timesOutInsteadOfBlockingOnStuckInflightComputation() throws Exception {
        RecommendationCacheProperties props = new RecommendationCacheProperties();
        props.setComputeWaitTimeoutMillis(50);
        RecommendationCache cache = new RecommendationCache(props);
        var key = new RecommendationCache.RecommendationKey("u1", 5, List.of(), "training", "v1");
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(() -> cache.getOrCompute(key, () -> {
                loaderEntered.countDown();
                try {
                    releaseLoader.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of(new ScoredItem("1", 0.9));
            }));

            assertThat(loaderEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> cache.getOrCompute(key, () -> List.of(new ScoredItem("2", 0.1))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Timed out");
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }
    }
}
