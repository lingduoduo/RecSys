package com.recsys.model.response;

public record SubmitTokenResponse(String token, int expiresInSeconds) {}
