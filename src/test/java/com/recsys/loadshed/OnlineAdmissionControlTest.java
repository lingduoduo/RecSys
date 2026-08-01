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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineAdmissionControlTest {
    private static final OnlineLoadShedder SHEDDER = new OnlineLoadShedder(1, 0.75);
    private static final OnlineServingMetricsService METRICS = new OnlineServingMetricsService();

    /**
     * Gates the delegate's response so the test — not a timer — decides how long the single
     * permit stays held. See {@link #rejectsBeforeDelegateWhenPermitUnavailableAndReleasesOnCompletion}.
     */
    private static final AtomicReference<CompletableFuture<HttpResponse>> RELEASE =
            new AtomicReference<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/controlled", new OnlineAdmissionControl(
                    (ctx, req) -> {
                        CompletableFuture<HttpResponse> gate = RELEASE.get();
                        return gate == null ? HttpResponse.of(HttpStatus.OK) : HttpResponse.of(gate);
                    },
                    SHEDDER, METRICS));
        }
    };

    @Test
    void rejectsBeforeDelegateWhenPermitUnavailableAndReleasesOnCompletion() {
        // The delegate used to hold the permit for a fixed 500ms. That made the test a race
        // against a closing window: it waits for `first` to acquire the permit, then sends the
        // request it expects to be rejected — and if those two steps together outran 500ms on
        // a loaded machine, `first` had already finished, the permit was free, and `rejected`
        // got 200. Widening the wait could not fix it; a longer wait made expiry MORE likely.
        //
        // The permit is now held until this test releases it, so there is no window to lose.
        CompletableFuture<HttpResponse> gate = new CompletableFuture<>();
        RELEASE.set(gate);
        try {
            var first = server.webClient().get("/controlled").aggregate();

            // Wait until `first` has actually acquired the single permit. Without this the
            // async `first` may not have reached the admission gate yet, so `rejected` could
            // win the permit and get 200.
            assertInFlightEventually(1, Duration.ofSeconds(10));

            var rejected = server.blockingWebClient().get("/controlled");
            assertThat(rejected.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(rejected.headers().get("retry-after")).isEqualTo("1");
            assertThat(rejected.contentUtf8()).contains("concurrency_limit");

            gate.complete(HttpResponse.of(HttpStatus.OK));
            assertThat(first.join().status()).isEqualTo(HttpStatus.OK);
            assertInFlightEventually(0, Duration.ofSeconds(5));
        } finally {
            // Never leave the route gated, whatever happened above: a stuck future would hang
            // every later request to /controlled.
            RELEASE.set(null);
            gate.complete(HttpResponse.of(HttpStatus.OK));
        }
    }

    private void assertInFlightEventually(int expected, Duration timeout) {
        assertInFlightEventually(SHEDDER, expected, timeout);
    }

    /**
     * Polls until the shedder reports {@code expected} in-flight requests.
     *
     * <p>Every in-flight transition here is asynchronous with respect to the calling thread:
     * the permit is acquired when Armeria invokes the service, and released by a callback on
     * the response future. Neither is guaranteed to have happened by the time
     * {@code aggregate().join()} returns to the test — so sampling the counter once is a race,
     * not an assertion. This polls instead. It still fails if the transition never happens,
     * which is the property under test.
     *
     * <p>Takes the shedder as a parameter rather than always reading the shared static one:
     * two of these tests build their own, and silently checking the wrong shedder would make
     * an assertion pass for the wrong reason.
     */
    private static void assertInFlightEventually(
            OnlineLoadShedder shedder, int expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int actual;
        do {
            actual = shedder.snapshot().inFlightRequests();
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

        assertThat(actual)
                .as("inFlightRequests did not reach %d within %dms (last observed %d)",
                        expected, timeout.toMillis(), actual)
                .isEqualTo(expected);
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
        // The slot is released by a callback on the response future, which is not guaranteed
        // to have run by the time aggregate().join() returns — sampling the counter here once
        // races that callback and fails roughly 1 run in 3.
        assertInFlightEventually(shedder, 0, Duration.ofSeconds(2));
    }
}
