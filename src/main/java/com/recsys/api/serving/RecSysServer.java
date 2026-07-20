package com.recsys.api.serving;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.cors.CorsService;
import com.recsys.config.EnvConfig;
import com.recsys.infrastructure.dataloading.DataLoader;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.application.model.PairPredictionService;
import com.recsys.loadshed.GracefulExecutors;
import com.recsys.loadshed.GracefulServers;
import com.recsys.loadshed.OnlineAdmissionControl;
import com.recsys.loadshed.OnlineLoadShedder;
import com.recsys.infrastructure.cache.LocalEmbeddingCache;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.infrastructure.redis.LettuceClientFactory;
import com.recsys.infrastructure.redis.RedisEmbeddingStore;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.infrastructure.redis.ShardedTopKStore;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.resilience.FaultInjector;
import com.recsys.resilience.WorkerBulkhead;
import com.recsys.application.recommendation.RecommendationHydrator;
import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.ranking.ScoreRanker;
import com.recsys.application.recommendation.RecommendationOrchestrator;
import com.recsys.application.retrieval.channels.Channels;
import com.recsys.application.retrieval.coldstart.ColdStartChannel;
import com.recsys.application.retrieval.coldstart.QuotaPolicy;
import com.recsys.application.retrieval.multichannel.ChannelHealthMonitor;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallConfig;
import com.recsys.application.retrieval.multichannel.RecallDegradationMetrics;
import com.recsys.infrastructure.store.TrendingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.ExecutorService;

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
    private static final String ROUTE_V2_RECOMMEND = "/v2/recommend";
    private static final String ROUTE_MOVIE_CATALOG = "/v1/catalog/movies";
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
        RedisExecutor jedisPool = LettuceClientFactory.routingFromEnv();
        CatalogComponent catalogComponent = null;
        final com.recsys.infrastructure.registry.ServiceRegistrar registrar =
                com.recsys.infrastructure.registry.ServiceRegistrar.fromEnvironment(
                        new com.recsys.infrastructure.registry.ServiceRegistryStore(
                                jedisPool, com.recsys.infrastructure.registry.ServiceRegistryStore.DEFAULT_KEY_PREFIX));
        if (registrar != null) {
            registrar.start();
        }
        try {
            catalogComponent = CatalogComponent.fromEnvironment();
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

            GlobalPopularityStore globalPopStore = new GlobalPopularityStore(jedisPool);
            int recallPoolSize = Runtime.getRuntime().availableProcessors() * 2;
            WorkerBulkhead recallBulkhead = new WorkerBulkhead("recall-catalog", recallPoolSize,
                    EnvConfig.readInt("RECALL_BULKHEAD_QUEUE_CAPACITY", recallPoolSize * 4));
            ExecutorService executor = recallBulkhead.asExecutorService();
            RecallDegradationMetrics recallMetrics = new RecallDegradationMetrics();

            MultiChannelRecallService recallService = MultiChannelRecallService.from(
                    RecallConfig.builder()
                            .channels(List.of(
                                    new Channels.Embedding(candidateGenerator),
                                    new Channels.UserSimilarity(dataManager),
                                    new Channels.Trending(topkStore, List.of("last_hour", "last_day")),
                                    new Channels.GenreHistory(candidateGenerator),
                                    new Channels.Popularity(dataManager, globalPopStore),
                                    new ColdStartChannel(topkStore, globalPopStore)))
                            .quotaPolicy(QuotaPolicy.defaultMovie())
                            .healthMonitor(new ChannelHealthMonitor())
                            .executor(executor)
                            .faultInjector(FaultInjector.NOOP)
                            .userEmbeddingStore(userEmbCache)
                            .recallMetrics(recallMetrics)
                            .build());

            RecommendationOrchestrator orchestrator = new RecommendationOrchestrator(
                    recallService,
                    new ScoreRanker(),
                    RecommendationHydrator.IDENTITY,
                    new CursorPaginationService()
            );

            CatalogService.Movies movieService = new CatalogService.Movies(dataManager);
            CatalogService.Users userService = new CatalogService.Users(dataManager);
            RecommendationService.V1 recommendationService =
                    new RecommendationService.V1(dataManager, recallService);

            String corsOrigin = System.getenv("CORS_ALLOWED_ORIGIN");

            OnlineLoadShedder loadShedder = new OnlineLoadShedder(
                    EnvConfig.readInt("CATALOG_MAX_CONCURRENT_REQUESTS", 64),
                    EnvConfig.readDouble("CATALOG_DRAIN_UTILIZATION", 0.90));

            ServerBuilder sb = Server.builder()
                    .http(port)
                    .service(ROUTE_ITEM, movieService)
                    .service(ROUTE_ITEM_ALIAS, movieService)
                    .service(ROUTE_USER, userService)
                    .service(ROUTE_USER_ALIAS, userService)
                    .service(ROUTE_SIMILAR,
                            new OnlineAdmissionControl(new RecommendationService.Similar(embCache),
                                    loadShedder, () -> {}))
                    .service(ROUTE_RECOMMENDATION,
                            new OnlineAdmissionControl(recommendationService, loadShedder, () -> {}))
                    .service(ROUTE_RECOMMENDATION_ALIAS,
                            new OnlineAdmissionControl(recommendationService, loadShedder, () -> {}))
                    .service(ROUTE_SET_EMBEDDING, new EmbeddingService.SetMovie(embCache, candidateGenerator))
                    .service(ROUTE_SET_USER_EMBEDDING, new EmbeddingService.SetUser(userEmbCache))
                    .service(ROUTE_HEALTH, new RecommendationService.Health())
                    .service("/health/ready", (ctx, req) ->
                            loadShedder.shouldDrain()
                                    ? HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE)
                                    : HttpResponse.of(HttpStatus.OK))
                    .service("/health/load", new CatalogLoadService(recallBulkhead, recallMetrics))
                    .service(ROUTE_V2_RECOMMEND,
                            new OnlineAdmissionControl(new RecommendationService.V2(orchestrator),
                                    loadShedder, () -> {}))
                    // exact() encodes ':' as '%3A' so the literal path never matches;
                    // regex routing matches against the decoded path.
                    .service(Route.builder()
                                     .regex("^" + ROUTE_PREDICT + "$")
                                     .methods(HttpMethod.POST)
                                     .build(),
                             new PredictionService(pairPredictionService));

            registerCatalogRoute(sb, catalogComponent);

            if (corsOrigin != null && !corsOrigin.isBlank()) {
                sb.decorator(CorsService.builder(corsOrigin)
                        .allowAllRequestHeaders(true)
                        .allowRequestMethods(
                                HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                                HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS, HttpMethod.HEAD)
                        .newDecorator());
            }

            GracefulServers.applyShutdownWindow(sb);

            Server server = sb.build();
            CatalogComponent ownedCatalogComponent = catalogComponent;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                loadShedder.markShuttingDown();   // readiness -> 503 so LBs drain this pod first
                server.stop().join();
                GracefulExecutors.shutdownGracefully(executor);
                if (registrar != null) {
                    registrar.close();
                }
                jedisPool.close();
                ownedCatalogComponent.close();
            }, "recsys-shutdown"));
            log.info("Starting RecSys serving API on port {}", port);
            server.start().join();
            server.blockUntilShutdown();
        } catch (Exception e) {
            if (catalogComponent != null) {
                catalogComponent.close();
            }
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

    static void registerCatalogRoute(ServerBuilder builder, CatalogComponent component) {
        builder.service(ROUTE_MOVIE_CATALOG, component.service());
    }
}
