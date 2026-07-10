package com.recsys.api.online;

import com.recsys.application.online.OnlineServices;
import com.recsys.application.online.OnlineBlendingPipeline;
import com.recsys.application.online.OnlineRecommendationService;
import com.recsys.metrics.OnlineServingMetricsService;

import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.infrastructure.cache.LogicalExpiryEmbeddingCache;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.infrastructure.redis.LettuceClientFactory;
import com.recsys.infrastructure.redis.RedisEmbeddingStore;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.redis.ShardedTopKStore;
import com.recsys.infrastructure.redis.sharding.ConsistentHashRing;
import com.recsys.infrastructure.redis.sharding.SequenceGenerator;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import com.recsys.infrastructure.redis.sharding.ShardTopologyProvider;
import com.recsys.infrastructure.redis.sharding.ShardTopologyStore;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.infrastructure.messaging.AsyncEventPublisher;
import com.recsys.application.online.LearnerFlushScheduler;
import com.recsys.application.online.OnlineLearner;
import com.recsys.config.EnvConfig;
import com.recsys.resilience.FaultInjector;
import com.recsys.resilience.WorkerBulkhead;
import com.recsys.loadshed.GracefulExecutors;
import com.recsys.loadshed.GracefulServers;
import com.recsys.loadshed.OnlineAdmissionControl;
import com.recsys.health.OnlineCapacityService;
import com.recsys.health.OnlineHealthService;
import com.recsys.loadshed.OnlineLoadShedder;
import com.recsys.health.OnlineOpsService;
import com.recsys.infrastructure.store.ShardedRecordService;
import com.recsys.ratelimit.RedisRateLimiter;
import com.recsys.infrastructure.store.OnlineFeatureStore;
import com.recsys.infrastructure.store.TrendingStore;
import com.recsys.infrastructure.messaging.AsyncEventPublisherFactory;
import com.recsys.application.retrieval.channels.Channels;
import com.recsys.application.retrieval.coldstart.ColdStartChannel;
import com.recsys.application.retrieval.coldstart.QuotaPolicy;
import com.recsys.application.retrieval.multichannel.ChannelHealthMonitor;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallConfig;
import java.util.List;
import java.util.concurrent.ExecutorService;

public final class OnlinePredictionServer {
    private static final int DEFAULT_PORT = 7010;

    private OnlinePredictionServer() {}

    public static void main(String[] args) throws Exception {
        int port = readIntEnv("ONLINE_DEMO_PORT", DEFAULT_PORT);
        int requestTimeoutMs = readIntEnv("ONLINE_REQUEST_TIMEOUT_MS", 500);

        RedisExecutor jedisPool = LettuceClientFactory.routingFromEnv();
        final com.recsys.infrastructure.registry.ServiceRegistrar registrar =
                com.recsys.infrastructure.registry.ServiceRegistrar.fromEnvironment(
                        new com.recsys.infrastructure.registry.ServiceRegistryStore(
                                jedisPool, com.recsys.infrastructure.registry.ServiceRegistryStore.DEFAULT_KEY_PREFIX));
        if (registrar != null) {
            registrar.start();
        }
        AsyncEventPublisher asyncEventPublisher = createAsyncEventPublisher();
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
            int recallPoolSize = Runtime.getRuntime().availableProcessors() * 2;
            WorkerBulkhead recallBulkhead = new WorkerBulkhead("recall-online", recallPoolSize,
                    EnvConfig.readInt("RECALL_BULKHEAD_QUEUE_CAPACITY", recallPoolSize * 4));
            recallExecutor = recallBulkhead.asExecutorService();
            MultiChannelRecallService recallService = MultiChannelRecallService.from(
                    RecallConfig.builder()
                            .channels(List.of(
                                    new Channels.Embedding(candidateGenerator),
                                    new Channels.OnlineRecentHistory(onlineFeatureStore, dataManager),
                                    new Channels.UserSimilarity(dataManager),
                                    new Channels.Trending(topkStore, List.of("last_hour", "last_day")),
                                    new Channels.Popularity(dataManager, globalPopStore),
                                    new ColdStartChannel(topkStore, globalPopStore)))
                            .quotaPolicy(QuotaPolicy.defaultOnline())
                            .healthMonitor(new ChannelHealthMonitor())
                            .executor(recallExecutor)
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
            long refreshMs = readIntEnv("SHARD_TOPOLOGY_REFRESH_SECONDS", 30) * 1000L;
            ShardTopologyStore topologyStore = new ShardTopologyStore(jedisPool, "shard:topology");
            ShardTopologyProvider topologyProvider = new ShardTopologyProvider(
                    topologyStore, 150, shardCount, refreshMs,
                    System::currentTimeMillis);
            topologyProvider.start();
            ShardedRecordStore shardedRecordStore = new ShardedRecordStore(
                    jedisPool, jedisPool, topologyProvider,
                    new SequenceGenerator(jedisPool, "sr:"), "sr:");

            ServerBuilder sb = Server.builder();
            sb.http(port)
              .requestTimeoutMillis(requestTimeoutMs)
              .meterRegistry(registry)
              .decorator(MetricCollectingService.newDecorator(
                      MeterIdPrefixFunction.ofDefault("online_serving")))
              .service("/health/live", new OnlineServices.Live())
              .service("/health/ready",
                      new OnlineHealthService(metricsService, loadShedder))
              .service("/health",
                      new OnlineHealthService(metricsService, loadShedder))
              .service("/metrics", PrometheusExpositionService.of(registry.getPrometheusRegistry()))
              .service("/online/features",
                      new OnlineAdmissionControl(
                              new OnlineServices.Features(recommendationService, metricsService,
                                      loadShedder, redisRateLimiter, asyncEventPublisher, true),
                              loadShedder, metricsService))
              .service("/online/recommendation",
                      new OnlineAdmissionControl(
                              new OnlineServices.Prediction(recommendationService, metricsService,
                                      loadShedder, redisRateLimiter, true),
                              loadShedder, metricsService))
              .service("/online/ops",
                      new OnlineOpsService(metricsService, loadShedder, capacityService,
                              redisRateLimiter, asyncEventPublisher))
              .service("/v2/recommend", new OnlineServices.RecommendV2(blendingPipeline))
              .service(Route.builder().pathPrefix("/shards/").build(),
                      new ShardedRecordService(shardedRecordStore, topologyStore,
                              System.getenv("SHARD_ADMIN_TOKEN"),
                              readIntEnv("SHARDED_RECORD_MAX_TTL_SECONDS", 86_400) * 1000L,
                              System::currentTimeMillis));

            GracefulServers.applyShutdownWindow(sb);

            Server server = sb.build();
            metricsService.registerGauges(registry);
            LearnerFlushScheduler activeLearnerFlushScheduler = learnerFlushScheduler;
            ShardTopologyProvider activeTopologyProvider = topologyProvider;

            ExecutorService activeRecallExecutor = recallExecutor;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                loadShedder.markShuttingDown();   // flip readiness to 503 + shed new load before draining
                server.stop().join();
                asyncEventPublisher.close();
                activeLearnerFlushScheduler.close();
                GracefulExecutors.shutdownGracefully(activeRecallExecutor);
                activeTopologyProvider.stop();
                if (registrar != null) {
                    registrar.close();
                }
                jedisPool.close();
            }));

            server.start().join();
            server.blockUntilShutdown();
        } catch (Exception e) {
            asyncEventPublisher.close();
            if (learnerFlushScheduler != null) learnerFlushScheduler.close();
            if (recallExecutor != null) GracefulExecutors.shutdownGracefully(recallExecutor);
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

    static AsyncEventPublisher createAsyncEventPublisher() {
        return AsyncEventPublisherFactory.fromEnvironment("ONLINE_EVENTS");
    }
}
