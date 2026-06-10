package com.recsys.model.dto;

public final class ApiResponseUtil {

    private ApiResponseUtil() {}

    public static ApiResponse<Void> error(String message) {
        return ApiResponse.error(message);
    }

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.success(data);
    }

    public static ApiResponse<Void> success() {
        return ApiResponse.success();
    }
}
