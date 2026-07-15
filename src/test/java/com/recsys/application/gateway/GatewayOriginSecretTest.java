package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayOriginSecretTest {

    private static GatewayOriginSecret withSecret(String value) {
        Map<String, String> env = Map.of("GATEWAY_ORIGIN_SECRET", value);
        return GatewayOriginSecret.fromEnvironment(env::get);
    }

    private static RequestHeaders headers(String path, String secret) {
        if (secret == null) {
            return RequestHeaders.of(HttpMethod.GET, path);
        }
        return RequestHeaders.of(HttpMethod.GET, path,
                HttpHeaderNames.of(GatewayOriginSecret.HEADER), secret);
    }

    @Test
    void disabledWhenEnvVarUnset() {
        GatewayOriginSecret secret = GatewayOriginSecret.fromEnvironment(name -> null);
        assertThat(secret.isEnabled()).isFalse();
        // Local dev: everything passes.
        assertThat(secret.isAllowed(headers("/api/recommend", null), "/api/recommend")).isTrue();
    }

    @Test
    void disabledWhenEnvVarBlank() {
        assertThat(withSecret("   ").isEnabled()).isFalse();
    }

    @Test
    void allowsMatchingSecret() {
        GatewayOriginSecret secret = withSecret("s3cret");
        assertThat(secret.isEnabled()).isTrue();
        assertThat(secret.isAllowed(headers("/api/recommend", "s3cret"), "/api/recommend")).isTrue();
    }

    @Test
    void rejectsWrongSecret() {
        assertThat(withSecret("s3cret")
                .isAllowed(headers("/api/recommend", "wrong"), "/api/recommend")).isFalse();
    }

    @Test
    void rejectsMissingSecret() {
        assertThat(withSecret("s3cret")
                .isAllowed(headers("/api/recommend", null), "/api/recommend")).isFalse();
    }

    @Test
    void exemptsHealthSoAlbAndKubeletProbesStillPass() {
        GatewayOriginSecret secret = withSecret("s3cret");
        assertThat(secret.isAllowed(headers("/health", null), "/health")).isTrue();
    }

    @Test
    void exemptsMetricsSoPrometheusScrapeStillPasses() {
        GatewayOriginSecret secret = withSecret("s3cret");
        assertThat(secret.isAllowed(headers("/metrics", null), "/metrics")).isTrue();
    }

    @Test
    void exemptionIsBoundaryMatchedNotPrefixMatched() {
        GatewayOriginSecret secret = withSecret("s3cret");
        // /healthcheck must NOT inherit /health's exemption.
        assertThat(secret.isAllowed(headers("/healthcheck", null), "/healthcheck")).isFalse();
    }

    @Test
    void acceptsEitherSecretDuringRotation() {
        GatewayOriginSecret secret = withSecret("old-secret,new-secret");
        assertThat(secret.isEnabled()).isTrue();
        assertThat(secret.isAllowed(headers("/api/recommend", "old-secret"), "/api/recommend")).isTrue();
        assertThat(secret.isAllowed(headers("/api/recommend", "new-secret"), "/api/recommend")).isTrue();
    }

    @Test
    void rejectsASecretNotInTheSet() {
        GatewayOriginSecret secret = withSecret("old-secret,new-secret");
        assertThat(secret.isAllowed(headers("/api/recommend", "other"), "/api/recommend")).isFalse();
    }

    @Test
    void trimsWhitespaceAroundCsvEntries() {
        GatewayOriginSecret secret = withSecret("  old-secret , new-secret  ");
        assertThat(secret.isAllowed(headers("/api/recommend", "old-secret"), "/api/recommend")).isTrue();
        assertThat(secret.isAllowed(headers("/api/recommend", "new-secret"), "/api/recommend")).isTrue();
    }

    @Test
    void ignoresEmptyCsvEntries() {
        GatewayOriginSecret secret = withSecret("old-secret,,new-secret,");
        assertThat(secret.isEnabled()).isTrue();
        assertThat(secret.isAllowed(headers("/api/recommend", "old-secret"), "/api/recommend")).isTrue();
        // An empty entry must never become a match-anything secret.
        assertThat(secret.isAllowed(headers("/api/recommend", ""), "/api/recommend")).isFalse();
    }

    @Test
    void commaOnlyValueIsTreatedAsDisabled() {
        GatewayOriginSecret secret = withSecret(",,,");
        assertThat(secret.isEnabled()).isFalse();
    }
}
