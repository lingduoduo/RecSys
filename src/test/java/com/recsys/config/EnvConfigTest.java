package com.recsys.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvConfigTest {

    // Reliably testable without mutating process env: an absent variable always
    // falls back to the supplied default. Uses an env-var name that will not exist.
    private static final String ABSENT = "RECSYS_ENVCONFIG_TEST_ABSENT_VAR";

    @Test
    void readInt_returnsDefaultWhenAbsent() {
        assertThat(EnvConfig.readInt(ABSENT, 7)).isEqualTo(7);
    }

    @Test
    void readLong_returnsDefaultWhenAbsent() {
        assertThat(EnvConfig.readLong(ABSENT, 9_999_999_999L)).isEqualTo(9_999_999_999L);
    }

    @Test
    void readDouble_returnsDefaultWhenAbsent() {
        assertThat(EnvConfig.readDouble(ABSENT, 0.75)).isEqualTo(0.75);
    }
}
