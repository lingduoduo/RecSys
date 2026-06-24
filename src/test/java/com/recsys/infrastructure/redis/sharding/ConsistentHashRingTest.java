package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsistentHashRingTest {

    @Test
    void singleShard_allDevicesMapToShardZero() {
        var ring = new ConsistentHashRing(1, 150);
        assertThat(ring.shardFor("device-1")).isZero();
        assertThat(ring.shardFor("device-abc")).isZero();
        assertThat(ring.shardFor("user:999")).isZero();
    }

    @Test
    void multipleShards_distributionIsUniformWithin20Percent() {
        var ring = new ConsistentHashRing(4, 250);
        int deviceCount = 10_000;
        List<String> devices = IntStream.range(0, deviceCount)
                .mapToObj(i -> "device-" + i)
                .toList();

        Map<Integer, Integer> dist = ring.distribution(devices);

        int expected = deviceCount / 4;
        for (int count : dist.values()) {
            assertThat(count).isBetween((int)(expected * 0.8), (int)(expected * 1.2));
        }
    }

    @Test
    void shardFor_isDeterministicAcrossCallsAndInstances() {
        var ring1 = new ConsistentHashRing(4, 150);
        var ring2 = new ConsistentHashRing(4, 150);
        String deviceId = "user:12345";

        int shard1 = ring1.shardFor(deviceId);
        int shard2 = ring2.shardFor(deviceId);
        int shard3 = ring1.shardFor(deviceId);

        assertThat(shard1).isEqualTo(shard2).isEqualTo(shard3);
    }

    @Test
    void shardFor_returnsValueInRange() {
        var ring = new ConsistentHashRing(8, 150);
        for (int i = 0; i < 1000; i++) {
            int shard = ring.shardFor("device-" + i);
            assertThat(shard).isBetween(0, 7);
        }
    }

    @Test
    void invalidShardCount_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new ConsistentHashRing(0, 150))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidVirtualNodeCount_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new ConsistentHashRing(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shardCount_returnsConfiguredValue() {
        assertThat(new ConsistentHashRing(3, 150).shardCount()).isEqualTo(3);
    }

    @Test
    void shardFor_matchesGoldenAssignmentsAcrossShardCounts() {
        assertThatAssignments(2, Map.of(
                "device-1", 0,
                "device-123", 0,
                "device-abc", 0,
                "user:999", 1,
                "user:12345", 1));

        assertThatAssignments(4, Map.of(
                "device-1", 0,
                "device-123", 0,
                "device-abc", 0,
                "user:999", 1,
                "user:12345", 1));

        assertThatAssignments(8, Map.of(
                "device-1", 4,
                "device-123", 4,
                "device-abc", 0,
                "user:999", 1,
                "user:12345", 5));
    }

    private static void assertThatAssignments(int shardCount, Map<String, Integer> expected) {
        var ring = new ConsistentHashRing(shardCount, 150);
        expected.forEach((deviceId, shard) -> assertThat(ring.shardFor(deviceId)).isEqualTo(shard));
    }
}
