package com.recsys.infrastructure.store;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.redis.sharding.ShardTopologyStore;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Mock-based (no Docker, no Redis) tests for POST /shards/topology.
 * Runs in the normal (non-docker) suite — no @Tag("docker").
 */
class ShardedRecordServiceReshardTest {

    static final long WINDOW_MS = 86_400_000L;
    static final String VALID_TOKEN = "secret-token";

    // Snapshot stub: version=2, shardCount=4, vnodes=150, prevVersion=1, prevShardCount=2, prevExpiresAtMs=999999
    static final ShardTopologyStore.Snapshot STUB_SNAPSHOT =
            new ShardTopologyStore.Snapshot(2, 4, 150, 0L, 1, 2, 999_999L);

    // Shared mocks — set up inside configure() so they are visible to tests
    static ShardTopologyStore mockTopologyStore;
    static ShardedRecordStore mockRecordStore;

    // ── Server with reshard ENABLED (valid token + topologyStore) ────────────
    @RegisterExtension
    static final ServerExtension serverEnabled = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            mockTopologyStore = mock(ShardTopologyStore.class);
            when(mockTopologyStore.publishReshard(eq(4), anyLong(), eq(WINDOW_MS)))
                    .thenReturn(STUB_SNAPSHOT);
            mockRecordStore = mock(ShardedRecordStore.class);
            sb.service(Route.builder().pathPrefix("/shards/").build(),
                    new ShardedRecordService(mockRecordStore, mockTopologyStore,
                            VALID_TOKEN, WINDOW_MS, () -> 1_000_000L));
        }
    };

    // ── Case 1: correct token + valid shardCount → 200 with version/shardCount ──
    @Test
    void reshard_correctToken_returns200WithVersionAndShardCount() {
        reset(mockTopologyStore);
        when(mockTopologyStore.publishReshard(eq(4), anyLong(), eq(WINDOW_MS)))
                .thenReturn(STUB_SNAPSHOT);

        AggregatedHttpResponse r = serverEnabled.blockingWebClient()
                .prepare()
                .post("/shards/topology")
                .header("X-Admin-Token", VALID_TOKEN)
                .content("{\"shardCount\":4}")
                .execute();

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        String body = r.contentUtf8();
        assertThat(body).contains("\"version\":2");
        assertThat(body).contains("\"shardCount\":4");
        verify(mockTopologyStore, times(1))
                .publishReshard(eq(4), anyLong(), eq(WINDOW_MS));
    }

    // ── Case 2a: missing token → 403, publishReshard never called ────────────
    @Test
    void reshard_missingToken_returns403_publishNeverCalled() {
        reset(mockTopologyStore);

        AggregatedHttpResponse r = serverEnabled.blockingWebClient()
                .prepare()
                .post("/shards/topology")
                .content("{\"shardCount\":4}")
                .execute();

        assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(mockTopologyStore, never()).publishReshard(anyInt(), anyLong(), anyLong());
    }

    // ── Case 2b: incorrect token → 403, publishReshard never called ──────────
    @Test
    void reshard_incorrectToken_returns403_publishNeverCalled() {
        reset(mockTopologyStore);

        AggregatedHttpResponse r = serverEnabled.blockingWebClient()
                .prepare()
                .post("/shards/topology")
                .header("X-Admin-Token", "wrong-token")
                .content("{\"shardCount\":4}")
                .execute();

        assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(mockTopologyStore, never()).publishReshard(anyInt(), anyLong(), anyLong());
    }

    // ── Case 2c: wrong token of the SAME length → 403 (constant-time compare) ─────
    @Test
    void reshard_sameLengthWrongToken_returns403_publishNeverCalled() {
        reset(mockTopologyStore);
        // Same length as VALID_TOKEN ("secret-token", 12 chars) so the rejection cannot depend on a
        // length mismatch — exercises the constant-time MessageDigest.isEqual comparison.
        String sameLengthWrong = "xxxxxxxxxxxx";
        assertThat(sameLengthWrong.length()).isEqualTo(VALID_TOKEN.length());

        AggregatedHttpResponse r = serverEnabled.blockingWebClient()
                .prepare()
                .post("/shards/topology")
                .header("X-Admin-Token", sameLengthWrong)
                .content("{\"shardCount\":4}")
                .execute();

        assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(mockTopologyStore, never()).publishReshard(anyInt(), anyLong(), anyLong());
    }

    // ── Case 3a: reshard disabled — blank adminToken → 403 regardless of header ──
    @RegisterExtension
    static final ServerExtension serverBlankToken = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            ShardTopologyStore ts = mock(ShardTopologyStore.class);
            ShardedRecordStore rs = mock(ShardedRecordStore.class);
            sb.service(Route.builder().pathPrefix("/shards/").build(),
                    new ShardedRecordService(rs, ts, "", WINDOW_MS, () -> 1_000_000L));
        }
    };

    @Test
    void reshard_blankAdminToken_returns403_regardlessOfHeader() {
        AggregatedHttpResponse r = serverBlankToken.blockingWebClient()
                .prepare()
                .post("/shards/topology")
                .header("X-Admin-Token", VALID_TOKEN)
                .content("{\"shardCount\":4}")
                .execute();

        assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.contentUtf8()).contains("reshard disabled");
    }

    // ── Case 3b: reshard disabled — null topologyStore → 403 ─────────────────
    @RegisterExtension
    static final ServerExtension serverNullTopology = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            ShardedRecordStore rs = mock(ShardedRecordStore.class);
            sb.service(Route.builder().pathPrefix("/shards/").build(),
                    new ShardedRecordService(rs, (ShardTopologyStore) null,
                            VALID_TOKEN, WINDOW_MS, () -> 1_000_000L));
        }
    };

    @Test
    void reshard_nullTopologyStore_returns403() {
        AggregatedHttpResponse r = serverNullTopology.blockingWebClient()
                .prepare()
                .post("/shards/topology")
                .header("X-Admin-Token", VALID_TOKEN)
                .content("{\"shardCount\":4}")
                .execute();

        assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.contentUtf8()).contains("reshard disabled");
    }

    // ── Case 4a: body missing shardCount → 400, publishReshard never called ──
    @Test
    void reshard_missingShardCount_returns400_publishNeverCalled() {
        reset(mockTopologyStore);

        AggregatedHttpResponse r = serverEnabled.blockingWebClient()
                .prepare()
                .post("/shards/topology")
                .header("X-Admin-Token", VALID_TOKEN)
                .content("{}")
                .execute();

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(mockTopologyStore, never()).publishReshard(anyInt(), anyLong(), anyLong());
    }

    // ── Case 4b: shardCount == 0 → 400, publishReshard never called ──────────
    @Test
    void reshard_shardCountZero_returns400_publishNeverCalled() {
        reset(mockTopologyStore);

        AggregatedHttpResponse r = serverEnabled.blockingWebClient()
                .prepare()
                .post("/shards/topology")
                .header("X-Admin-Token", VALID_TOKEN)
                .content("{\"shardCount\":0}")
                .execute();

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(mockTopologyStore, never()).publishReshard(anyInt(), anyLong(), anyLong());
    }

    // ── Case 4c: shardCount < 0 → 400, publishReshard never called ───────────
    @Test
    void reshard_shardCountNegative_returns400_publishNeverCalled() {
        reset(mockTopologyStore);

        AggregatedHttpResponse r = serverEnabled.blockingWebClient()
                .prepare()
                .post("/shards/topology")
                .header("X-Admin-Token", VALID_TOKEN)
                .content("{\"shardCount\":-1}")
                .execute();

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(mockTopologyStore, never()).publishReshard(anyInt(), anyLong(), anyLong());
    }
}
