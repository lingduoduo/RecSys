package com.recsys.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LoginRequest {

    @NotBlank(message = "apiKey is required")
    @Size(max = 256, message = "apiKey must not exceed 256 characters")
    private String apiKey;

    public LoginRequest() {}

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
