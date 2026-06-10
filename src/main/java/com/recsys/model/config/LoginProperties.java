package com.recsys.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configures valid API keys for the login endpoint.
 *
 * application.yml:
 *   recsys:
 *     login:
 *       api-keys: "key1,key2"
 */
@Validated
@ConfigurationProperties(prefix = "recsys.login")
public class LoginProperties {

    private String apiKeys = "";

    public String getApiKeys() { return apiKeys; }
    public void setApiKeys(String apiKeys) { this.apiKeys = apiKeys == null ? "" : apiKeys; }

    public Set<String> parsedApiKeys() {
        return Arrays.stream(apiKeys.split(","))
                .map(String::trim)
                .filter(k -> !k.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isEnabled() {
        return !parsedApiKeys().isEmpty();
    }
}
