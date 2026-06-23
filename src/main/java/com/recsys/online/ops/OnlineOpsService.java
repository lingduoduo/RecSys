package com.recsys.online.ops;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.messaging.AsyncEventPublisher;
import com.recsys.online.redis.RedisRateLimiter;
import com.recsys.online.serving.ApiService;

import java.time.Instant;

public final class OnlineOpsService extends ApiService {

    private final OnlineServingMetricsService metricsService;
    private final OnlineLoadShedder loadShedder;
    private final OnlineCapacityService capacityService;
    private final RedisRateLimiter redisRateLimiter;
    private final AsyncEventPublisher asyncEventPublisher;

    public OnlineOpsService(OnlineServingMetricsService metricsService,
                            OnlineLoadShedder loadShedder,
                            OnlineCapacityService capacityService) {
        this(metricsService, loadShedder, capacityService, RedisRateLimiter.disabled(), null);
    }

    public OnlineOpsService(OnlineServingMetricsService metricsService,
                            OnlineLoadShedder loadShedder,
                            OnlineCapacityService capacityService,
                            RedisRateLimiter redisRateLimiter) {
        this(metricsService, loadShedder, capacityService, redisRateLimiter, null);
    }

    public OnlineOpsService(OnlineServingMetricsService metricsService,
                            OnlineLoadShedder loadShedder,
                            OnlineCapacityService capacityService,
                            RedisRateLimiter redisRateLimiter,
                            AsyncEventPublisher asyncEventPublisher) {
        this.metricsService = metricsService;
        this.loadShedder = loadShedder;
        this.capacityService = capacityService;
        this.redisRateLimiter = redisRateLimiter;
        this.asyncEventPublisher = asyncEventPublisher;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
            try {
                OnlineServingMetricsService.Snapshot metrics = metricsService.snapshot();
                OnlineLoadShedder.Snapshot load = loadShedder.snapshot();
                OnlineCapacityService.Snapshot capacity = capacityService.snapshot(metrics, load);

                AsyncEventPublisher.Snapshot events = asyncEventPublisher != null
                        ? asyncEventPublisher.snapshot()
                        : new AsyncEventPublisher.Snapshot(0, 0L, 0L, 0L);

                OnlineOpsResponse opsResponse = new OnlineOpsResponse(
                        Instant.now().toString(),
                        metrics,
                        load,
                        redisRateLimiter.snapshot(),
                        capacity,
                        events
                );

                byte[] body = MAPPER.writeValueAsBytes(opsResponse);

                if (load.retryAfterSeconds() > 0) {
                    ResponseHeaders headers = ResponseHeaders.builder(HttpStatus.OK)
                            .contentType(MediaType.JSON_UTF_8)
                            .set(HttpHeaderNames.RETRY_AFTER, String.valueOf(load.retryAfterSeconds()))
                            .build();
                    return HttpResponse.of(headers, HttpData.wrap(body));
                }
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, body);
            } catch (Exception e) {
                log.error("Unexpected error in OnlineOpsService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }

    private record OnlineOpsResponse(
            String servedAt,
            OnlineServingMetricsService.Snapshot metrics,
            OnlineLoadShedder.Snapshot load,
            RedisRateLimiter.Snapshot rateLimit,
            OnlineCapacityService.Snapshot capacity,
            AsyncEventPublisher.Snapshot events
    ) {}
}
