package com.recsys.modelbased.response;

public record SubmitTokenResponse(String token, int expiresInSeconds) {}
