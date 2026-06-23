package com.recsys.infrastructure.featureflags.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.infrastructure.featureflags.FeatureFlagProvider;
import com.recsys.infrastructure.featureflags.models.FeatureFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class PostHogFeatureFlagProvider implements FeatureFlagProvider {

    private static final Logger log = LoggerFactory.getLogger(PostHogFeatureFlagProvider.class);

    private final String apiKey;
    private final URI decideUri;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PostHogFeatureFlagProvider(String apiKey, URI host, Duration requestTimeout) {
        this(apiKey, host, requestTimeout, HttpClient.newHttpClient(), new ObjectMapper());
    }

    public PostHogFeatureFlagProvider(String apiKey, URI host, Duration requestTimeout,
                                      HttpClient httpClient, ObjectMapper objectMapper) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("PostHog API key must not be blank");
        }
        this.apiKey = apiKey;
        this.decideUri = Objects.requireNonNull(host, "host").resolve("/decide/?v=3");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Optional<Boolean> resolve(FeatureFlag flag, String distinctId, Map<String, Object> properties) {
        if (distinctId == null || distinctId.isBlank()) {
            return Optional.empty();
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("api_key", apiKey);
            body.put("distinct_id", distinctId);
            if (!properties.isEmpty()) {
                body.put("person_properties", properties);
            }

            HttpRequest request = HttpRequest.newBuilder(decideUri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("PostHog feature flag request failed with status {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode value = objectMapper.readTree(response.body()).path("featureFlags").get(flag.key());
            if (value == null || value.isNull() || value.isMissingNode()) {
                return Optional.empty();
            }
            if (value.isBoolean()) {
                return Optional.of(value.booleanValue());
            }
            return Optional.of(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("PostHog feature flag request failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
