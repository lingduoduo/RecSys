package com.recsys.application.gateway;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.recsys.ratelimit.GatewayRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserScopeAuthorizationTest {

    private static final MicroserviceRoute CATALOG = new MicroserviceRoute(
            "catalog", "/api/catalog", "CATALOG_SERVICE_URL",
            URI.create("http://localhost:6010"), "/health", "recsys-catalog-serving");

    private static final MicroserviceRoute LLM = new MicroserviceRoute(
            "llm", "/api/llm", "LLM_SERVICE_URL", URI.create("http://localhost:11434"), "/api/tags");

    @Test
    void serviceTierIsUnaffected() {
        // The regression that proves "no behavior change today": every real caller is an API key.
        assertNull(forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=999", get(), GatewayPrincipal.ofApiKey("key-1")));
        assertNull(forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=999", get(), GatewayPrincipal.anonymous()));
    }

    @Test
    void userTierMatchingItsOwnIdIsAllowed() {
        assertNull(forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=42", get(), user("42")));
    }

    @Test
    void userTierNamingAnotherUserIsForbidden() {
        HttpResponse denied = forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=43", get(), user("42"));
        assertNotNull(denied);
        AggregatedHttpResponseAssert.assertForbidden(denied);
    }

    @Test
    void userTierWithNoResolvedClaimIsForbidden() {
        // Fails closed: a claim-name misconfiguration must not read as service-tier freedom.
        assertNotNull(forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=42", get(), user("")));
    }

    @Test
    void userTierWithNoUserIdInTheRequestIsForbidden() {
        // Authorize before validate: an unidentifiable subject is denied, not forwarded for the
        // backend to 400.
        assertNotNull(forwarder().authorizeUserScope(CATALOG, "/getuser", get(), user("42")));
    }

    @Test
    void bodyRoutesAreCheckedToo() {
        assertNull(forwarder().authorizeUserScope(
                CATALOG, "/v2/recommend", post("{\"userId\":\"42\"}"), user("42")));
        assertNotNull(forwarder().authorizeUserScope(
                CATALOG, "/v2/recommend", post("{\"userId\":\"43\"}"), user("42")));
    }

    @Test
    void idsAreComparedExactlyWithNoNumericNormalization() {
        // Same discipline as cacheKeyIntParam on the CDN-cached routes: one spelling, one identity.
        assertNotNull(forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=042", get(), user("42")));
    }

    @Test
    void undeclaredRoutesAreUntouched() {
        assertNull(forwarder().authorizeUserScope(
                CATALOG, "/item?id=7", get(), user("42")));
        // A route with no registry service name can never match the table.
        assertNull(forwarder().authorizeUserScope(LLM, "/api/tags", get(), user("42")));
    }

    @Test
    void denialsAreCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayRequestForwarder forwarder = forwarder(registry);

        forwarder.authorizeUserScope(CATALOG, "/getuser?userId=43", get(), user("42"));
        forwarder.authorizeUserScope(CATALOG, "/getuser?userId=44", get(), user("42"));

        assertEquals(2.0, registry.get("gateway_user_scope_rejected_total").counter().count());
    }

    private static GatewayPrincipal user(String appUserId) {
        return GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("sub-1", "app-client", "access", appUserId));
    }

    private static AggregatedHttpRequest get() {
        return AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/api/catalog/getuser"), HttpData.empty());
    }

    private static AggregatedHttpRequest post(String json) {
        return AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/api/recommend"), HttpData.ofUtf8(json));
    }

    private static GatewayRequestForwarder forwarder() {
        return forwarder(null);
    }

    private static GatewayRequestForwarder forwarder(io.micrometer.core.instrument.MeterRegistry registry) {
        return new GatewayRequestForwarder(
                List.of(CATALOG, LLM), Duration.ofSeconds(1), Map.of(),
                GatewayRateLimiter.disabled(), registry);
    }

    /** Keeps the status assertion in one place; the body must never echo the requested id. */
    private static final class AggregatedHttpResponseAssert {
        static void assertForbidden(HttpResponse response) {
            var aggregated = response.aggregate().join();
            assertEquals(HttpStatus.FORBIDDEN, aggregated.status());
            assertEquals("{\"error\":\"forbidden: request is not scoped to the authenticated user\"}",
                    aggregated.contentUtf8());
        }
    }
}
