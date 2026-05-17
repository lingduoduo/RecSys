package com.recsys.streaming;

import com.recsys.features.CandidateGenerator;
import com.recsys.features.DataManager;
import com.recsys.features.RedisTopKStore;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import redis.clients.jedis.JedisPool;

import java.net.InetSocketAddress;

public final class OnlinePredictionServer {
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 7010;

    private OnlinePredictionServer() {}

    public static void main(String[] args) throws Exception {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = readIntEnv("REDIS_PORT", 6379);
        int port = readIntEnv("ONLINE_DEMO_PORT", DEFAULT_PORT);

        try (JedisPool jedisPool = new JedisPool(redisHost, redisPort);
             AsyncEventPublisher asyncEventPublisher = new AsyncEventPublisher()) {

            DataManager dataManager = DataManager.getInstance();
            CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager);
            RedisTopKStore topkStore = new RedisTopKStore(jedisPool, "topk:");
            OnlineFeatureStore onlineFeatureStore = new OnlineFeatureStore(jedisPool);
            OnlineRecommendationEngine engine = new OnlineRecommendationEngine(dataManager, topkStore, onlineFeatureStore);
            OnlineRecommendationService recommendationService =
                    new OnlineRecommendationService(dataManager, engine, candidateGenerator);
            OnlineServingMetricsService metricsService = new OnlineServingMetricsService();
            OnlineLoadShedder loadShedder = new OnlineLoadShedder();
            OnlineCapacityService capacityService = new OnlineCapacityService();
            RedisRateLimiter redisRateLimiter = new RedisRateLimiter(jedisPool);

            Server server = new Server(new InetSocketAddress(DEFAULT_HOST, port));
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            context.addServlet(new ServletHolder(new OnlineHealthServlet(metricsService, loadShedder)), "/health");
            context.addServlet(new ServletHolder(new OnlineFeaturesServlet(
                    recommendationService, metricsService, loadShedder, redisRateLimiter,
                    asyncEventPublisher)), "/online/features");
            context.addServlet(new ServletHolder(new OnlinePredictionServlet(
                    recommendationService, metricsService, loadShedder, redisRateLimiter,
                    asyncEventPublisher)), "/online/recommendation");
            context.addServlet(new ServletHolder(new OnlineOpsServlet(
                    metricsService, loadShedder, capacityService, redisRateLimiter,
                    asyncEventPublisher)), "/online/ops");
            server.setHandler(context);
            server.setStopAtShutdown(true);
            server.start();
            server.join();
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
