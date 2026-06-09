package com.recsys.modelbased.dto;

public class ApiResponse<T> {

    private final boolean success;
    private final T data;

    private ApiResponse(boolean success, T data) {
        this.success = success;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(true, null);
    }

    public boolean isSuccess() { return success; }
    public T getData() { return data; }
}
