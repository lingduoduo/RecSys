package com.recsys.api.serving;

import com.recsys.infrastructure.redis.RedisEmbeddingStore;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Startup seeding must repair a <em>partially</em> populated Redis.
 *
 * <p>Redis runs with {@code allkeys-lru} at {@code maxmemory}, so seeded embeddings —
 * written with no TTL — can still be evicted. The old guard re-seeded only when Redis
 * scanned completely empty, so after a partial eviction Redis was non-empty and a
 * restart never restored the evicted subset.
 */
class EmbeddingSeedRepairTest {

    @Test
    void seedRepairsEachStorePerIdWithNoAllOrNothingEmptinessGate() {
        RedisEmbeddingStore items = mock(RedisEmbeddingStore.class);
        RedisEmbeddingStore users = mock(RedisEmbeddingStore.class);

        RecSysServer.seedEmbeddings(items, users);

        verify(items).writeMissing(anyMap(), eq(0L));
        verify(users).writeMissing(anyMap(), eq(0L));
        // Reinstating the "seed only when the store scans empty" gate must fail this test.
        verify(items, never()).scanIds(anyInt());
        verify(users, never()).scanIds(anyInt());
    }

    @Test
    void seedNeverBulkOverwritesAnAlreadyPopulatedStore() {
        RedisEmbeddingStore items = mock(RedisEmbeddingStore.class);
        RedisEmbeddingStore users = mock(RedisEmbeddingStore.class);

        RecSysServer.seedEmbeddings(items, users);

        // The repair writes only the absent subset; it must not re-issue the whole file.
        verify(items, never()).setEmbeddings(anyMap(), anyLong());
        verify(users, never()).setEmbeddings(anyMap(), anyLong());
    }
}
