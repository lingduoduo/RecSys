package com.recsys.streaming;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.redis.sharding.Page;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import com.recsys.model.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("load")
class OnlinePredictionLoadTest {

    static final OnlineRecommendationService mockRec = mock(OnlineRecommendationService.class);

    static {
        OnlineRecommendationResult result = new OnlineRecommendationResult(
                new User(1, "Alice"), "last_hour", "online",
                List.of(), List.of(), List.of());
        when(mockRec.recommend(any())).thenReturn(result);
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            OnlineServingMetricsService metrics = new OnlineServingMetricsService(60);
            // Tight concurrency limit (16) to exercise load shedding under burst.
            OnlineLoadShedder shedder = new OnlineLoadShedder(16, 0.90);
            ShardedRecordStore mockStore = mock(ShardedRecordStore.class);
            when(mockStore.readDevice(any(), any(), anyInt())).thenReturn(new Page<>(List.of(), null));

            sb.requestTimeoutMillis(500)
              .service("/online/recommendation", new OnlineAdmissionControl(
                      new OnlinePredictionService(mockRec, metrics, shedder,
                              RedisRateLimiter.disabled(), null, true),
                      shedder, metrics))
              .service("/online/ops", new OnlineOpsService(metrics, shedder, new OnlineCapacityService()))
              .service(Route.builder().pathPrefix("/shards/").build(),
                      new ShardedRecordService(mockStore));
        }
    };

    @Test
    void steadyState_200RPS_allSucceed() throws InterruptedException {
        int requests = 200;
        int threads = 8;
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger err = new AtomicInteger();
        AtomicLong totalMs = new AtomicLong();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(requests);

        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                try {
                    long start = System.currentTimeMillis();
                    AggregatedHttpResponse r = server.blockingWebClient()
                            .get("/online/recommendation?userId=1&k=5");
                    long elapsed = System.currentTimeMillis() - start;
                    totalMs.addAndGet(elapsed);
                    if (r.status().equals(HttpStatus.OK)) ok.incrementAndGet();
                    else err.incrementAndGet();
                } catch (Exception e) {
                    err.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        double avgMs = (double) totalMs.get() / requests;
        System.out.printf("[LOAD] steady-state: ok=%d err=%d avgMs=%.1f%n", ok.get(), err.get(), avgMs);

        assertThat(ok.get()).isEqualTo(requests);
        assertThat(err.get()).isZero();
        assertThat(avgMs).isLessThan(200.0);
    }

    @Test
    void burstOverload_shedsBeyondConcurrencyLimit() throws InterruptedException {
        int requests = 200;
        int threads = 64; // burst: far exceeds the 16-slot concurrency limit
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger shed = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(requests);

        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                try {
                    AggregatedHttpResponse r = server.blockingWebClient()
                            .get("/online/recommendation?userId=1&k=5");
                    if (r.status().equals(HttpStatus.OK)) ok.incrementAndGet();
                    else if (r.status().equals(HttpStatus.TOO_MANY_REQUESTS)) shed.incrementAndGet();
                    else other.incrementAndGet();
                } catch (Exception e) {
                    other.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        System.out.printf("[LOAD] burst: ok=%d shed(429)=%d other=%d%n",
                ok.get(), shed.get(), other.get());

        assertThat(ok.get()).isGreaterThan(0);
        assertThat(shed.get()).isGreaterThan(0);
        assertThat(other.get()).isZero();
    }

    @Test
    void ops_endpoint_reflectsLoadAfterBurst() throws InterruptedException {
        int requests = 50;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(requests);

        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                try {
                    server.blockingWebClient().get("/online/recommendation?userId=1&k=5");
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        AggregatedHttpResponse ops = server.blockingWebClient().get("/online/ops");
        assertThat(ops.status()).isEqualTo(HttpStatus.OK);
        String body = ops.contentUtf8();
        System.out.println("[LOAD] ops snapshot: " + body);

        assertThat(body).contains("\"totalRequests\"");
        assertThat(body).contains("\"qps\"");
        assertThat(body).contains("\"inFlightRequests\"");
    }
}
