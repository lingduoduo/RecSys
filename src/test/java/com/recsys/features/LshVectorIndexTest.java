package com.recsys.features;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LshVectorIndexTest {

    private static Map<Integer, float[]> twoItemEmbeddings() {
        Map<Integer, float[]> emb = new HashMap<>();
        emb.put(1, new float[]{1f, 0f});
        emb.put(2, new float[]{0f, 1f});
        return emb;
    }

    private static Map<Integer, float[]> manyEmbeddings(int n) {
        Map<Integer, float[]> emb = new HashMap<>();
        for (int i = 0; i < n; i++) {
            float angle = (float) (2 * Math.PI * i / n);
            emb.put(i, new float[]{(float) Math.cos(angle), (float) Math.sin(angle)});
        }
        return emb;
    }

    @Test
    void search_returnsTopKResults() {
        LshVectorIndex idx = new LshVectorIndex(manyEmbeddings(50));
        List<SearchResult> results = idx.search(new float[]{1f, 0f}, 5, Set.of());
        assertThat(results).hasSize(5);
        // Results should be in descending score order
        for (int i = 0; i < results.size() - 1; i++) {
            assertThat(results.get(i).score()).isGreaterThanOrEqualTo(results.get(i + 1).score());
        }
    }

    @Test
    void search_excludesWatchedIds() {
        LshVectorIndex idx = new LshVectorIndex(manyEmbeddings(50));
        Set<Integer> exclude = Set.of(0, 1, 2, 3, 4);
        List<SearchResult> results = idx.search(new float[]{1f, 0f}, 5, exclude);
        assertThat(results).extracting(SearchResult::id).doesNotContainAnyElementsOf(exclude);
    }

    @Test
    void search_fallsBackToFullScanWhenLshCandidatesBelowK() {
        // Only 2 items — LSH candidates will be far fewer than k=10, forcing full scan
        LshVectorIndex idx = new LshVectorIndex(twoItemEmbeddings());
        List<SearchResult> results = idx.search(new float[]{1f, 0f}, 10, Set.of());
        // Should return both available items, not crash or return empty
        assertThat(results).hasSize(2);
    }

    @Test
    void search_nullExcludeIds_treatedAsEmpty() {
        LshVectorIndex idx = new LshVectorIndex(manyEmbeddings(20));
        List<SearchResult> results = idx.search(new float[]{1f, 0f}, 3, null);
        assertThat(results).hasSize(3);
    }

    @Test
    void search_topResultIsClosestVector() {
        // Item 0 is at angle 0 (aligned with query [1,0]), should rank first
        LshVectorIndex idx = new LshVectorIndex(manyEmbeddings(8));
        List<SearchResult> results = idx.search(new float[]{1f, 0f}, 1, Set.of());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(0);
    }

    @Test
    void name_returnsLsh() {
        assertThat(new LshVectorIndex(twoItemEmbeddings()).name()).isEqualTo("lsh");
    }
}
