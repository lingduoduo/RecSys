package com.recsys.modelbased.dto;

import java.util.List;

public record ApiError(String error, List<Violation> violations) {
    public record Violation(String field, String message) {}
}
