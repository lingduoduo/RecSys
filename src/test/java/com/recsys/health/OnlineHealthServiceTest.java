package com.recsys.health;

import com.recsys.application.online.OnlineServices;
import com.recsys.loadshed.OnlineLoadShedder;
import com.recsys.metrics.OnlineServingMetricsService;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineHealthServiceTest {
    private static final OnlineLoadShedder SHEDDER = new OnlineLoadShedder(1, 0.75);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/health/live", new OnlineServices.Live())
              .service("/health/ready",
                      new OnlineHealthService(new OnlineServingMetricsService(), SHEDDER));
        }
    };

    @Test
    void drainingChangesReadinessButNotLiveness() {
        assertThat(SHEDDER.tryAcquire()).isTrue();
        try {
            assertThat(server.blockingWebClient().get("/health/ready").status())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(server.blockingWebClient().get("/health/live").status())
                    .isEqualTo(HttpStatus.OK);
        } finally {
            SHEDDER.release();
        }
    }

    // Dedicated shedder for the shutdown case — markShuttingDown() is one-way, so it must not be
    // shared with the utilization-draining test above.
    private static final OnlineLoadShedder SHUTDOWN_SHEDDER = new OnlineLoadShedder(4, 0.95);

    @RegisterExtension
    static final ServerExtension shutdownServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/health/ready",
                    new OnlineHealthService(new OnlineServingMetricsService(), SHUTDOWN_SHEDDER));
        }
    };

    @Test
    void sigtermFlipsReadinessToUnavailableAndBodyShowsShuttingDown() {
        // Healthy before SIGTERM: low utilization, not shutting down.
        var before = shutdownServer.blockingWebClient().get("/health/ready");
        assertThat(before.status()).isEqualTo(HttpStatus.OK);
        assertThat(before.contentUtf8()).contains("\"shuttingDown\":false");

        SHUTDOWN_SHEDDER.markShuttingDown();

        var after = shutdownServer.blockingWebClient().get("/health/ready");
        assertThat(after.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(after.contentUtf8()).contains("\"shuttingDown\":true");
    }
}
