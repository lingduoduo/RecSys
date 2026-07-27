package com.recsys.application.gateway;

import com.recsys.config.EnvVars;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiDeprecationDecoratorTest {

    private static EnvVars.EnvReader env(Map<String, String> values) {
        return values::get;
    }

    private static ApiDeprecationDecorator enabled() {
        return ApiDeprecationDecorator.fromEnvironment(
                env(Map.of("GATEWAY_DEPRECATION_SUNSET", "2027-07-27")));
    }

    @Test
    void disabledWhenSunsetUnset() {
        ApiDeprecationDecorator decorator = ApiDeprecationDecorator.fromEnvironment(env(Map.of()));
        assertThat(decorator.isEnabled()).isFalse();
        assertThat(decorator.isDeprecated("/api/catalog/item")).isFalse();
    }

    @Test
    void disabledWhenSunsetIsUnparseable() {
        ApiDeprecationDecorator decorator = ApiDeprecationDecorator.fromEnvironment(
                env(Map.of("GATEWAY_DEPRECATION_SUNSET", "not-a-date")));
        assertThat(decorator.isEnabled()).isFalse();
    }

    @Test
    void unversionedApiPathIsDeprecated() {
        assertThat(enabled().isDeprecated("/api/movies/movie")).isTrue();
    }

    @Test
    void versionedApiPathIsNotDeprecated() {
        assertThat(enabled().isDeprecated("/api/v1/movies/movie")).isFalse();
    }

    @Test
    void aliasRouteStaysDeprecatedEvenWhenVersioned() {
        assertThat(enabled().isDeprecated("/api/v1/catalog/item")).isTrue();
        assertThat(enabled().isDeprecated("/api/v1/model/predict")).isTrue();
        assertThat(enabled().isDeprecated("/api/v1/online/features")).isTrue();
    }

    @Test
    void healthAndMetricsAreExempt() {
        assertThat(enabled().isDeprecated("/health")).isFalse();
        assertThat(enabled().isDeprecated("/health/ready")).isFalse();
        assertThat(enabled().isDeprecated("/metrics")).isFalse();
    }

    @Test
    void nonApiPathIsNotDeprecated() {
        assertThat(enabled().isDeprecated("/some/other/path")).isFalse();
    }

    @Test
    void sunsetIsFormattedAsAnHttpDate() {
        assertThat(enabled().sunsetHeaderValue()).isEqualTo("Tue, 27 Jul 2027 00:00:00 GMT");
    }

    @Test
    void successorLinkIsEmittedForTheUnversionedClass() {
        assertThat(enabled().successorLink("/api/catalog/item"))
                .isEqualTo("</api/v1/catalog/item>; rel=\"successor-version\"");
    }

    @Test
    void successorLinkIsAbsentForAnAlreadyVersionedAliasRoute() {
        // /api/v1/catalog/item is deprecated as an alias route, but /api/catalog and /api/movies
        // strip to different backend paths — there is no mechanical successor to advertise.
        assertThat(enabled().successorLink("/api/v1/catalog/item")).isNull();
    }
}
