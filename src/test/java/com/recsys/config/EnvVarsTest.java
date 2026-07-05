package com.recsys.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvVarsTest {

    private static EnvVars.EnvReader env(Map<String, String> values) {
        return values::get;
    }

    @Test
    void readBool_truthyValues_returnTrue() {
        for (String v : new String[]{"true", "TRUE", "1", "yes", "On"}) {
            assertThat(EnvVars.readBool(env(Map.of("FLAG", v)), "FLAG", false))
                    .as("value=%s", v).isTrue();
        }
    }

    @Test
    void readBool_falsyValues_returnFalse() {
        for (String v : new String[]{"false", "FALSE", "0", "no", "Off"}) {
            assertThat(EnvVars.readBool(env(Map.of("FLAG", v)), "FLAG", true))
                    .as("value=%s", v).isFalse();
        }
    }

    @Test
    void readBool_unsetOrBlank_returnsDefault() {
        assertThat(EnvVars.readBool(env(Map.of()), "FLAG", true)).isTrue();
        assertThat(EnvVars.readBool(env(Map.of()), "FLAG", false)).isFalse();
        assertThat(EnvVars.readBool(env(Map.of("FLAG", "   ")), "FLAG", true)).isTrue();
    }

    @Test
    void readBool_unrecognizedValue_returnsDefault() {
        assertThat(EnvVars.readBool(env(Map.of("FLAG", "maybe")), "FLAG", true)).isTrue();
        assertThat(EnvVars.readBool(env(Map.of("FLAG", "maybe")), "FLAG", false)).isFalse();
    }
}
