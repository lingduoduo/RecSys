package com.recsys.featureflags.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.featureflags.models.FeatureFlag;
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
        assertThat(json.path("distinct_id").asText()).isEqualTo("user-1");
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
