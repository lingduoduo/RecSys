package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisReadReplicaRouterTest {

    private static RedisExecutor mockExec() {
        return mock(RedisExecutor.class);
    }

    @Test
    void writable_alwaysReturnsPrimary() {
        RedisExecutor primary = mockExec();
        RedisExecutor replica = mockExec();
        try (var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replica, "us-east-1b")),
                "us-east-1a")) {
            assertThat(router.writable()).isSameAs(primary);
        }
    }

    @Test
    void readable_returnsPrimaryWhenNoReplicas() {
        RedisExecutor primary = mockExec();
        try (var router = new RedisReadReplicaRouter(primary, List.of(), "us-east-1a")) {
            assertThat(router.readable()).isSameAs(primary);
        }
    }

    @Test
    void readable_prefersSameAzReplica() {
        RedisExecutor primary  = mockExec();
        RedisExecutor replicaB = mockExec();
        RedisExecutor replicaC = mockExec();

        try (var router = new RedisReadReplicaRouter(primary, List.of(
                new RedisReadReplicaRouter.AzExecutor(replicaB, "us-east-1b"),
                new RedisReadReplicaRouter.AzExecutor(replicaC, "us-east-1c")
        ), "us-east-1b")) {
            assertThat(router.readable()).isSameAs(replicaB);
        }
    }

    @Test
    void readable_returnsAReplicaWhenNoSameAzMatch() {
        RedisExecutor primary  = mockExec();
        RedisExecutor replicaB = mockExec();
        RedisExecutor replicaC = mockExec();

        try (var router = new RedisReadReplicaRouter(primary, List.of(
                new RedisReadReplicaRouter.AzExecutor(replicaB, "us-east-1b"),
                new RedisReadReplicaRouter.AzExecutor(replicaC, "us-east-1c")
        ), "us-east-1a")) {
            RedisExecutor selected = router.readable();
            assertThat(selected).isIn(replicaB, replicaC);
            assertThat(selected).isNotSameAs(primary);
        }
    }

    @Test
    void readable_withNullLocalAz_returnsAnyReplica() {
        RedisExecutor primary  = mockExec();
        RedisExecutor replicaB = mockExec();

        try (var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replicaB, "us-east-1b")),
                null)) {
            assertThat(router.readable()).isSameAs(replicaB);
        }
    }

    @Test
    void replicaCount_reflectsConfiguredReplicas() {
        RedisExecutor primary  = mockExec();
        RedisExecutor replicaB = mockExec();
        RedisExecutor replicaC = mockExec();

        try (var router = new RedisReadReplicaRouter(primary, List.of(
                new RedisReadReplicaRouter.AzExecutor(replicaB, "us-east-1b"),
                new RedisReadReplicaRouter.AzExecutor(replicaC, "us-east-1c")
        ), "us-east-1a")) {
            assertThat(router.replicaCount()).isEqualTo(2);
        }
    }

    @Test
    void replicaCount_zeroWhenNoReplicas() {
        try (var router = new RedisReadReplicaRouter(mockExec(), List.of(), "us-east-1a")) {
            assertThat(router.replicaCount()).isZero();
        }
    }

    @Test
    void localAz_isPreserved() {
        try (var router = new RedisReadReplicaRouter(mockExec(), List.of(), "us-west-2a")) {
            assertThat(router.localAz()).isEqualTo("us-west-2a");
        }
    }

    @Test
    void localAz_defaultsToUnknownWhenNull() {
        try (var router = new RedisReadReplicaRouter(mockExec(), List.of(), null)) {
            assertThat(router.localAz()).isEqualTo("unknown");
        }
    }
}
