package com.recsys.modelbased.twotower.service;

import com.recsys.modelbased.twotower.model.ScoredItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RankingServiceTest {

    private RankingService rankingService;

    @BeforeEach
    void setUp() {
        var artifactService = mock(ModelArtifactService.class);
        when(artifactService.getItemEmbeddings()).thenReturn(Map.of(
                "A", new float[]{1.0f, 0.0f},
                "B", new float[]{0.0f, 1.0f},
                "C", new float[]{0.5f, 0.5f}
        ));
        rankingService = new RankingService(artifactService);
    }

    @Test
    void rank_ordersItemsByInnerProductDescending() {
        // user embedding aligned with A: dot(A)=1.0, dot(C)=0.5, dot(B)=0.0
        float[] userEmb = {1.0f, 0.0f};
        List<ScoredItem> recalled = List.of(
                new ScoredItem("A", 0.3),
                new ScoredItem("B", 0.9),
                new ScoredItem("C", 0.6)
        );

        List<ScoredItem> ranked = rankingService.rank(userEmb, recalled, 3);

        assertThat(ranked).extracting(ScoredItem::itemId)
                .containsExactly("A", "C", "B");
    }

    @Test
    void rank_truncatesToK() {
        float[] userEmb = {1.0f, 0.0f};
        List<ScoredItem> recalled = List.of(
                new ScoredItem("A", 0.9),
                new ScoredItem("B", 0.8),
                new ScoredItem("C", 0.7)
        );

        assertThat(rankingService.rank(userEmb, recalled, 2)).hasSize(2);
    }

    @Test
    void rank_itemWithoutEmbedding_isSkipped() {
        float[] userEmb = {1.0f, 0.0f};
        List<ScoredItem> recalled = List.of(
                new ScoredItem("A", 1.0),
                new ScoredItem("MISSING", 0.5)
        );

        List<ScoredItem> ranked = rankingService.rank(userEmb, recalled, 5);
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).itemId()).isEqualTo("A");
    }

    @Test
    void rank_nullUserEmbedding_returnsEmpty() {
        assertThat(rankingService.rank(null, List.of(new ScoredItem("A", 1.0)), 5)).isEmpty();
    }

    @Test
    void rank_emptyRecall_returnsEmpty() {
        assertThat(rankingService.rank(new float[]{1.0f, 0.0f}, List.of(), 5)).isEmpty();
    }

    @Test
    void rank_kZero_returnsEmpty() {
        assertThat(rankingService.rank(new float[]{1.0f, 0.0f},
                List.of(new ScoredItem("A", 1.0)), 0)).isEmpty();
    }

    @Test
    void rank_duplicateItems_deduplicatedByBestScore() {
        float[] userEmb = {1.0f, 0.0f};
        List<ScoredItem> recalled = List.of(
                new ScoredItem("A", 0.5),
                new ScoredItem("A", 0.9)
        );

        List<ScoredItem> ranked = rankingService.rank(userEmb, recalled, 5);
        assertThat(ranked).hasSize(1);
    }
}
