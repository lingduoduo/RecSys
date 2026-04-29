package com.recsys.modelbased.model.dto;

import jakarta.validation.constraints.*;

import java.util.ArrayList;
import java.util.List;

public class RecommendRequest {

    @NotBlank(message = "userId is required")
    @Size(max = 50, message = "userId must not exceed 50 characters")
    private String userId;

    @Min(value = 1, message = "k must be at least 1")
    @Max(value = 100, message = "k must be at most 100")
    private int k = 5;

    @Size(max = 500, message = "excludeItemIds must not exceed 500 entries")
    private List<@NotBlank(message = "item IDs in excludeItemIds must not be blank")
                 @Size(max = 50, message = "each excluded item ID must not exceed 50 characters")
                 String> excludeItemIds = new ArrayList<>();

    public RecommendRequest() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public int getK() { return k; }
    public void setK(int k) { this.k = k; }

    public List<String> getExcludeItemIds() { return excludeItemIds; }
    public void setExcludeItemIds(List<String> excludeItemIds) {
        this.excludeItemIds = excludeItemIds == null ? new ArrayList<>() : excludeItemIds;
    }
}
