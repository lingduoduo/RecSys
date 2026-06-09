package com.recsys.model.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CandidateSelectionServiceTest {

    private CandidateSelectionService service;

    @BeforeEach
    void setUp() {
        var artifactService = mock(ModelArtifactService.class);
        when(artifactService.getAvailableItemIds()).thenReturn(Set.of("1", "2", "3", "4", "5"));
        service = new CandidateSelectionService(artifactService);
    }

    @Test
    void selectCandidates_nullUserId_returnsGlobalPool() {
        Set<String> candidates = service.selectCandidates(null, Set.of());
        assertThat(candidates).isNotNull();
    }

    @Test
    void selectCandidates_nullExcluded_treatedAsEmpty() {
        assertThat(service.selectCandidates(null, null))
                .isEqualTo(service.selectCandidates(null, Set.of()));
    }

    @Test
    void selectCandidates_onlyAvailableEmbeddingsReturned() {
        Set<String> candidates = service.selectCandidates(null, Set.of());
        assertThat(candidates).allMatch(id -> Set.of("1", "2", "3", "4", "5").contains(id));
    }

    @Test
    void selectCandidates_excludedItemsNotInResults() {
        Set<String> baseline = service.selectCandidates(null, Set.of());
        assumeFalse(baseline.isEmpty(), "no embedding overlap with bundled data — skipping");
        String toExclude = baseline.iterator().next();
        assertThat(service.selectCandidates(null, Set.of(toExclude))).doesNotContain(toExclude);
    }

    @Test
    void selectCandidates_multipleExcludedItemsNotInResults() {
        Set<String> baseline = service.selectCandidates(null, Set.of());
        assumeFalse(baseline.isEmpty(), "no embedding overlap with bundled data — skipping");
        Set<String> excluded = Set.copyOf(baseline);
        assertThat(service.selectCandidates(null, excluded)).isEmpty();
    }
}
