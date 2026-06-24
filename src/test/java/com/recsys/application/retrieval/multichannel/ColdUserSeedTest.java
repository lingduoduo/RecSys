package com.recsys.application.retrieval.multichannel;
import com.recsys.application.retrieval.multichannel.ChannelHealthMonitor;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.user.User;
import com.recsys.infrastructure.dataloading.DataLoader;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.resilience.FaultInjector;
import com.recsys.application.retrieval.RecallChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the built-in cold user (200 = "New User") wired into the seed data:
 * it must stay cataloged (no 404) yet embedding-less (cold quota) so the
 * cold-start path documented in the README keeps working out of the box.
 */
class ColdUserSeedTest {

    private static final int COLD_USER = 200;
    private static final int WARM_USER = 123;

    @Test
    void seedUser200_isColdUser_cataloguedButWithoutEmbedding() {
        Map<Integer, User> users = DataLoader.loadUsers();
        Map<Integer, float[]> userEmbeddings = DataLoader.loadUserEmbeddings();

        // Cataloged → /getrecommendation finds it and does not 404.
        assertThat(users).containsKey(COLD_USER);
        // No embedding → getEmbedding == null → cold quota is selected.
        assertThat(userEmbeddings).doesNotContainKey(COLD_USER);
        // Sanity: the reference warm user really is warm.
        assertThat(users).containsKey(WARM_USER);
        assertThat(userEmbeddings).containsKey(WARM_USER);
    }

    @Test
    void recallService_classifiesSeedUser200AsCold_andUser123AsWarm() {
        // Embedding store backed by the REAL seed embeddings: 200 → null (cold), 123 → present (warm).
        Map<Integer, float[]> seed = DataLoader.loadUserEmbeddings();
        EmbeddingStore userEmb = mock(EmbeddingStore.class);
        when(userEmb.getEmbedding(anyInt())).thenAnswer(inv -> seed.get((Integer) inv.getArgument(0)));

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(
                        channel("embedding",
                                new MovieCandidate("10", 0.9, "embedding", Map.of()),
                                new MovieCandidate("11", 0.8, "embedding", Map.of()),
                                new MovieCandidate("12", 0.7, "embedding", Map.of())),
                        channel("cold_start",
                                new MovieCandidate("20", 0.6, "cold_start", Map.of()),
                                new MovieCandidate("21", 0.5, "cold_start", Map.of()),
                                new MovieCandidate("22", 0.4, "cold_start", Map.of())),
                        channel("trending",
                                new MovieCandidate("30", 0.3, "trending", Map.of())),
                        channel("genre_history",
                                new MovieCandidate("40", 0.2, "genre_history", Map.of())),
                        channel("popularity",
                                new MovieCandidate("50", 0.1, "popularity", Map.of()))),
                new ChannelHealthMonitor(),
                ForkJoinPool.commonPool(),
                200L,
                FaultInjector.NOOP,
                userEmb);

        // Cold user, quota-fill only (limit=6): cold quota gives embedding 0 slots,
        // and the cold_start/trending/genre/popularity channels fill all 6 — so embedding contributes nothing.
        List<MovieCandidate> coldRecall = service.recall(
                new RecommendationQuery(String.valueOf(COLD_USER), 6, Set.of(), null), 6);
        assertThat(coldRecall).filteredOn(c -> "embedding".equals(c.channel())).isEmpty();
        assertThat(coldRecall).filteredOn(c -> "cold_start".equals(c.channel())).isNotEmpty();

        // Warm user, same setup: embedding channel gets its 60% quota and contributes.
        List<MovieCandidate> warmRecall = service.recall(
                new RecommendationQuery(String.valueOf(WARM_USER), 6, Set.of(), null), 6);
        assertThat(warmRecall).filteredOn(c -> "embedding".equals(c.channel())).isNotEmpty();
    }

    private static RecallChannel channel(String name, MovieCandidate... candidates) {
        return new RecallChannel() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                return List.of(candidates);
            }
        };
    }
}
