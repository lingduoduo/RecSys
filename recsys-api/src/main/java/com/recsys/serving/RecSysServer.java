package com.recsys.serving;

import com.recsys.features.DataManager;
import com.recsys.features.RedisEmbeddingStore;
import com.recsys.features.RedisTopKStore;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import redis.clients.jedis.JedisPool;

import java.net.InetSocketAddress;

public class RecSysServer {

    private static final int DEFAULT_PORT = 6010;
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final String ROUTE_MOVIE = "/getmovie";
    private static final String ROUTE_USER = "/getuser";
    private static final String ROUTE_SIMILAR_MOVIE = "/getsimilarmovie";
    private static final String ROUTE_RECOMMENDATION = "/getrecommendation";
    private static final String ROUTE_SET_EMBEDDING = "/setembedding";
    private static final String ROUTE_HEALTH = "/health";

    public static void main(String[] args) throws Exception {
        new RecSysServer().run();
    }

    public void run() throws Exception {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = readIntEnv("REDIS_PORT", 6379);
        int port = readIntEnv("PORT", DEFAULT_PORT);

        try (JedisPool jedisPool = new JedisPool(redisHost, redisPort)) {
            DataManager dataManager = DataManager.getInstance();
            RedisEmbeddingStore embStore = new RedisEmbeddingStore(jedisPool, "i2vEmb");
            RedisTopKStore topkStore = new RedisTopKStore(jedisPool, "topk:");

            InetSocketAddress inetAddress = new InetSocketAddress(DEFAULT_HOST, port);
            Server server = new Server(inetAddress);

            ServletContextHandler context = createContext();
            registerRoutes(context, dataManager, embStore, topkStore);

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
                                       RedisEmbeddingStore embStore,
                                       RedisTopKStore topkStore) {
        context.addServlet(new ServletHolder(new MovieService(dataManager)), ROUTE_MOVIE);
        context.addServlet(new ServletHolder(new UserService(dataManager)), ROUTE_USER);
        context.addServlet(new ServletHolder(new SimilarMovieService(embStore)), ROUTE_SIMILAR_MOVIE);
        context.addServlet(new ServletHolder(new RecommendationService(dataManager, topkStore)), ROUTE_RECOMMENDATION);
        context.addServlet(new ServletHolder(new SetEmbeddingService(embStore)), ROUTE_SET_EMBEDDING);
        context.addServlet(new ServletHolder(new HealthService()), ROUTE_HEALTH);
    }

    private static int readIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        return Integer.parseInt(value);
    }
}
