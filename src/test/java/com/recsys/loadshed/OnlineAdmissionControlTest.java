package com.recsys.loadshed;
import com.recsys.metrics.OnlineServingMetricsService;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineAdmissionControlTest {
    private static final OnlineLoadShedder SHEDDER = new OnlineLoadShedder(1, 0.75);
    private static final OnlineServingMetricsService METRICS = new OnlineServingMetricsService();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/controlled", new OnlineAdmissionControl(
                    (ctx, req) -> HttpResponse.delayed(HttpResponse.of(HttpStatus.OK),
                            java.time.Duration.ofMillis(100)),
                    SHEDDER, METRICS));
        }
    };

    @Test
    void rejectsBeforeDelegateWhenPermitUnavailableAndReleasesOnCompletion() {
        var first = server.webClient().get("/controlled").aggregate();

        var rejected = server.blockingWebClient().get("/controlled");
        assertThat(rejected.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.headers().get("retry-after")).isEqualTo("1");
        assertThat(rejected.contentUtf8()).contains("concurrency_limit");

        assertThat(first.join().status()).isEqualTo(HttpStatus.OK);
        assertInFlightEventually(0, Duration.ofSeconds(1));
    }

    private void assertInFlightEventually(int expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int actual;
        do {
            actual = SHEDDER.snapshot().inFlightRequests();
            if (actual == expected) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (System.nanoTime() < deadline);

        assertThat(actual).isEqualTo(expected);
    }

    private static ServiceRequestContext ctx() {
        return ServiceRequestContext.of(HttpRequest.of(com.linecorp.armeria.common.HttpMethod.GET, "/x"));
    }

    @Test
    void rejectsWithRetryAfterAndRunsCallbackWhenAtCapacity() throws Exception {
        OnlineLoadShedder shedder = new OnlineLoadShedder(1, 0.9);
        AtomicInteger rejects = new AtomicInteger();
        // Occupy the one slot so the next request is rejected.
        assertThat(shedder.tryAcquire()).isTrue();

        HttpService delegate = (c, r) -> HttpResponse.of(HttpStatus.OK);
        OnlineAdmissionControl gate = new OnlineAdmissionControl(delegate, shedder, rejects::incrementAndGet);

        AggregatedHttpResponse resp = gate.serve(ctx(), HttpRequest.of(
                com.linecorp.armeria.common.HttpMethod.GET, "/x")).aggregate().join();

        assertThat(resp.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resp.headers().get(com.linecorp.armeria.common.HttpHeaderNames.RETRY_AFTER)).isNotNull();
        assertThat(rejects.get()).isEqualTo(1);
    }

    @Test
    void admitsAndReleasesWhenUnderCapacity() throws Exception {
        OnlineLoadShedder shedder = new OnlineLoadShedder(2, 0.9);
        HttpService delegate = (c, r) -> HttpResponse.of(HttpStatus.OK);
        OnlineAdmissionControl gate = new OnlineAdmissionControl(delegate, shedder, () -> {});

        AggregatedHttpResponse resp = gate.serve(ctx(), HttpRequest.of(
                com.linecorp.armeria.common.HttpMethod.GET, "/x")).aggregate().join();

        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
        // slot released after completion
        assertThat(shedder.snapshot().inFlightRequests()).isZero();
    }
}
