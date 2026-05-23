package com.recsys.microservice;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class GatewayProxyServlet extends HttpServlet {
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    );

    private final List<MicroserviceRoute> routes;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    GatewayProxyServlet(List<MicroserviceRoute> routes,
                        Duration requestTimeout) {
        this.routes = List.copyOf(routes);
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = requestPath(request);
        MicroserviceRoute route = MicroserviceRoute.match(routes, path);
        if (route == null) {
            writeGatewayError(response, HttpServletResponse.SC_NOT_FOUND,
                    "no microservice route matches " + path);
            return;
        }

        URI target = route.rewrite(path, request.getQueryString());
        try {
            HttpResponse<byte[]> upstream = httpClient.send(
                    buildUpstreamRequest(request, target),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            writeUpstreamResponse(response, upstream);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeGatewayError(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "interrupted while proxying " + route.name());
        } catch (RuntimeException e) {
            writeGatewayError(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "failed to proxy " + route.name() + ": " + e.getMessage());
        }
    }

    private HttpRequest buildUpstreamRequest(HttpServletRequest request, URI target) throws IOException {
        HttpRequest.BodyPublisher bodyPublisher = shouldForwardBody(request)
                ? HttpRequest.BodyPublishers.ofByteArray(request.getInputStream().readAllBytes())
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(requestTimeout)
                .method(request.getMethod(), bodyPublisher);

        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (isHopByHop(headerName)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(headerName);
            while (values.hasMoreElements()) {
                builder.header(headerName, values.nextElement());
            }
        }
        builder.header("X-Gateway-Service", "recsys-api-gateway");
        String host = request.getHeader("Host");
        if (host != null && !host.isBlank()) {
            builder.header("X-Forwarded-Host", host);
        }
        builder.header("X-Forwarded-Proto", request.getScheme());
        return builder.build();
    }

    private static boolean shouldForwardBody(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
            return false;
        }
        return request.getContentLengthLong() != 0;
    }

    private static void writeUpstreamResponse(HttpServletResponse response, HttpResponse<byte[]> upstream)
            throws IOException {
        response.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((name, values) -> {
            if (!isHopByHop(name)) {
                for (String value : values) {
                    response.addHeader(name, value);
                }
            }
        });
        response.getOutputStream().write(upstream.body());
    }

    private static boolean isHopByHop(String headerName) {
        return headerName != null && HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    static void writeGatewayError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().printf("{\"error\":\"%s\"}%n", escapeJson(message));
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
