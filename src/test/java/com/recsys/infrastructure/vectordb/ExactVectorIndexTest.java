package com.recsys.infrastructure.vectordb;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ExactVectorIndexTest {

    @Test
    void addOrUpdate_newVecIsSearchable() {
        ExactVectorIndex idx = new ExactVectorIndex(Map.of(1, new float[]{0f, 1f}));
        idx.addOrUpdate(2, new float[]{1f, 0f});
        List<SearchResult> results = idx.search(new float[]{1f, 0f}, 2, Set.of());
        assertThat(results).extracting(SearchResult::id).contains(2);
        assertThat(results.get(0).id()).isEqualTo(2); // best match to query [1,0]
    }

    @Test
    void addOrUpdate_updatesExistingVec() {
        ExactVectorIndex idx = new ExactVectorIndex(Map.of(1, new float[]{0f, 1f}));
        idx.addOrUpdate(1, new float[]{1f, 0f}); // now aligned with query [1,0]
        List<SearchResult> results = idx.search(new float[]{1f, 0f}, 1, Set.of());
        assertThat(results.get(0).id()).isEqualTo(1);
        assertThat(results.get(0).score()).isGreaterThan(0.9);
    }
}
