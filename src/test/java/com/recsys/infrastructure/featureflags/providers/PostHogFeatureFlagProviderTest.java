package com.recsys.infrastructure.featureflags.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.infrastructure.featureflags.models.FeatureFlag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostHogFeatureFlagProviderTest {

    private CapturingHttpClient client;
    private ObjectMapper objectMapper;
    private PostHogFeatureFlagProvider provider;

    @BeforeEach
    void setUp() {
        client = new CapturingHttpClient();
        objectMapper = new ObjectMapper();
        provider = new PostHogFeatureFlagProvider(
                "phc_test",
                "test-salt",
                URI.create("https://posthog.example"),
                Duration.ofSeconds(1),
                client,
                objectMapper);
    }

    @Test
    void resolvesBooleanFlagAndSendsEvaluationContext() throws Exception {
        client.nextResponse = new StubResponse(200, """
                {"featureFlags":{"new-ranking":false}}
                """);

        assertThat(provider.resolve(
                FeatureFlag.enabledByDefault("new-ranking"),
                "user-1",
                Map.of("plan", "pro")))
                .contains(false);

        HttpRequest request = client.lastRequest;
        assertThat(request.uri()).isEqualTo(URI.create("https://posthog.example/decide/?v=3"));
        assertThat(request.timeout()).contains(Duration.ofSeconds(1));

        String body = request.bodyPublisher().orElseThrow()
                .contentLength() >= 0 ? bodyFrom(request) : "";
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("api_key").asText()).isEqualTo("phc_test");
        assertThat(json.path("distinct_id").asText())
                .as("distinct_id must be a full SHA-256 hex digest, not the application userId")
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo("user-1");
        assertThat(json.path("person_properties").path("plan").asText()).isEqualTo("pro");
    }

    @Test
    void treatsMultivariateFlagAsEnabled() throws Exception {
        client.nextResponse = new StubResponse(200, """
                {"featureFlags":{"new-ranking":"treatment"}}
                """);

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

        client.nextResponse = new StubResponse(500, "");

        assertThat(provider.resolve(
                FeatureFlag.disabledByDefault("new-ranking"),
                "user-1",
                Map.of()))
                .isEmpty();
    }

    @Test
    void theRawUserIdNeverAppearsAnywhereInTheRequestBody() throws Exception {
        client.nextResponse = new StubResponse(200, """
                {"featureFlags":{"new-ranking":true}}
                """);

        provider.resolve(FeatureFlag.enabledByDefault("new-ranking"), "user-1", Map.of());

        // Asserted against the whole serialized body, not one field: a future change that added the
        // userId under some other key would still be a disclosure, and this test should catch it.
        assertThat(bodyFrom(client.lastRequest)).doesNotContain("user-1");
    }

    @Test
    void theSameUserAndSaltAlwaysProduceTheSameDistinctId() throws Exception {
        // Stability across instances is what makes PostHog's percentage rollouts bucket a user
        // consistently — a per-process value would reshuffle every pod and every restart.
        assertThat(distinctIdFor("user-1", "test-salt"))
                .isEqualTo(distinctIdFor("user-1", "test-salt"));
    }

    @Test
    void differentSaltsProduceDifferentDistinctIdsForTheSameUser() throws Exception {
        // This is what makes the salt load-bearing rather than decorative: without it the digest
        // of a small integer id space is a seconds-long rainbow table.
        assertThat(distinctIdFor("user-1", "salt-a"))
                .isNotEqualTo(distinctIdFor("user-1", "salt-b"));
    }

    @Test
    void differentUsersProduceDifferentDistinctIds() throws Exception {
        assertThat(distinctIdFor("user-1", "test-salt"))
                .isNotEqualTo(distinctIdFor("user-2", "test-salt"));
    }

    @Test
    void aBlankSaltIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new PostHogFeatureFlagProvider(
                "phc_test", "  ", URI.create("https://posthog.example"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("POSTHOG_DISTINCT_ID_SALT");

        assertThatThrownBy(() -> new PostHogFeatureFlagProvider(
                "phc_test", null, URI.create("https://posthog.example"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("POSTHOG_DISTINCT_ID_SALT");
    }

    /** Resolves once through a fresh provider and returns the distinct_id it sent. */
    private String distinctIdFor(String userId, String salt) throws Exception {
        CapturingHttpClient localClient = new CapturingHttpClient();
        localClient.nextResponse = new StubResponse(200, """
                {"featureFlags":{"new-ranking":true}}
                """);
        PostHogFeatureFlagProvider localProvider = new PostHogFeatureFlagProvider(
                "phc_test", salt, URI.create("https://posthog.example"),
                Duration.ofSeconds(1), localClient, objectMapper);

        localProvider.resolve(FeatureFlag.enabledByDefault("new-ranking"), userId, Map.of());

        return objectMapper.readTree(bodyFrom(localClient.lastRequest)).path("distinct_id").asText();
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

    private static final class CapturingHttpClient extends HttpClient {
        private HttpRequest lastRequest;
        private HttpResponse<String> nextResponse = new StubResponse(200, "{}");

        @Override
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }
        @Override
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override
        public SSLContext sslContext() { return null; }
        @Override
        public SSLParameters sslParameters() { return null; }
        @Override
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override
        public Version version() { return Version.HTTP_1_1; }
        @Override
        public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            lastRequest = request;
            return (HttpResponse<T>) nextResponse;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            throw new UnsupportedOperationException();
        }
    }

    private record StubResponse(int statusCode, String body) implements HttpResponse<String> {
        @Override
        public HttpRequest request() { return null; }
        @Override
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override
        public URI uri() { return URI.create("https://posthog.example/decide/?v=3"); }
        @Override
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
