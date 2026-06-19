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
import com.recsys.infrastructure.cache.LogicalExpiryEmbeddingCache;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.infrastructure.redis.RedisConnectionFactory;
import com.recsys.infrastructure.redis.RedisEmbeddingStore;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.redis.ShardedTopKStore;
import com.recsys.infrastructure.redis.sharding.ConsistentHashRing;
import com.recsys.infrastructure.redis.sharding.SequenceGenerator;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.online.event.AsyncEventPublisher;
import com.recsys.online.learner.LearnerFlushScheduler;
import com.recsys.online.learner.OnlineLearner;
import com.recsys.online.ops.FaultInjector;
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
import com.recsys.service.retrieval.channels.EmbeddingChannel;
import com.recsys.service.retrieval.channels.OnlineRecentHistoryChannel;
import com.recsys.service.retrieval.channels.PopularityChannel;
import com.recsys.service.retrieval.channels.TrendingChannel;
import com.recsys.service.retrieval.coldstart.ColdStartChannel;
import com.recsys.service.retrieval.coldstart.QuotaPolicy;
import com.recsys.service.retrieval.multichannel.ChannelHealthMonitor;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.service.retrieval.multichannel.RecallConfig;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        ExecutorService recallExecutor = null;

        try {
            DataManager dataManager = DataManager.getInstance();
            RedisEmbeddingStore userEmbeddingStore = new RedisEmbeddingStore(jedisPool, "u2vEmb");
            // u2vEmb is continuously rewritten by Flink: use a soft-TTL cache (serve-stale +
            // background refresh, no Bloom guard) so new users are still found and updates land
            // within ~one soft TTL. Default 30s, overridable for tuning.
            int userEmbSoftTtlSeconds = readIntEnv("ONLINE_USER_EMB_SOFT_TTL_SECONDS", 30);
            EmbeddingStore userEmbCache =
                    new LogicalExpiryEmbeddingCache(userEmbeddingStore, userEmbSoftTtlSeconds);
            CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager, userEmbCache);
            TrendingStore topkStore = new ShardedTopKStore(jedisPool, "topk:");
            OnlineFeatureStore onlineFeatureStore = new OnlineFeatureStore(jedisPool);
            OnlineLearner onlineLearner = new OnlineLearner();
            GlobalPopularityStore globalPopStore = new GlobalPopularityStore(jedisPool);
            recallExecutor = Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors() * 2,
                    r -> new Thread(r, "online-recall-channel"));
            MultiChannelRecallService recallService = MultiChannelRecallService.from(
                    RecallConfig.builder()
                            .channels(List.of(
                                    new EmbeddingChannel(candidateGenerator),
                                    new OnlineRecentHistoryChannel(onlineFeatureStore, dataManager),
                                    new TrendingChannel(topkStore, List.of("last_hour", "last_day")),
                                    new PopularityChannel(dataManager, globalPopStore),
                                    new ColdStartChannel(topkStore, globalPopStore)))
                            .quotaPolicy(QuotaPolicy.defaultOnline())
                            .healthMonitor(new ChannelHealthMonitor())
                            .executor(recallExecutor)
                            .channelTimeoutMs(200L)
                            .faultInjector(FaultInjector.NOOP)
                            .userEmbeddingStore(userEmbCache)
                            .build());
            OnlineRecommendationService recommendationService = new OnlineRecommendationService(
                    dataManager, recallService, onlineFeatureStore, topkStore, onlineLearner);
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

            ExecutorService activeRecallExecutor = recallExecutor;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop().join();
                asyncEventPublisher.close();
                activeLearnerFlushScheduler.close();
                activeRecallExecutor.shutdownNow();
                jedisPool.close();
            }));

            server.start().join();
            server.blockUntilShutdown();
        } catch (Exception e) {
            asyncEventPublisher.close();
            if (learnerFlushScheduler != null) learnerFlushScheduler.close();
            if (recallExecutor != null) recallExecutor.shutdownNow();
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
