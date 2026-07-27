package com.recsys.application.recommendation;

import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.pagination.RecommendationCursorCodec;
import com.recsys.application.pagination.RecommendationPaginationConfig;
import com.recsys.application.pagination.RecommendationPaginationCoordinator;
import com.recsys.application.pagination.RecommendationPaginationMetrics;
import com.recsys.application.ranking.ScoreRanker;
import com.recsys.application.retrieval.RecallChannel;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationOrchestratorTest {
    private static final int MAX_CANDIDATES = 500;
    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");

    @Test
    void recallIsBoundedAndTerminalMetadataIsExact() {
        AtomicInteger capturedRecallLimit = new AtomicInteger();
        RecommendationOrchestrator orchestrator = orchestrator(
                channel(capturedRecallLimit,
                        candidate("1", 0.9),
                        candidate("2", 0.8)),
                RecommendationHydrator.IDENTITY);

        RecommendationResult result = orchestrator.recommend(
                new RecommendationQuery("u1", 2, Set.of(), null));

        assertThat(capturedRecallLimit.get()).isEqualTo(MAX_CANDIDATES);
        assertThat(result.items()).extracting(RankedMovie::itemId)
                .containsExactly("1", "2");
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void continuesAcrossTwoPagesWithSignedCursor() {
        RecommendationOrchestrator orchestrator = orchestrator(
                channel(new AtomicInteger(),
                        candidate("1", 0.9),
                        candidate("2", 0.8),
                        candidate("3", 0.7)),
                RecommendationHydrator.IDENTITY);

        RecommendationResult firstPage = orchestrator.recommend(
                new RecommendationQuery("u1", 2, Set.of(), null));
        RecommendationResult secondPage = orchestrator.recommend(
                new RecommendationQuery("u1", 2, Set.of(), firstPage.nextCursor()));

        assertThat(firstPage.items()).extracting(RankedMovie::itemId)
                .containsExactly("1", "2");
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).contains(".");
        assertThat(secondPage.items()).extracting(RankedMovie::itemId)
                .containsExactly("3");
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.nextCursor()).isNull();
    }

    @Test
    void rejectsInvalidCursorBeforeRecall() {
        AtomicInteger recallInvocations = new AtomicInteger();
        RecommendationOrchestrator orchestrator = orchestrator(
                channel(recallInvocations, candidate("1", 0.9)),
                RecommendationHydrator.IDENTITY);

        assertThatThrownBy(() -> orchestrator.recommend(
                new RecommendationQuery("u1", 2, Set.of(), "not-a-cursor")))
                .isInstanceOf(RecommendationCursorCodec.InvalidCursorException.class)
                .hasMessage("Invalid recommendation cursor");
        assertThat(recallInvocations.get()).isZero();
    }

    @Test
    void hydratesOnlyReturnedPageItems() {
        AtomicReference<List<RankedMovie>> hydratedItems = new AtomicReference<>();
        RecommendationHydrator hydrator = (query, items) -> {
            hydratedItems.set(List.copyOf(items));
            return items;
        };
        RecommendationOrchestrator orchestrator = orchestrator(
                channel(new AtomicInteger(),
                        candidate("1", 0.9),
                        candidate("2", 0.8),
                        candidate("3", 0.7)),
                hydrator);

        RecommendationResult result = orchestrator.recommend(
                new RecommendationQuery("u1", 2, Set.of(), null));

        assertThat(hydratedItems.get()).extracting(RankedMovie::itemId)
                .containsExactly("1", "2");
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void tracesBudgetExhaustionFromTerminalBoundedPage() {
        List<MovieCandidate> candidates = new ArrayList<>(MAX_CANDIDATES);
        for (int i = 0; i < MAX_CANDIDATES; i++) {
            candidates.add(candidate(Integer.toString(i), MAX_CANDIDATES - i));
        }
        RecommendationOrchestrator orchestrator = orchestrator(
                channel(new AtomicInteger(), candidates.toArray(MovieCandidate[]::new)),
                RecommendationHydrator.IDENTITY);
        RecommendationResult page = null;
        String cursor = null;

        for (int i = 0; i < 5; i++) {
            page = orchestrator.recommend(
                    new RecommendationQuery("u1", 100, Set.of(), cursor));
            cursor = page.nextCursor();
        }

        assertThat(page).isNotNull();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.trace()).containsEntry("paginationBudgetExhausted", "true");
    }

    private static RecommendationOrchestrator orchestrator(
            RecallChannel recall,
            RecommendationHydrator hydrator
    ) {
        return new RecommendationOrchestrator(
                new MultiChannelRecallService(List.of(recall)),
                new ScoreRanker(),
                hydrator,
                pagination(),
                MAX_CANDIDATES);
    }

    private static RecommendationPaginationCoordinator pagination() {
        RecommendationPaginationConfig config = new RecommendationPaginationConfig(
                "a".repeat(32), null, Duration.ofMinutes(15), false, MAX_CANDIDATES);
        RecommendationCursorCodec codec = new RecommendationCursorCodec(
                config, Clock.fixed(NOW, ZoneOffset.UTC));
        return new RecommendationPaginationCoordinator(
                codec,
                new CursorPaginationService(),
                new RecommendationPaginationMetrics(new SimpleMeterRegistry()));
    }

    private static RecallChannel channel(
            AtomicInteger capturedLimit,
            MovieCandidate... candidates
    ) {
        return new RecallChannel() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                capturedLimit.set(limit);
                return List.of(candidates);
            }
        };
    }

    private static MovieCandidate candidate(String itemId, double score) {
        return new MovieCandidate(itemId, score, "test", Map.of());
    }
}
