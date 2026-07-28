package com.recsys.infrastructure.store;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.redis.sharding.Page;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mock-based (no Docker, no Redis) tests for cursor handling on the /shards read endpoints.
 *
 * <p>Both handlers accept an opaque {@code cursor} query parameter, and the two endpoints
 * page different spaces — {@code /shards/device} by ZSet score, {@code /shards/shard} by
 * Redis stream ID. Handing a cursor from one to the other used to reach Redis and return
 * 500. These pin it at 400.
 */
class ShardedRecordServiceCursorTest {

    static final String ADMIN_TOKEN = "secret-token";
    static ShardedRecordStore mockStore;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            mockStore = mock(ShardedRecordStore.class);
            when(mockStore.readDevice(anyString(), any(), anyInt())).thenReturn(Page.empty());
            when(mockStore.readShard(anyInt(), any(), anyInt())).thenReturn(Page.empty());
            sb.service(Route.builder().pathPrefix("/shards/").build(),
                    new ShardedRecordService(mockStore, null, ADMIN_TOKEN, 0L, () -> 0L));
        }
    };

    /** The mock is built once in configure() and shared, so verify() would otherwise
     *  count invocations from sibling tests. */
    @BeforeEach
    void resetStore() {
        reset(mockStore);
        when(mockStore.readDevice(anyString(), any(), anyInt())).thenReturn(Page.empty());
        when(mockStore.readShard(anyInt(), any(), anyInt())).thenReturn(Page.empty());
    }

    private AggregatedHttpResponse get(String path) {
        return server.blockingWebClient().prepare().get(path)
                .header("X-Admin-Token", ADMIN_TOKEN).execute();
    }

    @Test
    void deviceRead_rejectsAStreamIdCursorWith400() {
        AggregatedHttpResponse r = get("/shards/device?deviceId=d1&cursor=1690000000000-0");

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.contentUtf8()).contains("sequence number");
        // The bad cursor never reaches the store, so Double.parseDouble never runs.
        verify(mockStore, never()).readDevice(anyString(), any(), anyInt());
    }

    @Test
    void shardRead_rejectsASequenceNumberCursorWith400() {
        AggregatedHttpResponse r = get("/shards/shard?index=0&cursor=42");

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.contentUtf8()).contains("stream ID");
        verify(mockStore, never()).readShard(anyInt(), any(), anyInt());
    }

    @Test
    void bothReads_rejectAMalformedCursorWith400() {
        assertThat(get("/shards/device?deviceId=d1&cursor=not-a-cursor").status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/shards/shard?index=0&cursor=not-a-cursor").status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void bothReads_acceptTheirOwnCursorSpace() {
        assertThat(get("/shards/device?deviceId=d1&cursor=42").status()).isEqualTo(HttpStatus.OK);
        assertThat(get("/shards/shard?index=0&cursor=1690000000000-0").status())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void bothReads_acceptAnAbsentCursor() {
        assertThat(get("/shards/device?deviceId=d1").status()).isEqualTo(HttpStatus.OK);
        assertThat(get("/shards/shard?index=0").status()).isEqualTo(HttpStatus.OK);
    }
}
