package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicaConfigTest {

    @Test
    void parse_fullSpec_hostPortAz() {
        ReplicaConfig cfg = ReplicaConfig.parse("redis-b.internal:6380@us-east-1b");
        assertThat(cfg.host()).isEqualTo("redis-b.internal");
        assertThat(cfg.port()).isEqualTo(6380);
        assertThat(cfg.az()).isEqualTo("us-east-1b");
    }

    @Test
    void parse_noAz_defaultsToUnknown() {
        ReplicaConfig cfg = ReplicaConfig.parse("redis-c:6379");
        assertThat(cfg.host()).isEqualTo("redis-c");
        assertThat(cfg.port()).isEqualTo(6379);
        assertThat(cfg.az()).isEqualTo("unknown");
    }

    @Test
    void parse_hostOnly_defaultsPortAndAz() {
        ReplicaConfig cfg = ReplicaConfig.parse("myhost");
        assertThat(cfg.host()).isEqualTo("myhost");
        assertThat(cfg.port()).isEqualTo(6379);
        assertThat(cfg.az()).isEqualTo("unknown");
    }

    @Test
    void parse_trimsWhitespace() {
        ReplicaConfig cfg = ReplicaConfig.parse("  redis-d:6379@us-west-2a  ");
        assertThat(cfg.host()).isEqualTo("redis-d");
        assertThat(cfg.az()).isEqualTo("us-west-2a");
    }

    @Test
    void parse_emptySpec_throws() {
        assertThatThrownBy(() -> ReplicaConfig.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_nullSpec_throws() {
        assertThatThrownBy(() -> ReplicaConfig.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
