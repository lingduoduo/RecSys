package com.recsys.microservice;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

public final class MicroserviceGatewayServer {
    private static final Logger log = LoggerFactory.getLogger(MicroserviceGatewayServer.class);
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 8010;

    private MicroserviceGatewayServer() {}

    public static void main(String[] args) throws Exception {
        int port = readIntEnv("GATEWAY_PORT", DEFAULT_PORT);
        int timeoutMs = readIntEnv("GATEWAY_TIMEOUT_MS", 3000);
        Duration timeout = Duration.ofMillis(timeoutMs);
        List<MicroserviceRoute> routes = MicroserviceRoute.defaults();

        Server server = new Server(new InetSocketAddress(DEFAULT_HOST, port));
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new GatewayHealthServlet(routes, timeout)), "/health");
        context.addServlet(new ServletHolder(new GatewayProxyServlet(routes, timeout)), "/*");
        server.setHandler(context);
        server.setStopAtShutdown(true);

        log.info("Starting RecSys API gateway on port {}", port);
        for (MicroserviceRoute route : routes) {
            log.info("Route {} {} -> {}", route.name(), route.prefix(), route.baseUri());
        }
        server.start();
        server.join();
    }

    private static int readIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid integer: " + value);
        }
    }
}
