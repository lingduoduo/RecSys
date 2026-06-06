package com.recsys.serving;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.cors.CorsService;
import com.recsys.infrastructure.DataLoader;
import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.PairPredictionService;
import com.recsys.infrastructure.cache.LocalEmbeddingCache;
import com.recsys.infrastructure.redis.RedisConnectionFactory;
import com.recsys.infrastructure.redis.RedisEmbeddingStore;
import com.recsys.infrastructure.redis.ShardedTopKStore;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.service.retrieval.EmbeddingChannel;
import com.recsys.service.retrieval.GenreHistoryChannel;
import com.recsys.service.retrieval.MultiChannelRecallService;
import com.recsys.service.retrieval.PopularityChannel;
import com.recsys.service.retrieval.TrendingChannel;
import com.recsys.streaming.TrendingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;
import java.util.List;

public class RecSysServer {

    private static final Logger log = LoggerFactory.getLogger(RecSysServer.class);
    private static final int DEFAULT_PORT = 6010;
    private static final String ROUTE_ITEM = "/item";
    private static final String ROUTE_USER = "/getuser";
    private static final String ROUTE_SIMILAR = "/similar";
    private static final String ROUTE_RECOMMENDATION = "/getrecommendation";
    private static final String ROUTE_SET_EMBEDDING = "/setembedding";
    private static final String ROUTE_SET_USER_EMBEDDING = "/setuserembedding";
    private static final String ROUTE_HEALTH = "/health";
    private static final String ROUTE_PREDICT = "/v1/models/recmodel:predict";
    // REST-style aliases used when requests arrive via the API gateway
    // (gateway strips /api/users, /api/movies, etc., leaving these suffixes).
    private static final String ROUTE_USER_ALIAS = "/user";
    private static final String ROUTE_ITEM_ALIAS = "/movie";
    private static final String ROUTE_RECOMMENDATION_ALIAS = "/recommendation";

    public static void main(String[] args) throws Exception {
        new RecSysServer().run();
    }

    public void run() throws Exception {
        int port = readIntEnv("PORT", DEFAULT_PORT);
        Pool<Jedis> jedisPool = RedisConnectionFactory.fromEnv();
        try {
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

            MultiChannelRecallService recallService = new MultiChannelRecallService(List.of(
                    new EmbeddingChannel(candidateGenerator),
                    new TrendingChannel(topkStore),
                    new GenreHistoryChannel(candidateGenerator),
                    new PopularityChannel(dataManager)
            ));

            MovieService movieService = new MovieService(dataManager);
            UserService userService = new UserService(dataManager);
            RecommendationService recommendationService =
                    new RecommendationService(dataManager, recallService);

            String corsOrigin = System.getenv("CORS_ALLOWED_ORIGIN");

            ServerBuilder sb = Server.builder()
                    .http(port)
                    .service(ROUTE_ITEM, movieService)
                    .service(ROUTE_ITEM_ALIAS, movieService)
                    .service(ROUTE_USER, userService)
                    .service(ROUTE_USER_ALIAS, userService)
                    .service(ROUTE_SIMILAR, new SimilarMovieService(embCache))
                    .service(ROUTE_RECOMMENDATION, recommendationService)
                    .service(ROUTE_RECOMMENDATION_ALIAS, recommendationService)
                    .service(ROUTE_SET_EMBEDDING, new SetEmbeddingService(embCache, candidateGenerator))
                    .service(ROUTE_SET_USER_EMBEDDING, new SetUserEmbeddingService(userEmbCache))
                    .service(ROUTE_HEALTH, new HealthService())
                    // exact() encodes ':' as '%3A' so the literal path never matches;
                    // regex routing matches against the decoded path.
                    .service(Route.builder()
                                     .regex("^" + ROUTE_PREDICT + "$")
                                     .methods(HttpMethod.POST)
                                     .build(),
                             new PredictionService(pairPredictionService));

            if (corsOrigin != null && !corsOrigin.isBlank()) {
                sb.decorator(CorsService.builder(corsOrigin)
                        .allowAllRequestHeaders(true)
                        .allowRequestMethods(
                                HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                                HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS, HttpMethod.HEAD)
                        .newDecorator());
            }

            Server server = sb.build();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop().join();
                jedisPool.close();
            }, "recsys-shutdown"));
            log.info("Starting RecSys serving API on port {}", port);
            server.start().join();
            server.blockUntilShutdown();
        } catch (Exception e) {
            jedisPool.close();
            throw e;
        }
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
