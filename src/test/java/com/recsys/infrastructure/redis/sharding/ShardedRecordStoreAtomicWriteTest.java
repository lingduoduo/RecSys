package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The write path issues exactly one Redis command — the script — and derives its status
 * from the script's return value.
 */
class ShardedRecordStoreAtomicWriteTest {

    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> commands = mock(RedisCommands.class);
    private final RedisExecutor exec = mock(RedisExecutor.class);

    @SuppressWarnings("unchecked")
    private ShardedRecordStore storeReturning(long seq, long zadd, int keyFormat) {
        when(exec.execute(any())).thenAnswer(invocation -> {
            Function<RedisCommands<String, String>, Object> fn = invocation.getArgument(0);
            return fn.apply(commands);
        });
        when(commands.eval(anyString(), eq(ScriptOutputType.MULTI), any(String[].class),
                any(String[].class))).thenReturn(List.of(seq, zadd));
        ShardTopologyProvider provider = ShardTopologyProvider.fixedAtVersion(2, 2, 150, keyFormat);
        return new ShardedRecordStore(exec, exec, provider, new SequenceGenerator(exec, "sr:"), "sr:");
    }

    private static ShardedRecord record() {
        return new ShardedRecord("dev-1", 0L, RecordType.EVENT, "evt-1", "{}", 1234L);
    }

    @Test
    void insertReturnsOkAndTheScriptAssignedSequence() {
        ShardedRecordStore store = storeReturning(42L, 1L, ShardKeys.FORMAT_TAGGED);

        WriteResult result = store.write(record());

        assertThat(result.seqNum()).isEqualTo(42L);
        assertThat(result.status()).isEqualTo(WriteStatus.OK);
    }

    @Test
    void insertWithZeroZaddIsADuplicate() {
        ShardedRecordStore store = storeReturning(42L, 0L, ShardKeys.FORMAT_TAGGED);

        assertThat(store.write(record()).status()).isEqualTo(WriteStatus.DUPLICATE);
    }

    @Test
    void updateWithZeroZaddIsNotADuplicate() {
        // ZADD XX GT returns 0 when the element exists but its score did not advance. That is
        // an ordinary non-advancing update, not a duplicate, and must stay OK.
        ShardedRecordStore store = storeReturning(42L, 0L, ShardKeys.FORMAT_TAGGED);

        assertThat(store.update(record()).status()).isEqualTo(WriteStatus.OK);
    }

    @Test
    void theScriptReceivesTaggedKeysAndTheRecordKeyPrefix() {
        ShardedRecordStore store = storeReturning(42L, 1L, ShardKeys.FORMAT_TAGGED);
        int shard = new ConsistentHashRing(2, 150).shardFor("dev-1");

        store.write(record());

        verify(commands).eval(anyString(), eq(ScriptOutputType.MULTI),
                eq(new String[]{
                        "sr:g2:seq:{" + shard + "}",
                        "sr:g2:dev:{" + shard + "}:dev-1",
                        "sr:g2:stream:{" + shard + "}"}),
                any(String[].class));
    }

    @Test
    void anUntaggedGenerationStillUsesUntaggedKeys() {
        ShardedRecordStore store = storeReturning(42L, 1L, ShardKeys.FORMAT_UNTAGGED);
        int shard = new ConsistentHashRing(2, 150).shardFor("dev-1");

        store.write(record());

        verify(commands).eval(anyString(), eq(ScriptOutputType.MULTI),
                eq(new String[]{
                        "sr:g2:seq:" + shard,
                        "sr:g2:dev:" + shard + ":dev-1",
                        "sr:g2:stream:" + shard}),
                any(String[].class));
    }
}
