package com.recsys.streaming;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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
        assertThat(SHEDDER.snapshot().inFlightRequests()).isZero();
    }
}
