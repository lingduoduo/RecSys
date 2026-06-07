package com.recsys.featureflags.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.featureflags.models.FeatureFlag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostHogFeatureFlagProviderTest {

    private HttpClient client;
    private HttpResponse<String> response;
    private ObjectMapper objectMapper;
    private PostHogFeatureFlagProvider provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(HttpClient.class);
        response = mock(HttpResponse.class);
        objectMapper = new ObjectMapper();
        provider = new PostHogFeatureFlagProvider(
                "phc_test",
                URI.create("https://posthog.example"),
                Duration.ofSeconds(1),
                client,
                objectMapper);
    }

    @Test
    void resolvesBooleanFlagAndSendsEvaluationContext() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"featureFlags":{"new-ranking":false}}
                """);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        assertThat(provider.resolve(
                FeatureFlag.enabledByDefault("new-ranking"),
                "user-1",
                Map.of("plan", "pro")))
                .contains(false);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = requestCaptor.getValue();
        assertThat(request.uri()).isEqualTo(URI.create("https://posthog.example/decide/?v=3"));
        assertThat(request.timeout()).contains(Duration.ofSeconds(1));

        String body = request.bodyPublisher().orElseThrow()
                .contentLength() >= 0 ? bodyFrom(request) : "";
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("api_key").asText()).isEqualTo("phc_test");
        assertThat(json.path("distinct_id").asText()).isEqualTo("user-1");
        assertThat(json.path("person_properties").path("plan").asText()).isEqualTo("pro");
    }

    @Test
    void treatsMultivariateFlagAsEnabled() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"featureFlags":{"new-ranking":"treatment"}}
                """);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        assertThat(provider.resolve(
                FeatureFlag.disabledByDefault("new-ranking"),
                "user-1",
                Map.of()))
                .contains(true);
    }

    @Test
    void missingIdentityOrHttpFailureIsUnresolved() throws Exception {
        assertThat(provider.resolve(
                FeatureFlag.disabledByDefault("new-ranking"),
                null,
                Map.of()))
                .isEmpty();

        when(response.statusCode()).thenReturn(500);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        assertThat(provider.resolve(
                FeatureFlag.disabledByDefault("new-ranking"),
                "user-1",
                Map.of()))
                .isEmpty();
    }

    private String bodyFrom(HttpRequest request) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> subscriber =
                new java.util.concurrent.Flow.Subscriber<>() {
                    private java.util.concurrent.Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                        this.subscription = subscription;
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(java.nio.ByteBuffer item) {
                        byte[] bytes = new byte[item.remaining()];
                        item.get(bytes);
                        output.writeBytes(bytes);
                    }

                    @Override public void onError(Throwable throwable) { }
                    @Override public void onComplete() { }
                };
        request.bodyPublisher().orElseThrow().subscribe(subscriber);
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
