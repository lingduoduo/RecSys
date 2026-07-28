package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ShardCursor} serves two mutually incompatible cursor spaces: a ZSET score for
 * per-device reads, and a Redis stream ID for shard reads. Both HTTP handlers accept an
 * opaque cursor string, so a cursor from one endpoint could be fed to the other — which
 * used to surface as a 500 (a {@code NumberFormatException} or a Redis stream-ID error).
 *
 * <p>The kind is derived at construction so the confusion is caught before it reaches Redis.
 */
class ShardCursorKindTest {

    @Test
    void startIsItsOwnKind_andIsAcceptedByBothReadPaths() {
        ShardCursor start = ShardCursor.start();
        assertThat(start.kind()).isEqualTo(ShardCursor.Kind.START);
        assertThat(start.isStart()).isTrue();
        // Neither assertion throws: START is valid wherever a cursor is accepted.
        start.requireKind(ShardCursor.Kind.SEQ);
        start.requireKind(ShardCursor.Kind.STREAM);
    }

    @Test
    void aBareNumberIsASeqCursor() {
        ShardCursor cursor = ShardCursor.of("42");
        assertThat(cursor.kind()).isEqualTo(ShardCursor.Kind.SEQ);
        assertThat(cursor.value()).isEqualTo("42");
        cursor.requireKind(ShardCursor.Kind.SEQ);
    }

    @Test
    void aHyphenatedIdIsAStreamCursor() {
        ShardCursor cursor = ShardCursor.of("1690000000000-0");
        assertThat(cursor.kind()).isEqualTo(ShardCursor.Kind.STREAM);
        assertThat(cursor.value()).isEqualTo("1690000000000-0");
        cursor.requireKind(ShardCursor.Kind.STREAM);
    }

    @Test
    void feedingADeviceCursorToTheShardPathIsRejected() {
        // "42" comes from GET /shards/device; XREAD would reject it as a stream ID.
        assertThatThrownBy(() -> ShardCursor.of("42").requireKind(ShardCursor.Kind.STREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stream");
    }

    @Test
    void feedingAShardCursorToTheDevicePathIsRejected() {
        // "1690000000000-0" comes from GET /shards/shard; Double.parseDouble would throw.
        assertThatThrownBy(() -> ShardCursor.of("1690000000000-0").requireKind(ShardCursor.Kind.SEQ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
    }

    @Test
    void aValueInNeitherSpaceIsRejectedAtConstruction() {
        assertThatThrownBy(() -> ShardCursor.of("not-a-cursor"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardCursor.of(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void explicitFactoriesCarryTheirKind() {
        assertThat(ShardCursor.seq(42L).kind()).isEqualTo(ShardCursor.Kind.SEQ);
        assertThat(ShardCursor.seq(42L).value()).isEqualTo("42");
        assertThat(ShardCursor.stream("1690000000000-0").kind()).isEqualTo(ShardCursor.Kind.STREAM);
    }

    @Test
    void theWireFormatIsUnchanged_soInFlightCursorsKeepWorking() {
        // Round-tripping the exact strings the two read paths emit must preserve the kind,
        // with no added prefix or encoding.
        assertThat(ShardCursor.of(ShardCursor.seq(7L).value()).kind()).isEqualTo(ShardCursor.Kind.SEQ);
        assertThat(ShardCursor.of(ShardCursor.stream("5-1").value()).kind())
                .isEqualTo(ShardCursor.Kind.STREAM);
    }
}
