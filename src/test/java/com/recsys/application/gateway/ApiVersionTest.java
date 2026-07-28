package com.recsys.application.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiVersionTest {

    @Test
    void parse_stripsExplicitVersionSegment() {
        ApiVersion v = ApiVersion.parse("/api/v1/users/profile");
        assertThat(v.version()).isEqualTo(1);
        assertThat(v.path()).isEqualTo("/api/users/profile");
        assertThat(v.explicit()).isTrue();
        assertThat(v.supported()).isTrue();
    }

    @Test
    void parse_treatsUnversionedPathAsImplicitV1() {
        ApiVersion v = ApiVersion.parse("/api/users/profile");
        assertThat(v.version()).isEqualTo(1);
        assertThat(v.path()).isEqualTo("/api/users/profile");
        assertThat(v.explicit()).isFalse();
        assertThat(v.supported()).isTrue();
    }

    @Test
    void parse_reportsUnsupportedVersionButStillStrips() {
        ApiVersion v = ApiVersion.parse("/api/v2/users");
        assertThat(v.version()).isEqualTo(2);
        assertThat(v.path()).isEqualTo("/api/users");
        assertThat(v.explicit()).isTrue();
        assertThat(v.supported()).isFalse();
    }

    @Test
    void parse_doesNotTreatResourceNamedVersionAsAVersion() {
        ApiVersion v = ApiVersion.parse("/api/version/x");
        assertThat(v.path()).isEqualTo("/api/version/x");
        assertThat(v.explicit()).isFalse();
    }

    @Test
    void parse_requiresAtLeastOneDigit() {
        ApiVersion v = ApiVersion.parse("/api/v/x");
        assertThat(v.path()).isEqualTo("/api/v/x");
        assertThat(v.explicit()).isFalse();
    }

    @Test
    void parse_rejectsMoreThanFourDigitsSoIntegerCannotOverflow() {
        ApiVersion v = ApiVersion.parse("/api/v99999/x");
        assertThat(v.path()).isEqualTo("/api/v99999/x");
        assertThat(v.explicit()).isFalse();
    }

    @Test
    void parse_requiresASegmentBoundaryAfterTheDigits() {
        ApiVersion v = ApiVersion.parse("/api/v1x/foo");
        assertThat(v.path()).isEqualTo("/api/v1x/foo");
        assertThat(v.explicit()).isFalse();
    }

    @Test
    void parse_handlesBareVersionedApiRoot() {
        ApiVersion v = ApiVersion.parse("/api/v1");
        assertThat(v.path()).isEqualTo("/api");
        assertThat(v.explicit()).isTrue();
    }

    @Test
    void parse_leavesNonApiPathsAlone() {
        ApiVersion v = ApiVersion.parse("/health");
        assertThat(v.path()).isEqualTo("/health");
        assertThat(v.explicit()).isFalse();
        assertThat(v.supported()).isTrue();
    }

    @Test
    void parse_normalizesNullAndBlankToRoot() {
        assertThat(ApiVersion.parse(null).path()).isEqualTo("/");
        assertThat(ApiVersion.parse("").path()).isEqualTo("/");
    }

    @Test
    void parse_handlesBareApiRootWithTrailingSlash() {
        ApiVersion v = ApiVersion.parse("/api/");
        assertThat(v.version()).isEqualTo(1);
        assertThat(v.path()).isEqualTo("/api/");
        assertThat(v.explicit()).isFalse();
        assertThat(v.supported()).isTrue();
    }

    @Test
    void parse_handlesBareApiRoot() {
        ApiVersion v = ApiVersion.parse("/api");
        assertThat(v.version()).isEqualTo(1);
        assertThat(v.path()).isEqualTo("/api");
        assertThat(v.explicit()).isFalse();
        assertThat(v.supported()).isTrue();
    }

    @Test
    void parse_rejectsLeadingZeroSoItIsNotAnAlternateSpellingOfAnExplicitVersion() {
        assertThat(ApiVersion.parse("/api/v01/users").explicit()).isFalse();
        assertThat(ApiVersion.parse("/api/v01/users").path()).isEqualTo("/api/v01/users");
        assertThat(ApiVersion.parse("/api/v001/users").explicit()).isFalse();
        assertThat(ApiVersion.parse("/api/v0001/users").explicit()).isFalse();
    }

    @Test
    void parse_singleZeroDigitIsStillAnExplicitVersion() {
        // "v0" has no padding to strip — it is a genuine (unsupported) explicit version, not a
        // zero-padded spelling of v1.
        ApiVersion v = ApiVersion.parse("/api/v0/users");
        assertThat(v.explicit()).isTrue();
        assertThat(v.version()).isEqualTo(0);
        assertThat(v.supported()).isFalse();
    }

    @Test
    void parse_rejectsNonAsciiDigitsEvenThoughCharacterIsDigitWouldAcceptThem() {
        // U+0661 ARABIC-INDIC DIGIT ONE. Character.isDigit('١') is true, but only ASCII
        // digits may form a version segment.
        ApiVersion v = ApiVersion.parse("/api/v١/users");
        assertThat(v.explicit()).isFalse();
        assertThat(v.path()).isEqualTo("/api/v١/users");
    }

    @Test
    void unsupportedMessage_namesTheSupportedVersions() {
        assertThat(ApiVersion.parse("/api/v2/users").unsupportedMessage())
                .isEqualTo("unsupported API version: v2; supported: v1");
    }

    @Test
    void versioned_buildsTheCanonicalSpelling() {
        assertThat(ApiVersion.versioned(1, "/api/catalog/item")).isEqualTo("/api/v1/catalog/item");
        assertThat(ApiVersion.versioned(1, "/api")).isEqualTo("/api/v1");
        assertThat(ApiVersion.versioned(1, "/health")).isEqualTo("/health");
    }
}
