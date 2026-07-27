package com.recsys.application.recommendation;

import com.recsys.api.response.RecommendResponse;
import com.recsys.application.experiment.ABTestService;
import com.recsys.application.model.OnnxInferencePipeline;
import com.recsys.application.online.OnlineBlendingPipeline;
import com.recsys.application.online.OnlineRecommendationService;
import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.pagination.RankedListCursor;
import com.recsys.application.pagination.RecommendationCursorCodec;
import com.recsys.application.pagination.RecommendationPaginationConfig;
import com.recsys.application.pagination.RecommendationPaginationCoordinator;
import com.recsys.application.pagination.RecommendationPaginationMetrics;
import com.recsys.application.ranking.CandidateRanker;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallResult;
import com.recsys.domain.item.Movie;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.online.OnlineRecommendationRequest;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.domain.prediction.ScoredItem;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.domain.user.User;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CrossPathConsistencyTest {
    private static final int MAX_CANDIDATES = 500;
    private static final String SIGNING_KEY = "cross-path-pagination-signing-key";
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC);
    private static final List<RankedMovie> RANKED = List.of(
            ranked("10", 1.0, 1),
            ranked("11", 0.75, 2),
            ranked("12", 0.5, 3),
            ranked("13", 0.25, 4));
    private static final List<Movie> ONLINE_MOVIES = List.of(
            movie(10), movie(11), movie(12), movie(13));
    private static final int MIN_CANDIDATE_BUDGET = 101;
    private static final List<RankedMovie> BUDGET_RANKED = IntStream
            .rangeClosed(1, MIN_CANDIDATE_BUDGET)
            .mapToObj(id -> ranked(
                    Integer.toString(id),
                    (double) (MIN_CANDIDATE_BUDGET - id + 1) / MIN_CANDIDATE_BUDGET,
                    id))
            .toList();
    private static final List<Movie> BUDGET_MOVIES = IntStream
            .rangeClosed(1, MIN_CANDIDATE_BUDGET)
            .mapToObj(CrossPathConsistencyTest::movie)
            .toList();

    @Test
    void modelAndOnlinePathsTraverseTheSameSignedTupleContract() {
        CrossPathFixture fixture = new CrossPathFixture(MAX_CANDIDATES, false);
        RecommendationQuery firstQuery = query("1", 2, Set.of(), null);

        RecommendationResult modelFirst = fixture.model.recommend(firstQuery);
        RecommendationResult onlineFirst = fixture.online.recommend(firstQuery);

        assertThat(modelFirst.items()).extracting(RankedMovie::itemId)
                .containsExactlyElementsOf(onlineFirst.items().stream()
                        .map(RankedMovie::itemId).toList());
        assertThat(modelFirst.items()).extracting(RankedMovie::itemId)
                .containsExactly("10", "11");
        assertThat(modelFirst.hasMore()).isTrue();
        assertThat(onlineFirst.hasMore()).isTrue();
        assertThat(modelFirst.nextCursor()).isEqualTo(onlineFirst.nextCursor());

        // Cross the identical tokens between serving paths to prove both codecs accept the
        // same signed (score, itemId) tuple and resume at the same position.
        RecommendationResult modelSecond = fixture.model.recommend(
                query("1", 2, Set.of(), onlineFirst.nextCursor()));
        RecommendationResult onlineSecond = fixture.online.recommend(
                query("1", 2, Set.of(), modelFirst.nextCursor()));

        assertThat(modelSecond.items()).extracting(RankedMovie::itemId)
                .containsExactly("12", "13");
        assertThat(onlineSecond.items()).extracting(RankedMovie::itemId)
                .containsExactly("12", "13");
        assertThat(modelSecond.hasMore()).isFalse();
        assertThat(onlineSecond.hasMore()).isFalse();
        assertThat(modelSecond.nextCursor()).isNull();
        assertThat(onlineSecond.nextCursor()).isNull();
    }

    @Test
    void signedCursorRejectsChangedUserAndExclusionsBeforeEitherSourceRuns() {
        CrossPathFixture fixture = new CrossPathFixture(MAX_CANDIDATES, false);
        RecommendationResult first = fixture.model.recommend(
                query("1", 1, Set.of("90"), null));
        fixture.clearSourceInvocations();

        assertThatThrownBy(() -> fixture.model.recommend(
                query("2", 1, Set.of("90"), first.nextCursor())))
                .isInstanceOf(RecommendationCursorCodec.InvalidCursorException.class)
                .hasMessage("Invalid recommendation cursor");
        assertThatThrownBy(() -> fixture.online.recommend(
                query("2", 1, Set.of("90"), first.nextCursor())))
                .isInstanceOf(RecommendationCursorCodec.InvalidCursorException.class)
                .hasMessage("Invalid recommendation cursor");
        assertThatThrownBy(() -> fixture.model.recommend(
                query("1", 1, Set.of("91"), first.nextCursor())))
                .isInstanceOf(RecommendationCursorCodec.InvalidCursorException.class)
                .hasMessage("Invalid recommendation cursor");
        assertThatThrownBy(() -> fixture.online.recommend(
                query("1", 1, Set.of("91"), first.nextCursor())))
                .isInstanceOf(RecommendationCursorCodec.InvalidCursorException.class)
                .hasMessage("Invalid recommendation cursor");

        verifyNoInteractions(fixture.recall, fixture.ranker, fixture.onlineService);
    }

    @Test
    void changingOnlyLimitContinuesOnBothPaths() {
        CrossPathFixture fixture = new CrossPathFixture(MAX_CANDIDATES, false);
        RecommendationResult modelFirst = fixture.model.recommend(
                query("1", 1, Set.of(), null));
        RecommendationResult onlineFirst = fixture.online.recommend(
                query("1", 1, Set.of(), null));

        RecommendationResult modelRemainder = fixture.model.recommend(
                query("1", 3, Set.of(), onlineFirst.nextCursor()));
        RecommendationResult onlineRemainder = fixture.online.recommend(
                query("1", 3, Set.of(), modelFirst.nextCursor()));

        assertThat(modelRemainder.items()).extracting(RankedMovie::itemId)
                .containsExactly("11", "12", "13");
        assertThat(onlineRemainder.items()).extracting(RankedMovie::itemId)
                .containsExactly("11", "12", "13");
        assertThat(modelRemainder.hasMore()).isFalse();
        assertThat(onlineRemainder.hasMore()).isFalse();
    }

    @Test
    void legacyCursorIsAcceptedAndUpgradedByBothPaths() {
        CrossPathFixture fixture = new CrossPathFixture(MAX_CANDIDATES, true);
        String legacyCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "v2:0.75:11".getBytes(StandardCharsets.UTF_8));

        RecommendationResult model = fixture.model.recommend(
                query("1", 1, Set.of(), legacyCursor));
        RecommendationResult online = fixture.online.recommend(
                query("1", 1, Set.of(), legacyCursor));

        assertThat(model.items()).extracting(RankedMovie::itemId).containsExactly("12");
        assertThat(online.items()).extracting(RankedMovie::itemId).containsExactly("12");
        assertThat(model.hasMore()).isTrue();
        assertThat(online.hasMore()).isTrue();
        assertThat(model.nextCursor()).contains(".");
        assertThat(online.nextCursor()).contains(".");
        assertThat(model.nextCursor()).isNotEqualTo(legacyCursor);
        assertThat(online.nextCursor()).isNotEqualTo(legacyCursor);

        RecommendationResult modelContinuation = fixture.model.recommend(
                query("1", 1, Set.of(), online.nextCursor()));
        RecommendationResult onlineContinuation = fixture.online.recommend(
                query("1", 1, Set.of(), model.nextCursor()));
        assertThat(modelContinuation.items()).extracting(RankedMovie::itemId)
                .containsExactly("13");
        assertThat(onlineContinuation.items()).extracting(RankedMovie::itemId)
                .containsExactly("13");
        assertThat(modelContinuation.hasMore()).isFalse();
        assertThat(onlineContinuation.hasMore()).isFalse();
    }

    @Test
    void cursorBeyondBoundedWindowTerminatesAndRecordsBudgetExhaustion() {
        CrossPathFixture fixture = new CrossPathFixture(
                MIN_CANDIDATE_BUDGET, false, BUDGET_RANKED, BUDGET_MOVIES);
        RecommendationQuery unsignedQuery = query("1", 2, Set.of(), null);
        String beyondWindow = fixture.modelCodec.encode(
                unsignedQuery, new RankedListCursor(0.0, "999"));

        RecommendationResult model = fixture.model.recommend(
                query("1", 2, Set.of(), beyondWindow));
        RecommendationResult online = fixture.online.recommend(
                query("1", 2, Set.of(), beyondWindow));

        for (RecommendationResult result : List.of(model, online)) {
            assertThat(result.items()).isEmpty();
            assertThat(result.hasMore()).isFalse();
            assertThat(result.nextCursor()).isNull();
        }
        assertThat(model.trace()).containsEntry("paginationBudgetExhausted", "true");
        assertThat(fixture.modelRegistry.find("recsys.pagination.budget.exhausted")
                .counter().count()).isEqualTo(1.0);
        assertThat(fixture.onlineRegistry.find("recsys.pagination.budget.exhausted")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void allThreePipelinesReturnNonEmptyResultForSameUserId() {
        RecommendationQuery query = query("1", 5, Set.of(), null);

        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        CandidateRanker ranker = mock(CandidateRanker.class);
        RankedMovie rankedMovie = ranked("10", 0.9, 1);
        when(recall.recallDetailed(any(), anyInt())).thenReturn(new RecallResult(
                List.of(mock(MovieCandidate.class)), Set.of()));
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.of(rankedMovie));
        RecommendationPipeline path1 = new RecommendationOrchestrator(
                recall, ranker, RecommendationHydrator.IDENTITY,
                pagination(false, MAX_CANDIDATES, new SimpleMeterRegistry()),
                MAX_CANDIDATES);
        RecommendationResult r1 = path1.recommend(query);

        RecommendationService onnxService = mock(RecommendationService.class);
        ABTestService abTest = mock(ABTestService.class);
        when(abTest.getAssignmentForUser(any())).thenReturn(
                new ABTestService.Assignment("training", 0, "default", true));
        when(onnxService.recommend(any(), any())).thenReturn(
                new RecommendResponse("1", "v1", "training",
                        List.of(new ScoredItem("42", 0.8))));
        RecommendationPipeline path2 = new OnnxInferencePipeline(onnxService, abTest);
        RecommendationResult r2 = path2.recommend(query);

        OnlineRecommendationService onlineService = mock(OnlineRecommendationService.class);
        when(onlineService.recommend(any(OnlineRecommendationRequest.class))).thenReturn(
                new OnlineRecommendationResult(
                        new User(1, "Alice"), "last_hour", "online",
                        List.of(), List.of(), List.of(movie(7))));
        RecommendationPipeline path3 = new OnlineBlendingPipeline(
                onlineService,
                pagination(false, MAX_CANDIDATES, new SimpleMeterRegistry()),
                MAX_CANDIDATES);
        RecommendationResult r3 = path3.recommend(query);

        for (RecommendationResult result : List.of(r1, r2, r3)) {
            assertThat(result.userId()).isEqualTo("1");
            assertThat(result.items()).isNotEmpty();
            assertThat(result.items().get(0).rank()).isEqualTo(1);
        }
    }

    private static RecommendationQuery query(
            String userId, int limit, Set<String> exclusions, String cursor
    ) {
        return new RecommendationQuery(userId, limit, exclusions, cursor);
    }

    private static RankedMovie ranked(String itemId, double score, int rank) {
        return new RankedMovie(itemId, score, rank, Map.of());
    }

    private static Movie movie(int id) {
        return new Movie(id, "Movie " + id, 2020, List.of());
    }

    private static RecommendationPaginationCoordinator pagination(
            boolean acceptLegacy,
            int maxCandidates,
            SimpleMeterRegistry registry
    ) {
        RecommendationPaginationConfig config = new RecommendationPaginationConfig(
                SIGNING_KEY, null, Duration.ofMinutes(15), acceptLegacy, maxCandidates);
        return new RecommendationPaginationCoordinator(
                new RecommendationCursorCodec(config, FIXED_CLOCK),
                new CursorPaginationService(),
                new RecommendationPaginationMetrics(registry));
    }

    private static final class CrossPathFixture {
        private final SimpleMeterRegistry modelRegistry = new SimpleMeterRegistry();
        private final SimpleMeterRegistry onlineRegistry = new SimpleMeterRegistry();
        private final MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        private final CandidateRanker ranker = mock(CandidateRanker.class);
        private final OnlineRecommendationService onlineService =
                mock(OnlineRecommendationService.class);
        private final RecommendationCursorCodec modelCodec;
        private final RecommendationPipeline model;
        private final RecommendationPipeline online;

        private CrossPathFixture(int maxCandidates, boolean acceptLegacy) {
            this(maxCandidates, acceptLegacy, RANKED, ONLINE_MOVIES);
        }

        private CrossPathFixture(
                int maxCandidates,
                boolean acceptLegacy,
                List<RankedMovie> rankedItems,
                List<Movie> onlineMovies
        ) {
            RecommendationPaginationConfig config = new RecommendationPaginationConfig(
                    SIGNING_KEY, null, Duration.ofMinutes(15), acceptLegacy, maxCandidates);
            modelCodec = new RecommendationCursorCodec(config, FIXED_CLOCK);
            RecommendationPaginationCoordinator modelPagination =
                    new RecommendationPaginationCoordinator(
                            modelCodec,
                            new CursorPaginationService(),
                            new RecommendationPaginationMetrics(modelRegistry));
            RecommendationPaginationCoordinator onlinePagination =
                    new RecommendationPaginationCoordinator(
                            new RecommendationCursorCodec(config, FIXED_CLOCK),
                            new CursorPaginationService(),
                            new RecommendationPaginationMetrics(onlineRegistry));

            when(recall.recallDetailed(any(), anyInt())).thenReturn(
                    new RecallResult(List.of(mock(MovieCandidate.class)), Set.of()));
            when(ranker.rank(any(), any(), anyInt())).thenReturn(rankedItems);
            when(onlineService.recommend(any(OnlineRecommendationRequest.class))).thenReturn(
                    new OnlineRecommendationResult(
                            new User(1, "Alice"), "last_hour", "online",
                            List.of(), List.of(), onlineMovies));

            model = new RecommendationOrchestrator(
                    recall, ranker, RecommendationHydrator.IDENTITY,
                    modelPagination, maxCandidates);
            online = new OnlineBlendingPipeline(
                    onlineService, onlinePagination, maxCandidates);
        }

        private void clearSourceInvocations() {
            clearInvocations(recall, ranker, onlineService);
        }
    }
}
