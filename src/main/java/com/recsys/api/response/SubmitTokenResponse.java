package com.recsys.api.response;

public record SubmitTokenResponse(String token, int expiresInSeconds) {}
