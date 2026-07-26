package com.recsys.application.recommendation;

import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.ranking.CandidateRanker;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallResult;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static com.recsys.application.retrieval.multichannel.RecallResult.DegradationOutcome.*;

class RecommendationOrchestratorDegradedTest {

    @Test
    void degradedChannelsAppearInTrace() {
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        when(recall.recallDetailed(any(), anyInt()))
                .thenReturn(new RecallResult(List.<MovieCandidate>of(), Set.of("trending")));
        CandidateRanker ranker = mock(CandidateRanker.class);
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.<RankedMovie>of());

        RecommendationOrchestrator orch = new RecommendationOrchestrator(
                recall, ranker, null, new CursorPaginationService());

        RecommendationResult result = orch.recommend(
                new RecommendationQuery("1", 10, Set.of(), null));

        assertThat(result.trace()).containsEntry("degradedChannels", "trending");
        assertThat(result.trace()).containsEntry("degradationOutcome", "partial");
    }

    @Test
    void degradedChannelsAreSortedAlphabeticallyInTrace() {
        // Deterministic ordering requirement: trace value must match what
        // BaseApiService#writeJsonWithRecallDegraded would sort into the
        // X-Recall-Degraded header, regardless of the input Set's iteration order.
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        when(recall.recallDetailed(any(), anyInt()))
                .thenReturn(new RecallResult(List.<MovieCandidate>of(), Set.of("trending", "momentum")));
        CandidateRanker ranker = mock(CandidateRanker.class);
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.<RankedMovie>of());

        RecommendationOrchestrator orch = new RecommendationOrchestrator(
                recall, ranker, null, new CursorPaginationService());

        RecommendationResult result = orch.recommend(
                new RecommendationQuery("1", 10, Set.of(), null));

        assertThat(result.trace()).containsEntry("degradedChannels", "momentum,trending");
        assertThat(result.trace()).containsEntry("degradationOutcome", "partial");
    }

    @Test
    void noDegradedChannelsKeyWhenFullQuality() {
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        when(recall.recallDetailed(any(), anyInt()))
                .thenReturn(new RecallResult(List.<MovieCandidate>of(), Set.of()));
        CandidateRanker ranker = mock(CandidateRanker.class);
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.<RankedMovie>of());

        RecommendationOrchestrator orch = new RecommendationOrchestrator(
                recall, ranker, null, new CursorPaginationService());

        RecommendationResult result = orch.recommend(
                new RecommendationQuery("1", 10, Set.of(), null));

        assertThat(result.trace()).doesNotContainKey("degradedChannels");
        assertThat(result.trace()).doesNotContainKey("degradationOutcome");
    }

    @Test
    void allChannelFailureIsCarriedWithoutInferringFromEmptyItems() {
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        when(recall.recallDetailed(any(), anyInt()))
                .thenReturn(new RecallResult(List.of(), Set.of("trending"), ALL_CHANNELS));
        CandidateRanker ranker = mock(CandidateRanker.class);
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.of());

        RecommendationResult result = new RecommendationOrchestrator(
                recall, ranker, null, new CursorPaginationService())
                .recommend(new RecommendationQuery("1", 10, Set.of(), null));

        assertThat(result.trace()).containsEntry("degradationOutcome", "all_channels");
    }
}
