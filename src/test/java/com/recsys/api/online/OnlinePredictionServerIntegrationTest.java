package com.recsys.api.online;

import com.recsys.application.online.OnlineServices;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.application.online.OnlineRecommendationService;
import com.recsys.metrics.OnlineServingMetricsService;
import com.recsys.application.auth.AdminTokenGuard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.redis.sharding.Page;
import com.recsys.infrastructure.redis.sharding.RecordType;
import com.recsys.infrastructure.redis.sharding.ShardCursor;
import com.recsys.infrastructure.redis.sharding.ShardedRecord;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import com.recsys.infrastructure.redis.sharding.WriteResult;
import com.recsys.infrastructure.redis.sharding.WriteStatus;
import com.recsys.infrastructure.messaging.AsyncEventPublisher;
import com.recsys.domain.user.User;
import com.recsys.loadshed.OnlineAdmissionControl;
import com.recsys.health.OnlineCapacityService;
import com.recsys.health.OnlineHealthService;
import com.recsys.loadshed.OnlineLoadShedder;
import com.recsys.health.OnlineOpsService;
import com.recsys.ratelimit.RedisRateLimiter;
import com.recsys.infrastructure.store.ShardedRecordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlinePredictionServerIntegrationTest {

    static final String ADMIN_TOKEN = "ops-token";
    static final OnlineRecommendationService mockRec = mock(OnlineRecommendationService.class);
    static final ShardedRecordStore mockStore = mock(ShardedRecordStore.class);

    static {
        OnlineRecommendationResult result = new OnlineRecommendationResult(
                new User(1, "Alice"), "last_hour", "trending",
                List.of(), List.of(), List.of());
        try { when(mockRec.recommend(any())).thenReturn(result); }
        catch (Exception ignored) {}
        when(mockStore.write(any())).thenReturn(new WriteResult(42L, 0, WriteStatus.OK));
        when(mockStore.readDevice(any(), any(), anyInt()))
                .thenReturn(new Page<>(List.of(), null));
        when(mockStore.readShard(anyInt(), any(), anyInt()))
                .thenReturn(new Page<>(List.of(), null));
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            OnlineServingMetricsService metrics = new OnlineServingMetricsService();
            OnlineLoadShedder shedder = new OnlineLoadShedder();
            OnlineCapacityService capacity = new OnlineCapacityService();

            sb.service("/health/live", new OnlineServices.Live())
              .service("/health/ready", new OnlineHealthService(metrics, shedder))
              .service("/health", new OnlineHealthService(metrics, shedder))
              .service("/metrics", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .service("/online/features", new OnlineAdmissionControl(
                      new OnlineServices.Features(mockRec, metrics, shedder,
                              RedisRateLimiter.disabled(), null, true), shedder, metrics))
              .service("/online/recommendation", new OnlineAdmissionControl(
                      new OnlineServices.Prediction(mockRec, metrics, shedder,
                              RedisRateLimiter.disabled(), true), shedder, metrics))
              .service("/online/ops", new OnlineOpsService(metrics, shedder, capacity)
                      .decorate(AdminTokenGuard.newDecorator(new AdminTokenGuard(ADMIN_TOKEN))))
              .service(Route.builder().pathPrefix("/shards/").build(),
                      new ShardedRecordService(mockStore, null, ADMIN_TOKEN, 0L,
                              System::currentTimeMillis));
        }
    };

    @Test void healthReturns200() {
        assertThat(server.blockingWebClient().get("/health").status()).isEqualTo(HttpStatus.OK);
    }

    @Test void liveAndReadyHealthReturn200() {
        assertThat(server.blockingWebClient().get("/health/live").status()).isEqualTo(HttpStatus.OK);
        assertThat(server.blockingWebClient().get("/health/ready").status()).isEqualTo(HttpStatus.OK);
    }

    @Test void metricsReturns200() {
        assertThat(server.blockingWebClient().get("/metrics").status()).isEqualTo(HttpStatus.OK);
    }

    @Test void onlineRecommendation() {
        assertThat(server.blockingWebClient().get("/online/recommendation?userId=1&k=5").status())
                .isEqualTo(HttpStatus.OK);
    }

    @Test void onlineRecommendationMissingUserId() {
        assertThat(server.blockingWebClient().get("/online/recommendation").status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void onlineFeatures() {
        assertThat(server.blockingWebClient().get("/online/features?userId=1").status())
                .isEqualTo(HttpStatus.OK);
    }

    @Test void opsWithAdminTokenReturns200() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .prepare().get("/online/ops").header(AdminTokenGuard.HEADER, ADMIN_TOKEN).execute();
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
    }

    @Test void opsWithoutAdminTokenReturns403() {
        assertThat(server.blockingWebClient().get("/online/ops").status())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void defaultAsyncEventPublisherIsLogOnlyWhenEnvIsAbsent() {
        AsyncEventPublisher publisher = OnlinePredictionServer.createAsyncEventPublisher();
        assertThat(publisher).isExactlyInstanceOf(AsyncEventPublisher.class);
        publisher.close();
    }

    @Test void writeShardRecord() throws Exception {
        String body = "{\"deviceId\":\"d1\",\"eventId\":\"e1\",\"type\":\"EVENT\"}";
        AggregatedHttpResponse r = server.blockingWebClient()
                .execute(RequestHeaders.builder(HttpMethod.POST, "/shards/records")
                        .set("content-type", "application/json").build(),
                        HttpData.ofUtf8(body));
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        JsonNode json = new ObjectMapper().readTree(r.contentUtf8());
        assertThat(json.get("seqNum").asLong()).isEqualTo(42L);
    }

    @Test void writeShardRecordMissingDeviceId() throws Exception {
        String body = "{\"eventId\":\"e1\",\"type\":\"EVENT\"}";
        AggregatedHttpResponse r = server.blockingWebClient()
                .execute(RequestHeaders.builder(HttpMethod.POST, "/shards/records")
                        .set("content-type", "application/json").build(),
                        HttpData.ofUtf8(body));
        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void readShardDevice() {
        assertThat(server.blockingWebClient().get("/shards/device?deviceId=d1").status())
                .isEqualTo(HttpStatus.OK);
    }

    @Test void readShardByIndexWithAdminTokenReturns200() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .prepare().get("/shards/shard?index=0")
                .header(AdminTokenGuard.HEADER, ADMIN_TOKEN).execute();
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
    }

    @Test void readShardByIndexWithoutAdminTokenReturns403() {
        assertThat(server.blockingWebClient().get("/shards/shard?index=0").status())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
