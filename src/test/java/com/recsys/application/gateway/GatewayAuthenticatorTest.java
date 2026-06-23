package com.recsys.application.gateway;
import com.recsys.application.gateway.GatewayAuthenticator;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayAuthenticatorTest {

    @Test
    void disabledWhenNoApiKeysConfigured() {
        GatewayAuthenticator auth = GatewayAuthenticator.fromEnvironment(Map.<String, String>of()::get);
        assertThat(auth.isEnabled()).isFalse();
        assertThat(auth.check(RequestHeaders.of(HttpMethod.GET, "/api/model/recommend"), "/api/model/recommend")).isNull();
    }

    @Test
    void acceptsApiKeyHeader() {
        GatewayAuthenticator auth = GatewayAuthenticator.fromEnvironment(Map.of("GATEWAY_API_KEYS", "alpha,beta")::get);
        RequestHeaders headers = RequestHeaders.builder(HttpMethod.GET, "/api/model/recommend")
                .add("x-api-key", " beta ")
                .build();
        assertThat(auth.isEnabled()).isTrue();
        assertThat(auth.check(headers, "/api/model/recommend")).isNull();
    }

    @Test
    void acceptsBearerToken() {
        GatewayAuthenticator auth = GatewayAuthenticator.fromEnvironment(Map.of("GATEWAY_API_KEYS", "alpha")::get);
        RequestHeaders headers = RequestHeaders.builder(HttpMethod.GET, "/api/model/recommend")
                .add("authorization", "Bearer alpha")
                .build();
        assertThat(auth.check(headers, "/api/model/recommend")).isNull();
    }

    @Test
    void rejectsMissingCredential() {
        GatewayAuthenticator auth = GatewayAuthenticator.fromEnvironment(Map.of("GATEWAY_API_KEYS", "alpha")::get);
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/api/model/recommend");
        var rejection = auth.check(headers, "/api/model/recommend");
        assertThat(rejection).isNotNull();
        assertThat(rejection.aggregate().join().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void publicPathBypassesAuth() {
        GatewayAuthenticator auth = GatewayAuthenticator.fromEnvironment(Map.of("GATEWAY_API_KEYS", "alpha")::get);
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/health");
        assertThat(auth.check(headers, "/health")).isNull();
    }
}
