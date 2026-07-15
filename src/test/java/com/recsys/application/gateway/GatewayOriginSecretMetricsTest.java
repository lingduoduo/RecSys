package com.recsys.application.gateway;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayOriginSecretMetricsTest {

    static final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            GatewayOriginSecret secret = GatewayOriginSecret.fromEnvironment(
                    Map.of("GATEWAY_ORIGIN_SECRET", "s3cret")::get);
            sb.decorator(GatewayOriginSecret.newDecorator(secret, registry));
            sb.service("/api/recommend",
                    (ctx, req) -> HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{}"));
            sb.service("/health",
                    (ctx, req) -> HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{}"));
        }
    };

    private double rejectCount() {
        return registry.get("gateway_origin_secret_rejected_total").counter().count();
    }

    @Test
    void countsRejectionsButNotAllowedOrExemptRequests() {
        WebClient client = WebClient.of(server.httpUri());
        double before = rejectCount();

        // Rejected: no secret.
        AggregatedHttpResponse rejected = client.get("/api/recommend").aggregate().join();
        assertThat(rejected.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejectCount()).isEqualTo(before + 1);

        // Allowed: correct secret. Must not increment.
        AggregatedHttpResponse allowed = client.execute(RequestHeaders.of(
                HttpMethod.GET, "/api/recommend",
                HttpHeaderNames.of(GatewayOriginSecret.HEADER), "s3cret")).aggregate().join();
        assertThat(allowed.status()).isEqualTo(HttpStatus.OK);
        assertThat(rejectCount()).isEqualTo(before + 1);

        // Exempt: /health with no secret. Must not increment.
        AggregatedHttpResponse exempt = client.get("/health").aggregate().join();
        assertThat(exempt.status()).isEqualTo(HttpStatus.OK);
        assertThat(rejectCount()).isEqualTo(before + 1);
    }

    @Test
    void nullRegistryIsSupported() {
        GatewayOriginSecret secret = GatewayOriginSecret.fromEnvironment(
                Map.of("GATEWAY_ORIGIN_SECRET", "s3cret")::get);
        // Must not throw — the counter is optional.
        assertThat(GatewayOriginSecret.newDecorator(secret, null)).isNotNull();
    }
}
