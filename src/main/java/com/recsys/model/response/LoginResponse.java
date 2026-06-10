package com.recsys.model.response;

public record LoginResponse(String token, int expiresInSeconds) {}
