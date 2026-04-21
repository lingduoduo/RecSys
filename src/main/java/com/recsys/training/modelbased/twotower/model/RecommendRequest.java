package com.recsys.training.modelbased.twotower.model;

import java.util.ArrayList;
import java.util.List;

public class RecommendRequest {
    private String userId;
    private int k = 5;
    private List<String> excludeItemIds = new ArrayList<>();

    public RecommendRequest() {
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public int getK() {
        return k;
    }

    public void setK(int k) {
        this.k = k;
    }

    public List<String> getExcludeItemIds() {
        return excludeItemIds;
    }

    public void setExcludeItemIds(List<String> excludeItemIds) {
        this.excludeItemIds = excludeItemIds == null ? new ArrayList<>() : excludeItemIds;
    }
}
