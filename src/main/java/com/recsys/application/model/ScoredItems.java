package com.recsys.application.model;

import com.recsys.domain.prediction.ScoredItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public final class ScoredItems {

    private ScoredItems() {}

    public static PriorityQueue<ScoredItem> minHeap() {
        return new PriorityQueue<>(Comparator.comparingDouble(ScoredItem::score));
    }

    public static void keepTopK(PriorityQueue<ScoredItem> best, ScoredItem scoredItem, int k) {
        if (best.size() < k) {
            best.offer(scoredItem);
        } else if (scoredItem.score() > best.peek().score()) {
            best.poll();
            best.offer(scoredItem);
        }
    }

    public static List<ScoredItem> descending(PriorityQueue<ScoredItem> best) {
        List<ScoredItem> result = new ArrayList<>(best);
        result.sort(Comparator.comparingDouble(ScoredItem::score).reversed());
        return result;
    }
}
