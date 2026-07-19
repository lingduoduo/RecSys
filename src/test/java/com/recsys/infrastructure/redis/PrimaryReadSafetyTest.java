package com.recsys.infrastructure.redis;

import com.recsys.application.retrieval.RecallChannel;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.store.RecentHistoryStore;
import com.recsys.infrastructure.store.TrendingStore;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrimaryReadSafetyTest {
    @Test void redisPrimaryDefaultUsesWritableExecute() {
        AtomicBoolean executeCalled = new AtomicBoolean();
        RedisExecutor executor = new StubRedisExecutor() {
            @Override public <T> T execute(Function<RedisCommands<String, String>, T> fn) {
                executeCalled.set(true);
                return null;
            }
            @Override public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
                throw new AssertionError("replica read");
            }
        };
        executor.executePrimaryRead(c -> null);
        assertThat(executeCalled).isTrue();
    }

    @Test void timedRedisPrimaryDefaultFailsClosed() {
        RedisExecutor executor = new StubRedisExecutor();
        assertThatThrownBy(() -> executor.executePrimaryRead(c -> null, Duration.ofMillis(1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void dynamicPrimaryDefaultsFailClosed() {
        RecallChannel channel = new RecallChannel() {
            public String name() { return "dynamic"; }
            public List<com.recsys.domain.item.MovieCandidate> recall(RecommendationQuery q, int l) { return List.of(); }
        };
        TrendingStore trending = (window, k) -> List.of();
        RecentHistoryStore recent = (userId, limit) -> List.of();
        EmbeddingStore embeddings = new StubEmbeddingStore();
        RecommendationQuery query = new RecommendationQuery("1", 1, Set.of(), null);

        assertThatThrownBy(() -> channel.recallPrimary(query, 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> trending.getTopKIdsPrimary("last_hour", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> recent.getRecentMovieIdsPrimary(1, 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> embeddings.getEmbeddingPrimary(1)).isInstanceOf(UnsupportedOperationException.class);
    }

    private static class StubRedisExecutor implements RedisExecutor {
        public <T> T execute(Function<RedisCommands<String, String>, T> fn) { return null; }
        public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) { return null; }
        public void executePipelined(java.util.function.Consumer<StatefulRedisConnection<String, String>> fn) { }
        public void close() { }
    }

    private static class StubEmbeddingStore implements EmbeddingStore {
        public float[] getEmbedding(int id) { return null; }
        public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) { return Map.of(); }
        public void setEmbedding(int id, float[] vector, long ttlSeconds) { }
        public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) { }
        public Set<Integer> scanIds(int maxKeys) { return Set.of(); }
    }
}
