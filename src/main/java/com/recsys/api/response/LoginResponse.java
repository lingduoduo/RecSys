package com.recsys.api.response;

public record LoginResponse(String token, int expiresInSeconds) {}
