package com.recsys.training.modelbased.twotower.model;

public class ScoredItem {
    private String itemId;
    private double score;

    public ScoredItem() {
    }

    public ScoredItem(String itemId, double score) {
        this.itemId = itemId;
        this.score = score;
    }

    public String getItemId() {
        return itemId;
    }

    public double getScore() {
        return score;
    }
}
