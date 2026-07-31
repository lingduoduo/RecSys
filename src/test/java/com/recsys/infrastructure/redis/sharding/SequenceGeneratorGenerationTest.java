package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Non-docker unit tests for the generation-aware sequence-counter guard.
 *
 * <p>{@code SequenceGeneratorTest} is {@code @Tag("docker")} and the resilience profile —
 * the PR gate — sets {@code <excludedGroups>load,docker</excludedGroups>}, so behaviour that
 * must block a merge is asserted here against a mocked executor instead.
 */
class SequenceGeneratorGenerationTest {

    @SuppressWarnings("unchecked")
    private RedisCommands<String, String> wire(RedisExecutor exec) {
        RedisCommands<String, String> cmd = mock(RedisCommands.class);
        when(exec.execute(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        return cmd;
    }

    /**
     * Builds a single-page cursor. Note the call sites assign this to a local <em>before</em>
     * passing it to {@code thenReturn}: constructing it inline would run these {@code when()}
     * calls in the middle of the outer stubbing chain, which Mockito rejects as
     * {@code UnfinishedStubbing}.
     */
    @SuppressWarnings("unchecked")
    private KeyScanCursor<String> finishedCursor(List<String> keys) {
        KeyScanCursor<String> cursor = mock(KeyScanCursor.class);
        when(cursor.getKeys()).thenReturn(keys);
        when(cursor.isFinished()).thenReturn(true);
        return cursor;
    }

    @Test
    void generation2_scansAndWritesGenerationPrefixedKeys() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);

        KeyScanCursor<String> page = finishedCursor(List.of("sr:g2:dev:0:device-1"));
        when(cmd.scan(any(ScanArgs.class))).thenReturn(page);
        when(cmd.zrevrangebyscoreWithScores(eq("sr:g2:dev:0:device-1"), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(ScoredValue.just(100.0, "event-1")));
        when(cmd.get("sr:g2:seq:0")).thenReturn("5");

        boolean completed = new SequenceGenerator(exec, "sr:")
                .ensureCounterValid(new ShardTopology(2, 1, 150, 0L), 0, 30_000L);

        assertThat(completed).isTrue();
        // Counter was behind the max score, so it is raised past it — on the g2 key.
        verify(cmd).set("sr:g2:seq:0", "101");
        verify(cmd, never()).set(eq("sr:seq:0"), anyString());
    }

    @Test
    void generation1_keepsTheUnversionedKeyspace() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);

        KeyScanCursor<String> page = finishedCursor(List.of("sr:dev:0:device-1"));
        when(cmd.scan(any(ScanArgs.class))).thenReturn(page);
        when(cmd.zrevrangebyscoreWithScores(eq("sr:dev:0:device-1"), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(ScoredValue.just(100.0, "event-1")));
        when(cmd.get("sr:seq:0")).thenReturn("5");

        new SequenceGenerator(exec, "sr:")
                .ensureCounterValid(new ShardTopology(1, 1, 150, 0L), 0, 30_000L);

        verify(cmd).set("sr:seq:0", "101");
    }

    @Test
    void counterAlreadyAheadIsLeftAlone() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);

        KeyScanCursor<String> page = finishedCursor(List.of("sr:dev:0:device-1"));
        when(cmd.scan(any(ScanArgs.class))).thenReturn(page);
        when(cmd.zrevrangebyscoreWithScores(anyString(), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(ScoredValue.just(100.0, "event-1")));
        when(cmd.get("sr:seq:0")).thenReturn("500");

        new SequenceGenerator(exec, "sr:")
                .ensureCounterValid(new ShardTopology(1, 1, 150, 0L), 0, 30_000L);

        // The guard only ever raises the counter, never lowers it.
        verify(cmd, never()).set(anyString(), anyString());
    }

    @Test
    void emptyShardIsANoOp() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);
        KeyScanCursor<String> page = finishedCursor(List.of());
        when(cmd.scan(any(ScanArgs.class))).thenReturn(page);

        assertThat(new SequenceGenerator(exec, "sr:")
                .ensureCounterValid(new ShardTopology(1, 1, 150, 0L), 0, 30_000L)).isTrue();

        verify(cmd, never()).get(anyString());
        verify(cmd, never()).set(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exhaustedBudgetTruncatesTheScanAndReportsIncomplete() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);

        // A cursor that never finishes: only the budget can stop the loop.
        KeyScanCursor<String> endless = mock(KeyScanCursor.class);
        when(endless.getKeys()).thenReturn(List.of("sr:dev:0:device-1"));
        when(endless.isFinished()).thenReturn(false);
        when(cmd.scan(any(ScanArgs.class))).thenReturn(endless);
        when(cmd.scan(any(ScanCursor.class), any(ScanArgs.class))).thenReturn(endless);
        when(cmd.zrevrangebyscoreWithScores(anyString(), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(ScoredValue.just(100.0, "event-1")));
        when(cmd.get("sr:seq:0")).thenReturn("5");

        // A clock advancing 10ms per read exhausts a 50ms budget in a handful of pages.
        AtomicLong ticks = new AtomicLong();
        SequenceGenerator gen = new SequenceGenerator(exec, "sr:", () -> ticks.addAndGet(10L));

        assertThat(gen.ensureCounterValid(new ShardTopology(1, 1, 150, 0L), 0, 50L)).isFalse();

        // A truncated scan still repairs with what it found: under-estimating maxSeq is safe,
        // because the guard only raises the counter.
        verify(cmd).set("sr:seq:0", "101");
    }

    @Test
    void taggedGenerationIncrementsTheTaggedCounterKey() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);
        when(cmd.incr(anyString())).thenReturn(1L);

        new SequenceGenerator(exec, "sr:")
                .next(new ShardTopology(3, 2, 150, 0L, ShardKeys.FORMAT_TAGGED), 1);

        // The counter INCR must target the tagged key, or it will not share a Cluster slot
        // with the record and index keys the write script touches.
        verify(cmd).incr("sr:g3:seq:{1}");
    }

    @Test
    void taggedGenerationReadsAndRepairsTheTaggedCounterKey() {
        // ensureCounterValid GETs the counter before deciding whether to raise it. Asserting
        // the key it reads proves the repair path follows the generation's format too.
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);

        KeyScanCursor<String> page = finishedCursor(List.of("sr:g3:dev:{1}:dev-1"));
        when(cmd.scan(any(ScanArgs.class))).thenReturn(page);
        when(cmd.zrevrangebyscoreWithScores(anyString(), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(ScoredValue.just(9.0, "evt-9")));
        when(cmd.get(anyString())).thenReturn("3");

        new SequenceGenerator(exec, "sr:")
                .ensureCounterValid(new ShardTopology(3, 2, 150, 0L, ShardKeys.FORMAT_TAGGED),
                        1, 30_000L);

        verify(cmd).get("sr:g3:seq:{1}");
        verify(cmd).set("sr:g3:seq:{1}", "10");
    }
}
