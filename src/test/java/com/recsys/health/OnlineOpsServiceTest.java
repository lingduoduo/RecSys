package com.recsys.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.loadshed.OnlineLoadShedder;
import com.recsys.metrics.OnlineServingMetricsService;
import com.recsys.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OnlineOpsServiceTest {

    private static final RedisRateLimiter RATE_LIMITER = new RedisRateLimiter(
            mock(RedisExecutor.class), "rate:test:", 100L, 1,
            5, 30_000L, 2.5, 3, () -> 0L, () -> 0L);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            OnlineServingMetricsService metrics = new OnlineServingMetricsService();
            OnlineLoadShedder loadShedder = new OnlineLoadShedder();
            sb.service("/online/ops", new OnlineOpsService(
                    metrics, loadShedder, new OnlineCapacityService(), RATE_LIMITER));
        }
    };

    @Test
    void onlineOps_exposesBoundedEmergencyRateLimitState() throws Exception {
        AggregatedHttpResponse response = server.blockingWebClient().get("/online/ops");

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        JsonNode rateLimit = new ObjectMapper().readTree(response.contentUtf8()).get("rateLimit");
        assertThat(rateLimit.get("emergencyEnabled").asBoolean()).isTrue();
        assertThat(rateLimit.get("emergencyRatePerSecond").asDouble()).isEqualTo(2.5);
        assertThat(rateLimit.get("emergencyBurst").asInt()).isEqualTo(3);
        assertThat(rateLimit.get("redisAllowed").asLong()).isZero();
        assertThat(rateLimit.get("redisRejected").asLong()).isZero();
        assertThat(rateLimit.get("emergencyAllowed").asLong()).isZero();
        assertThat(rateLimit.get("emergencyRejected").asLong()).isZero();
        assertThat(rateLimit.get("circuitState").asText()).isEqualTo("CLOSED");
        assertThat(rateLimit.has("buckets")).isFalse();
        assertThat(rateLimit.has("principals")).isFalse();
    }
}
