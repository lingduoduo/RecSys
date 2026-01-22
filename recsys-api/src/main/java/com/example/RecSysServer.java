package com.example;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.net.InetSocketAddress;

public class RecSysServer {

    private static final int DEFAULT_PORT = 6010;

    public static void main(String[] args) throws Exception {
        new RecSysServer().run();
    }

    public void run() throws Exception {
        InetSocketAddress inetAddress = new InetSocketAddress("0.0.0.0", DEFAULT_PORT);
        Server server = new Server(inetAddress);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        // Register endpoint(s)
        context.addServlet(new ServletHolder(new MovieService()), "/getmovie");
        context.addServlet(new ServletHolder(new UserService()), "/getuser");

        // Attach handler AFTER config
        server.setHandler(context);

        server.setStopAtShutdown(true);
        server.start();
        server.join();
    }
}
