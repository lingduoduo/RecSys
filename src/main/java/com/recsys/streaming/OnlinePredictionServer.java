package com.recsys.streaming;

import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.redis.RedisConnectionFactory;
import com.recsys.infrastructure.redis.ShardedTopKStore;
import com.recsys.infrastructure.redis.sharding.ConsistentHashRing;
import com.recsys.infrastructure.redis.sharding.SequenceGenerator;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

public final class OnlinePredictionServer {
    private static final int DEFAULT_PORT = 7010;

    private OnlinePredictionServer() {}

    public static void main(String[] args) throws Exception {
        int port = readIntEnv("ONLINE_DEMO_PORT", DEFAULT_PORT);

        Pool<Jedis> jedisPool = RedisConnectionFactory.fromEnv();
        AsyncEventPublisher asyncEventPublisher = new AsyncEventPublisher();

        try {
            DataManager dataManager = DataManager.getInstance();
            CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager);
            TrendingStore topkStore = new ShardedTopKStore(jedisPool, "topk:");
            OnlineFeatureStore onlineFeatureStore = new OnlineFeatureStore(jedisPool);
            OnlineRecommendationEngine engine = new OnlineRecommendationEngine(dataManager, topkStore, onlineFeatureStore);
            OnlineRecommendationService recommendationService =
                    new OnlineRecommendationService(dataManager, engine, candidateGenerator);
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
              .service("/health",
                      new OnlineHealthService(metricsService, loadShedder))
              .service("/online/features",
                      new OnlineFeaturesService(recommendationService, metricsService,
                              loadShedder, redisRateLimiter, asyncEventPublisher))
              .service("/online/recommendation",
                      new OnlinePredictionService(recommendationService, metricsService,
                              loadShedder, redisRateLimiter, asyncEventPublisher))
              .service("/online/ops",
                      new OnlineOpsService(metricsService, loadShedder, capacityService,
                              redisRateLimiter, asyncEventPublisher))
              .service(Route.builder().pathPrefix("/shards/").build(),
                      new ShardedRecordService(shardedRecordStore));

            Server server = sb.build();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop().join();
                asyncEventPublisher.close();
                jedisPool.close();
            }));

            server.start().join();
            server.blockUntilShutdown();
        } catch (Exception e) {
            asyncEventPublisher.close();
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
