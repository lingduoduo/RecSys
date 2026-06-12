package com.recsys.online.serving;

import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.redis.RedisConnectionFactory;
import com.recsys.infrastructure.redis.ShardedTopKStore;
import com.recsys.infrastructure.redis.sharding.ConsistentHashRing;
import com.recsys.infrastructure.redis.sharding.SequenceGenerator;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.online.event.AsyncEventPublisher;
import com.recsys.online.learner.LearnerFlushScheduler;
import com.recsys.online.learner.OnlineLearner;
import com.recsys.online.ops.OnlineAdmissionControl;
import com.recsys.online.ops.OnlineCapacityService;
import com.recsys.online.ops.OnlineHealthService;
import com.recsys.online.ops.OnlineLoadShedder;
import com.recsys.online.ops.OnlineOpsService;
import com.recsys.online.ops.OnlineServingMetricsService;
import com.recsys.online.store.ShardedRecordService;
import com.recsys.online.redis.RedisRateLimiter;
import com.recsys.online.store.OnlineFeatureStore;
import com.recsys.online.store.TrendingStore;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

public final class OnlinePredictionServer {
    private static final int DEFAULT_PORT = 7010;

    private OnlinePredictionServer() {}

    public static void main(String[] args) throws Exception {
        int port = readIntEnv("ONLINE_DEMO_PORT", DEFAULT_PORT);
        int requestTimeoutMs = readIntEnv("ONLINE_REQUEST_TIMEOUT_MS", 500);

        Pool<Jedis> jedisPool = RedisConnectionFactory.fromEnv();
        AsyncEventPublisher asyncEventPublisher = new AsyncEventPublisher();
        LearnerFlushScheduler learnerFlushScheduler = null;

        try {
            DataManager dataManager = DataManager.getInstance();
            CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager);
            TrendingStore topkStore = new ShardedTopKStore(jedisPool, "topk:");
            OnlineFeatureStore onlineFeatureStore = new OnlineFeatureStore(jedisPool);
            OnlineRecommendationEngine engine = new OnlineRecommendationEngine(dataManager, topkStore, onlineFeatureStore);
            OnlineLearner onlineLearner = new OnlineLearner();
            OnlineRecommendationService recommendationService =
                    new OnlineRecommendationService(dataManager, engine, candidateGenerator, onlineLearner);
            OnlineBlendingPipeline blendingPipeline = new OnlineBlendingPipeline(recommendationService);
            learnerFlushScheduler =
                    new LearnerFlushScheduler(onlineLearner, jedisPool, "bias:item", 30L);
            learnerFlushScheduler.start();
            PrometheusMeterRegistry registry = PrometheusMeterRegistries.defaultRegistry();
            OnlineServingMetricsService metricsService = new OnlineServingMetricsService();
            OnlineLoadShedder loadShedder = new OnlineLoadShedder();
            OnlineCapacityService capacityService = new OnlineCapacityService();
            RedisRateLimiter redisRateLimiter = new RedisRateLimiter(jedisPool);

            int shardCount = readIntEnv("SHARDED_RECORD_SHARD_COUNT", 2);
            ShardedRecordStore shardedRecordStore = new ShardedRecordStore(
                    jedisPool,
                    new ConsistentHashRing(shardCount, 150),
                    new SequenceGenerator(jedisPool, "sr:"),
                    "sr:");

            ServerBuilder sb = Server.builder();
            sb.http(port)
              .requestTimeoutMillis(requestTimeoutMs)
              .gracefulShutdownTimeoutMillis(1_000L, 30_000L)
              .meterRegistry(registry)
              .decorator(MetricCollectingService.newDecorator(
                      MeterIdPrefixFunction.ofDefault("online_serving")))
              .service("/health/live", new OnlineLiveService())
              .service("/health/ready",
                      new OnlineHealthService(metricsService, loadShedder))
              .service("/health",
                      new OnlineHealthService(metricsService, loadShedder))
              .service("/metrics", PrometheusExpositionService.of(registry.getPrometheusRegistry()))
              .service("/online/features",
                      new OnlineAdmissionControl(
                              new OnlineFeaturesService(recommendationService, metricsService,
                                      loadShedder, redisRateLimiter, asyncEventPublisher, true),
                              loadShedder, metricsService))
              .service("/online/recommendation",
                      new OnlineAdmissionControl(
                              new OnlinePredictionService(recommendationService, metricsService,
                                      loadShedder, redisRateLimiter, true),
                              loadShedder, metricsService))
              .service("/online/ops",
                      new OnlineOpsService(metricsService, loadShedder, capacityService,
                              redisRateLimiter, asyncEventPublisher))
              .service("/v2/recommend", new OnlineRecommendV2Service(blendingPipeline))
              .service(Route.builder().pathPrefix("/shards/").build(),
                      new ShardedRecordService(shardedRecordStore));

            Server server = sb.build();
            metricsService.registerGauges(registry);
            LearnerFlushScheduler activeLearnerFlushScheduler = learnerFlushScheduler;

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop().join();
                asyncEventPublisher.close();
                activeLearnerFlushScheduler.close();
                jedisPool.close();
            }));

            server.start().join();
            server.blockUntilShutdown();
        } catch (Exception e) {
            asyncEventPublisher.close();
            if (learnerFlushScheduler != null) learnerFlushScheduler.close();
            jedisPool.close();
            throw e;
        }
    }

    private static int readIntEnv(String envName, int defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
