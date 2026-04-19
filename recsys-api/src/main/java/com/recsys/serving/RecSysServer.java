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

    public static void main(String[] args) throws Exception {
        new RecSysServer().run();
    }

    public void run() throws Exception {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        JedisPool jedisPool = new JedisPool(redisHost, redisPort);
        DataManager dataManager = DataManager.getInstance();
        RedisEmbeddingStore embStore = new RedisEmbeddingStore(jedisPool, "i2vEmb");
        RedisTopKStore topkStore = new RedisTopKStore(jedisPool, "topk:");

        int port = DEFAULT_PORT;
        InetSocketAddress inetAddress = new InetSocketAddress("0.0.0.0", port);
        Server server = new Server(inetAddress);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setWelcomeFiles(new String[]{"index.html"});

        context.addServlet(new ServletHolder(new MovieService(dataManager)), "/getmovie");
        context.addServlet(new ServletHolder(new UserService(dataManager)), "/getuser");
        context.addServlet(new ServletHolder(new SimilarMovieService(embStore)), "/getsimilarmovie");
        context.addServlet(new ServletHolder(new RecommendationService(dataManager, topkStore)), "/getrecommendation");
        context.addServlet(new ServletHolder(new SetEmbeddingService(embStore)), "/setembedding");

        server.setHandler(context);
        server.setStopAtShutdown(true);
        server.start();
        server.join();
    }
}
