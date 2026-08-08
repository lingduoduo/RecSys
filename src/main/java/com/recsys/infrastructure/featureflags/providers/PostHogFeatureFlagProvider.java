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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class PostHogFeatureFlagProvider implements FeatureFlagProvider {

    private static final Logger log = LoggerFactory.getLogger(PostHogFeatureFlagProvider.class);

    private final String apiKey;
    private final String distinctIdSalt;
    private final URI decideUri;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PostHogFeatureFlagProvider(String apiKey, String distinctIdSalt, URI host,
                                      Duration requestTimeout) {
        this(apiKey, distinctIdSalt, host, requestTimeout, HttpClient.newHttpClient(), new ObjectMapper());
    }

    public PostHogFeatureFlagProvider(String apiKey, String distinctIdSalt, URI host,
                                      Duration requestTimeout, HttpClient httpClient,
                                      ObjectMapper objectMapper) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("PostHog API key must not be blank");
        }
        // Required, not defaulted. distinct_id is a hash of the application userId, and userIds
        // here are small integers — an unsalted digest over that key space is a rainbow table
        // anyone can build in seconds, so a blank salt would look like a control and be none.
        // Refusing here is the same fail-closed shape as the blank-API-key check above.
        if (distinctIdSalt == null || distinctIdSalt.isBlank()) {
            throw new IllegalArgumentException(
                    "POSTHOG_DISTINCT_ID_SALT must be set when PostHog feature flags are enabled");
        }
        this.apiKey = apiKey;
        this.distinctIdSalt = distinctIdSalt;
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
            body.put("distinct_id", pseudonymize(distinctId));
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

    /**
     * The identifier PostHog is allowed to see: a salted, one-way digest of the application userId.
     *
     * <p>Deterministic and stable across pods and restarts, because the salt is shared
     * configuration — that is what lets PostHog's percentage rollouts bucket a given user
     * consistently. Full digest rather than a prefix: {@code GatewayPrincipal.sha256Prefix}
     * truncates because a rate-limit bucket tolerates collisions, and a flag-targeting key does not.
     *
     * <p>Rotating the salt re-buckets every user, so it is long-lived configuration. See
     * {@code docs/system_design/20_AuthN_AuthZ.md}.
     */
    private String pseudonymize(String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((distinctIdSalt + ":" + userId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
