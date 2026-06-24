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
}
