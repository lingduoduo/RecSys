package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
class SequenceGeneratorTest extends RedisShardingTestBase {

    @Test
    void next_returnsMonotonicallyIncreasingValues() {
        var gen = new SequenceGenerator(pool, "sr:");
        long s1 = gen.next(0);
        long s2 = gen.next(0);
        long s3 = gen.next(0);
        assertThat(s1).isLessThan(s2).isLessThan(s3);
        assertThat(s1).isEqualTo(1L);
    }

    @Test
    void next_differentShardsHaveIndependentCounters() {
        var gen = new SequenceGenerator(pool, "sr:");
        assertThat(gen.next(0)).isEqualTo(1L);
        assertThat(gen.next(1)).isEqualTo(1L);
        assertThat(gen.next(0)).isEqualTo(2L);
    }

    @Test
    void next_concurrentWritersProduceNoDuplicates() throws InterruptedException {
        var gen = new SequenceGenerator(pool, "sr:");
        int threads = 10, callsPerThread = 100;
        Set<Long> seqNums = new ConcurrentSkipListSet<>();
        CountDownLatch latch = new CountDownLatch(threads);

        ExecutorService ex = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            ex.submit(() -> {
                for (int i = 0; i < callsPerThread; i++) seqNums.add(gen.next(0));
                latch.countDown();
            });
        }
        latch.await();
        ex.shutdown();

        assertThat(seqNums).hasSize(threads * callsPerThread);
    }

    @Test
    void ensureCounterValid_resetsCounterWhenBehindMaxZSetScore() {
        var gen = new SequenceGenerator(pool, "sr:");
        // Manually write a ZSet member with score=100 simulating records after a flush.
        try (Jedis jedis = pool.getResource()) {
            jedis.zadd("sr:dev:0:device-1", 100.0, "event-1");
        }
        // Counter is at 0 (never incremented after flush) — should be reset.
        gen.ensureCounterValid(0, 1); // shardIndex=0, shardCount=1

        long next = gen.next(0);
        assertThat(next).isGreaterThan(100L);
    }
}
