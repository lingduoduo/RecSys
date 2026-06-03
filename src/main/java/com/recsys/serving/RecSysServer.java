package com.recsys.serving;

import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.infrastructure.DataLoader;
import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.cache.LocalEmbeddingCache;
import com.recsys.infrastructure.PairPredictionService;
import com.recsys.infrastructure.redis.RedisEmbeddingStore;
import com.recsys.infrastructure.redis.ShardedTopKStore;
import com.recsys.streaming.TrendingStore;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.recsys.infrastructure.redis.RedisConnectionFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.net.InetSocketAddress;

public class RecSysServer {

    private static final Logger log = LoggerFactory.getLogger(RecSysServer.class);
    private static final int DEFAULT_PORT = 6010;
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final String ROUTE_ITEM = "/item";
    private static final String ROUTE_USER = "/getuser";
    private static final String ROUTE_SIMILAR = "/similar";
    private static final String ROUTE_RECOMMENDATION = "/getrecommendation";
    private static final String ROUTE_SET_EMBEDDING = "/setembedding";
    private static final String ROUTE_HEALTH = "/health";
    private static final String ROUTE_PREDICT = "/v1/models/recmodel:predict";

    public static void main(String[] args) throws Exception {
        new RecSysServer().run();
    }

    public void run() throws Exception {
        int port = readIntEnv("PORT", DEFAULT_PORT);

        try (Pool<Jedis> jedisPool = RedisConnectionFactory.fromEnv()) {
            DataManager dataManager = DataManager.getInstance();
            PairPredictionService pairPredictionService = new PairPredictionService();
            RedisEmbeddingStore embStore     = new RedisEmbeddingStore(jedisPool, "i2vEmb");
            RedisEmbeddingStore userEmbStore = new RedisEmbeddingStore(jedisPool, "u2vEmb");
            TrendingStore topkStore = new ShardedTopKStore(jedisPool, "topk:");

            seedEmbeddings(embStore, userEmbStore);

            // Tier 3: JVM heap caches in front of Redis for both item and user embeddings.
            // preload() from classpath (Tier 1) avoids Redis round-trips at startup;
            // warmUp() then adds any entries written by Flink that aren't in the classpath.
            LocalEmbeddingCache embCache = new LocalEmbeddingCache(embStore);
            embCache.preload(DataLoader.loadMovieEmbeddings());
            embCache.warmUp();

            LocalEmbeddingCache userEmbCache = new LocalEmbeddingCache(userEmbStore);
            userEmbCache.preload(DataLoader.loadUserEmbeddings());
            userEmbCache.warmUp();

            CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager, userEmbCache);

            InetSocketAddress inetAddress = new InetSocketAddress(DEFAULT_HOST, port);
            Server server = new Server(inetAddress);

            ServletContextHandler context = createContext();
            registerRoutes(context, dataManager, candidateGenerator, embCache, topkStore, pairPredictionService);

            server.setHandler(context);
            server.setStopAtShutdown(true);
            server.start();
            server.join();
        }
    }

    private static ServletContextHandler createContext() {
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setWelcomeFiles(new String[]{"index.html"});
        return context;
    }

    private static void registerRoutes(ServletContextHandler context,
                                       DataManager dataManager,
                                       CandidateGenerator candidateGenerator,
                                       EmbeddingStore embStore,
                                       TrendingStore topkStore,
                                       PairPredictionService pairPredictionService) {
        context.addServlet(new ServletHolder(new MovieService(dataManager)), ROUTE_ITEM);
        context.addServlet(new ServletHolder(new UserService(dataManager)), ROUTE_USER);
        context.addServlet(new ServletHolder(new SimilarMovieService(embStore)), ROUTE_SIMILAR);
        context.addServlet(new ServletHolder(new RecommendationService(dataManager, candidateGenerator, topkStore)), ROUTE_RECOMMENDATION);
        context.addServlet(new ServletHolder(new SetEmbeddingService(embStore)), ROUTE_SET_EMBEDDING);
        context.addServlet(new ServletHolder(new HealthService()), ROUTE_HEALTH);
        context.addServlet(new ServletHolder(new PredictionService(pairPredictionService)), ROUTE_PREDICT);
    }

    // Seeds Redis with sample embeddings only if not already present.
    private static void seedEmbeddings(RedisEmbeddingStore embStore,
                                       RedisEmbeddingStore userEmbStore) {
        if (embStore.scanIds(1).isEmpty()) {
            embStore.setEmbeddings(DataLoader.loadMovieEmbeddings(), 0);
            log.info("Seeded movie embeddings from movie_embeddings.txt");
        }
        if (userEmbStore.scanIds(1).isEmpty()) {
            userEmbStore.setEmbeddings(DataLoader.loadUserEmbeddings(), 0);
            log.info("Seeded user embeddings from user_embeddings.txt");
        }
    }

    private static int readIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid integer: " + value);
        }
    }
}
