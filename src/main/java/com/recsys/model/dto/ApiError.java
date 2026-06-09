package com.recsys.model.dto;

import java.util.List;

public record ApiError(String error, List<Violation> violations) {
    public record Violation(String field, String message) {}
}
