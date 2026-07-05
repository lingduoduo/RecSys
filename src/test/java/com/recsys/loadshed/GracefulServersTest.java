package com.recsys.loadshed;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulServersTest {

    @Test
    void applyShutdownWindow_setsOneSecondQuietAndThirtySecondTimeout() {
        ServerBuilder sb = Server.builder().http(0);
        Server server = GracefulServers.applyShutdownWindow(sb)
                .service("/health", (ctx, req) ->
                        com.linecorp.armeria.common.HttpResponse.of(200))
                .build();

        assertThat(server.config().gracefulShutdownQuietPeriod()).isEqualTo(Duration.ofSeconds(1));
        assertThat(server.config().gracefulShutdownTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void applyShutdownWindow_returnsSameBuilderForChaining() {
        ServerBuilder sb = Server.builder().http(0);
        assertThat(GracefulServers.applyShutdownWindow(sb)).isSameAs(sb);
    }
}
