package com.recsys.microservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LLM-optimized reverse proxy servlet. Handles a single {@link MicroserviceRoute} and adds:
 *
 * <ul>
 *   <li><b>Streaming passthrough</b> — detects {@code "stream":true} in the request body and
 *       pipes the SSE/chunked upstream response directly to the client without buffering.</li>
 *   <li><b>Retry-on-429</b> — when the LLM service returns 429, respects the {@code Retry-After}
 *       header and retries once (buffered mode only; streaming is surfaced immediately).</li>
 *   <li><b>Token-based rate limiting</b> — pre-checks the token budget using {@code max_tokens}
 *       from the request body before forwarding, rejecting with 429 when the budget is exhausted.</li>
 *   <li><b>Response caching</b> — caches non-streaming 200 responses by SHA-256 of the request
 *       body; cache hits set {@code X-Cache: HIT} and skip the upstream entirely.</li>
 *   <li><b>Circuit breaker</b> — shared with the gateway health endpoint; opens on repeated
 *       upstream 5xx / timeouts and fast-fails with 503 during the cooldown window.</li>
 * </ul>
 *
 * Env vars (in addition to those on {@link LlmTokenRateLimiter} and {@link LlmResponseCache}):
 *   LLM_TIMEOUT_MS              — per-request timeout in ms (default 120000)
 *   LLM_MAX_RETRY_WAIT_MS       — max Retry-After wait before giving up the 429 retry (default 30000)
 *   LLM_DEFAULT_TOKEN_ESTIMATE  — token estimate used when {@code max_tokens} is absent (default 1000)
 */
final class LlmProxyServlet extends HttpServlet {

    static final int DEFAULT_TIMEOUT_MS = 120_000;
    static final int DEFAULT_MAX_RETRY_WAIT_MS = 30_000;
    static final int DEFAULT_TOKEN_ESTIMATE = 1_000;

    private static final int SC_TOO_MANY_REQUESTS = 429;
    private static final int STREAM_BUF_SIZE = 8192;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade"
    );

    private final MicroserviceRoute route;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final RouteCircuitBreaker circuitBreaker;
    private final LlmTokenRateLimiter tokenRateLimiter;
    private final LlmResponseCache responseCache;
    private final int defaultTokenEstimate;
    private final long maxRetryWaitMs;

    LlmProxyServlet(MicroserviceRoute route,
                    HttpClient httpClient,
                    Duration requestTimeout,
                    RouteCircuitBreaker circuitBreaker,
                    LlmTokenRateLimiter tokenRateLimiter,
                    LlmResponseCache responseCache,
                    int defaultTokenEstimate,
                    long maxRetryWaitMs) {
        this.route = route;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.circuitBreaker = circuitBreaker;
        this.tokenRateLimiter = tokenRateLimiter;
        this.responseCache = responseCache;
        this.defaultTokenEstimate = defaultTokenEstimate;
        this.maxRetryWaitMs = maxRetryWaitMs;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String path = requestPath(request);
        URI target = route.rewrite(path, request.getQueryString());

        byte[] requestBody = shouldForwardBody(request)
                ? request.getInputStream().readAllBytes()
                : null;

        boolean streaming = requestBody != null && isStreamingRequest(requestBody);

        // Cache check (non-streaming only — streaming responses are not deterministic per call)
        if (!streaming && requestBody != null) {
            LlmResponseCache.Entry cached = responseCache.get(requestBody);
            if (cached != null) {
                writeCachedResponse(response, cached);
                return;
            }
        }

        // Token rate limit pre-check
        int estimatedTokens = requestBody != null
                ? extractMaxTokens(requestBody, defaultTokenEstimate)
                : defaultTokenEstimate;
        LlmTokenRateLimiter.Decision tokenDecision = tokenRateLimiter.tryAcquire(estimatedTokens);
        if (!tokenDecision.allowed()) {
            int retryAfterSec = Math.max(1, (int) Math.ceil(tokenDecision.retryAfter().toMillis() / 1000.0));
            response.setHeader("Retry-After", Integer.toString(retryAfterSec));
            response.setHeader("X-RateLimit-Limit", Integer.toString(tokenDecision.limit()));
            response.setHeader("X-RateLimit-Remaining", Integer.toString(tokenDecision.remaining()));
            GatewayProxyServlet.writeGatewayError(response, SC_TOO_MANY_REQUESTS,
                    route.name() + " token budget exhausted — retry after " + retryAfterSec + "s");
            return;
        }

        // Circuit breaker
        if (!circuitBreaker.tryAcquire()) {
            GatewayProxyServlet.writeGatewayError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    route.name() + " circuit open — LLM service unavailable, retry later");
            return;
        }

        HttpRequest upstreamRequest = buildUpstreamRequest(request, target, requestBody);

        if (streaming) {
            forwardStreaming(response, upstreamRequest);
        } else {
            forwardBuffered(response, upstreamRequest, requestBody);
        }
    }

    // ── Streaming path ──────────────────────────────────────────────────────────────────────────

    private void forwardStreaming(HttpServletResponse response, HttpRequest upstreamRequest)
            throws IOException {
        try {
            HttpResponse<InputStream> upstream = httpClient.send(
                    upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (upstream.statusCode() >= 500) {
                circuitBreaker.recordFailure();
            } else {
                circuitBreaker.recordSuccess();
            }

            response.setStatus(upstream.statusCode());
            copyHeaders(upstream.headers().map(), response);

            try (InputStream in = upstream.body()) {
                byte[] buf = new byte[STREAM_BUF_SIZE];
                int n;
                while ((n = in.read(buf)) != -1) {
                    response.getOutputStream().write(buf, 0, n);
                    response.getOutputStream().flush();
                }
            }

        } catch (HttpTimeoutException e) {
            circuitBreaker.recordFailure();
            GatewayProxyServlet.writeGatewayError(response, HttpServletResponse.SC_GATEWAY_TIMEOUT,
                    "LLM upstream timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            GatewayProxyServlet.writeGatewayError(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "interrupted while proxying LLM stream");
        }
    }

    // ── Buffered path ───────────────────────────────────────────────────────────────────────────

    private void forwardBuffered(HttpServletResponse response, HttpRequest upstreamRequest,
                                  byte[] requestBody) throws IOException {
        HttpResponse<byte[]> upstream;
        try {
            upstream = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            circuitBreaker.recordFailure();
            GatewayProxyServlet.writeGatewayError(response, HttpServletResponse.SC_GATEWAY_TIMEOUT,
                    "LLM upstream timeout");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            GatewayProxyServlet.writeGatewayError(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "interrupted while proxying LLM request");
            return;
        }

        // Retry once when the upstream itself is rate-limited (429 with Retry-After)
        if (upstream.statusCode() == SC_TOO_MANY_REQUESTS) {
            long waitMs = parseRetryAfterMs(upstream);
            if (waitMs > 0 && waitMs <= maxRetryWaitMs) {
                try {
                    Thread.sleep(waitMs);
                    upstream = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofByteArray());
                } catch (HttpTimeoutException e) {
                    circuitBreaker.recordFailure();
                    GatewayProxyServlet.writeGatewayError(response, HttpServletResponse.SC_GATEWAY_TIMEOUT,
                            "LLM upstream timeout on 429 retry");
                    return;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    GatewayProxyServlet.writeGatewayError(response, HttpServletResponse.SC_BAD_GATEWAY,
                            "interrupted during 429 retry");
                    return;
                }
            }
        }

        if (upstream.statusCode() >= 500) {
            circuitBreaker.recordFailure();
        } else {
            circuitBreaker.recordSuccess();
        }

        // Cache successful responses (status 200 only — avoid caching partial/error responses)
        if (upstream.statusCode() == 200 && requestBody != null) {
            responseCache.put(requestBody, upstream.statusCode(),
                    upstream.headers().map(), upstream.body());
        }

        response.setStatus(upstream.statusCode());
        copyHeaders(upstream.headers().map(), response);
        response.setHeader("X-Cache", "MISS");
        response.getOutputStream().write(upstream.body());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private HttpRequest buildUpstreamRequest(HttpServletRequest request, URI target,
                                             byte[] body) {
        HttpRequest.BodyPublisher publisher = body != null
                ? HttpRequest.BodyPublishers.ofByteArray(body)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(requestTimeout)
                .method(request.getMethod(), publisher);

        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (isHopByHop(name)) continue;
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                builder.header(name, values.nextElement());
            }
        }
        builder.header("X-Gateway-Service", "recsys-llm-gateway");
        String host = request.getHeader("Host");
        if (host != null && !host.isBlank()) {
            builder.header("X-Forwarded-Host", host);
        }
        builder.header("X-Forwarded-Proto", request.getScheme());
        return builder.build();
    }

    private static void writeCachedResponse(HttpServletResponse response,
                                             LlmResponseCache.Entry entry) throws IOException {
        response.setStatus(entry.status());
        copyHeaders(entry.headers(), response);
        response.setHeader("X-Cache", "HIT");
        response.getOutputStream().write(entry.body());
    }

    private static void copyHeaders(Map<String, List<String>> headers,
                                     HttpServletResponse response) {
        headers.forEach((name, values) -> {
            if (!isHopByHop(name)) {
                for (String v : values) response.addHeader(name, v);
            }
        });
    }

    private static boolean shouldForwardBody(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method)) {
            return false;
        }
        return request.getContentLengthLong() != 0;
    }

    private static boolean isHopByHop(String name) {
        return name != null && HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isBlank() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }

    /**
     * Returns true when the request body contains {@code "stream": true} (OpenAI-compatible format).
     * Parses the full body with Jackson so the field position does not matter.
     */
    static boolean isStreamingRequest(byte[] body) {
        if (body == null || body.length == 0) return false;
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode stream = root.get("stream");
            return stream != null && stream.isBoolean() && stream.booleanValue();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Reads {@code max_tokens} from the request JSON body as the token cost estimate.
     * Falls back to {@code defaultValue} when the field is absent or unparseable.
     */
    static int extractMaxTokens(byte[] body, int defaultValue) {
        if (body == null || body.length == 0) return defaultValue;
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode maxTokens = root.get("max_tokens");
            if (maxTokens != null && maxTokens.isInt()) {
                return Math.max(1, maxTokens.intValue());
            }
        } catch (Exception ignored) {
        }
        return defaultValue;
    }

    /**
     * Parses the {@code Retry-After} header (integer seconds) from a 429 response.
     * Returns 0 if absent or unparseable.
     */
    private static long parseRetryAfterMs(HttpResponse<?> resp) {
        String header = resp.headers().firstValue("Retry-After").orElse(null);
        if (header == null || header.isBlank()) return 0L;
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds > 0 ? seconds * 1000L : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
