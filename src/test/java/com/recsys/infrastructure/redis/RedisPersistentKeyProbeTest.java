package com.recsys.infrastructure.redis;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisPersistentKeyProbeTest {

    /** A fake Redis holding key -> ttl seconds (-1 = no expiry), served as one SCAN page. */
    @SuppressWarnings("unchecked")
    private static RedisExecutor execWith(Map<String, Long> keyspace, String nextCursor) {
        RedisCommands<String, String> cmd = mock(RedisCommands.class);
        KeyScanCursor<String> page = mock(KeyScanCursor.class);
        when(page.getKeys()).thenReturn(List.copyOf(keyspace.keySet()));
        when(page.getCursor()).thenReturn(nextCursor);
        when(page.isFinished()).thenReturn("0".equals(nextCursor));
        when(cmd.scan(any(ScanCursor.class), any(ScanArgs.class))).thenReturn(page);
        keyspace.forEach((key, ttl) -> when(cmd.ttl(key)).thenReturn(ttl));

        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.executeRead(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        return exec;
    }

    @Test
    void flagsAKeyWithNoTtlThatIsNotDeclaredDurable() {
        RedisExecutor exec = execWith(Map.of("recsys:replica-lag-probe:abc", -1L), "0");

        RedisPersistentKeyProbe.KeyspaceSample sample = new RedisPersistentKeyProbe(exec).sample();

        assertThat(sample.available()).isTrue();
        assertThat(sample.scanned()).isEqualTo(1);
        assertThat(sample.unexpected()).isEqualTo(1);
        assertThat(sample.examples()).containsExactly("recsys:replica-lag-probe:abc");
    }

    @Test
    void doesNotFlagDeclaredDurableKeys() {
        RedisExecutor exec = execWith(Map.of(
                "shard:topology", -1L,
                "i2vEmb:42", -1L,
                "u2vEmb:7", -1L,
                "sr:seq:3", -1L,
                "bias:item:99", -1L), "0");

        RedisPersistentKeyProbe.KeyspaceSample sample = new RedisPersistentKeyProbe(exec).sample();

        assertThat(sample.scanned()).isEqualTo(5);
        assertThat(sample.unexpected()).isZero();
        assertThat(sample.examples()).isEmpty();
    }

    @Test
    void doesNotFlagKeysThatCarryATtl() {
        RedisExecutor exec = execWith(Map.of("filler:1", 300L, "svc:registry:online", 30L), "0");

        assertThat(new RedisPersistentKeyProbe(exec).sample().unexpected()).isZero();
    }

    @Test
    void carriesTheCursorAcrossSamplesAndWrapsWhenTheScanFinishes() {
        RedisExecutor exec = execWith(Map.of("leaky:1", -1L), "512");
        RedisPersistentKeyProbe probe = new RedisPersistentKeyProbe(exec);

        probe.sample();
        assertThat(probe.cursorPosition()).isEqualTo("512");

        RedisExecutor finishing = execWith(Map.of("leaky:2", -1L), "0");
        RedisPersistentKeyProbe wrapping = new RedisPersistentKeyProbe(finishing);
        wrapping.sample();
        assertThat(wrapping.cursorPosition())
                .as("a finished scan restarts, so sampling keeps covering the keyspace")
                .isEqualTo("0");
    }

    @Test
    void boundsHowManyOffendingKeysItReports() {
        RedisExecutor exec = execWith(Map.of(
                "leak:1", -1L, "leak:2", -1L, "leak:3", -1L, "leak:4", -1L, "leak:5", -1L), "0");

        RedisPersistentKeyProbe.KeyspaceSample sample = new RedisPersistentKeyProbe(exec).sample();

        assertThat(sample.unexpected()).isEqualTo(5);
        assertThat(sample.examples()).hasSize(3);
    }

    @Test
    void reportsUnavailableWithoutPropagatingWhenRedisFails() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.executeRead(any())).thenThrow(new IllegalStateException("redis down"));

        assertThat(new RedisPersistentKeyProbe(exec).sample().available()).isFalse();
    }
}
