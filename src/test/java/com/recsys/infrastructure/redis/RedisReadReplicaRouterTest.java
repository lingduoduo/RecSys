package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisReadReplicaRouterTest {

    @SuppressWarnings("unchecked")
    private static Pool<Jedis> mockPool() {
        return mock(Pool.class);
    }

    @Test
    void writablePool_alwaysReturnsPrimary() {
        Pool<Jedis> primary = mockPool();
        Pool<Jedis> replica = mockPool();
        try (var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzPool(replica, "us-east-1b")),
                "us-east-1a")) {
            assertThat(router.writablePool()).isSameAs(primary);
        }
    }

    @Test
    void readablePool_returnsPrimaryWhenNoReplicas() {
        Pool<Jedis> primary = mockPool();
        try (var router = new RedisReadReplicaRouter(primary, List.of(), "us-east-1a")) {
            assertThat(router.readablePool()).isSameAs(primary);
        }
    }

    @Test
    void readablePool_prefersSameAzReplica() {
        Pool<Jedis> primary  = mockPool();
        Pool<Jedis> replicaB = mockPool();
        Pool<Jedis> replicaC = mockPool();

        try (var router = new RedisReadReplicaRouter(primary, List.of(
                new RedisReadReplicaRouter.AzPool(replicaB, "us-east-1b"),
                new RedisReadReplicaRouter.AzPool(replicaC, "us-east-1c")
        ), "us-east-1b")) {
            assertThat(router.readablePool()).isSameAs(replicaB);
        }
    }

    @Test
    void readablePool_returnsAReplicaWhenNoSameAzMatch() {
        Pool<Jedis> primary  = mockPool();
        Pool<Jedis> replicaB = mockPool();
        Pool<Jedis> replicaC = mockPool();

        try (var router = new RedisReadReplicaRouter(primary, List.of(
                new RedisReadReplicaRouter.AzPool(replicaB, "us-east-1b"),
                new RedisReadReplicaRouter.AzPool(replicaC, "us-east-1c")
        ), "us-east-1a")) {
            Pool<Jedis> selected = router.readablePool();
            assertThat(selected).isIn(replicaB, replicaC);
            assertThat(selected).isNotSameAs(primary);
        }
    }

    @Test
    void readablePool_withNullLocalAz_returnsAnyReplica() {
        Pool<Jedis> primary  = mockPool();
        Pool<Jedis> replicaB = mockPool();

        try (var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzPool(replicaB, "us-east-1b")),
                null)) {
            assertThat(router.readablePool()).isSameAs(replicaB);
        }
    }

    @Test
    void replicaCount_reflectsConfiguredReplicas() {
        Pool<Jedis> primary  = mockPool();
        Pool<Jedis> replicaB = mockPool();
        Pool<Jedis> replicaC = mockPool();

        try (var router = new RedisReadReplicaRouter(primary, List.of(
                new RedisReadReplicaRouter.AzPool(replicaB, "us-east-1b"),
                new RedisReadReplicaRouter.AzPool(replicaC, "us-east-1c")
        ), "us-east-1a")) {
            assertThat(router.replicaCount()).isEqualTo(2);
        }
    }

    @Test
    void replicaCount_zeroWhenNoReplicas() {
        try (var router = new RedisReadReplicaRouter(mockPool(), List.of(), "us-east-1a")) {
            assertThat(router.replicaCount()).isZero();
        }
    }

    @Test
    void localAz_isPreserved() {
        try (var router = new RedisReadReplicaRouter(mockPool(), List.of(), "us-west-2a")) {
            assertThat(router.localAz()).isEqualTo("us-west-2a");
        }
    }

    @Test
    void localAz_defaultsToUnknownWhenNull() {
        try (var router = new RedisReadReplicaRouter(mockPool(), List.of(), null)) {
            assertThat(router.localAz()).isEqualTo("unknown");
        }
    }
}
