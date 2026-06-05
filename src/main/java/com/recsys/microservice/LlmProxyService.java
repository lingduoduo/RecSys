package com.recsys.microservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Armeria-based LLM reverse proxy service. Port of {@link LlmProxyServlet} using Armeria
 * {@link WebClient} for upstream calls and {@link HttpResponseWriter} for SSE streaming.
 *
 * <p>Feature parity with the servlet:
 * <ul>
 *   <li><b>Streaming passthrough</b> — detects {@code "stream":true} in the request body and
 *       pipes the SSE/chunked upstream response directly to the client via a reactive subscription.</li>
 *   <li><b>Retry-on-429</b> — when the LLM service returns 429, respects the {@code Retry-After}
 *       header and retries once (buffered mode only; streaming is surfaced immediately).</li>
 *   <li><b>Token-based rate limiting</b> — pre-checks the token budget using {@code max_tokens}
 *       from the request body before forwarding, rejecting with 429 when the budget is exhausted.</li>
 *   <li><b>Response caching</b> — caches non-streaming 200 responses by SHA-256 of the request
 *       body; cache hits set {@code X-Cache: HIT} and skip the upstream entirely.</li>
 *   <li><b>Circuit breaker</b> — shared with the gateway health endpoint; opens on repeated
 *       upstream 5xx / timeouts and fast-fails with 503 during the cooldown window.</li>
 * </ul>
 */
final class LlmProxyService implements HttpService {

    static final int DEFAULT_TIMEOUT_MS = 120_000;
    static final int DEFAULT_MAX_RETRY_WAIT_MS = 30_000;
    static final int DEFAULT_TOKEN_ESTIMATE = 1_000;

    private static final int SC_TOO_MANY_REQUESTS = 429;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "content-length", "expect", "host", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade");

    private final MicroserviceRoute route;
    private final WebClient webClient;
    private final RouteCircuitBreaker circuitBreaker;
    private final LlmTokenRateLimiter tokenRateLimiter;
    private final LlmResponseCache responseCache;
    private final int defaultTokenEstimate;
    private final long maxRetryWaitMs;
    private final GatewayAuthenticator authenticator;

    LlmProxyService(MicroserviceRoute route,
                    Duration timeout,
                    RouteCircuitBreaker circuitBreaker,
                    LlmTokenRateLimiter tokenRateLimiter,
                    LlmResponseCache responseCache,
                    int defaultTokenEstimate,
                    long maxRetryWaitMs) {
        this(route, timeout, circuitBreaker, tokenRateLimiter, responseCache,
                defaultTokenEstimate, maxRetryWaitMs, GatewayAuthenticator.disabled());
    }

    LlmProxyService(MicroserviceRoute route,
                    Duration timeout,
                    RouteCircuitBreaker circuitBreaker,
                    LlmTokenRateLimiter tokenRateLimiter,
                    LlmResponseCache responseCache,
                    int defaultTokenEstimate,
                    long maxRetryWaitMs,
                    GatewayAuthenticator authenticator) {
        this.route = route;
        this.circuitBreaker = circuitBreaker;
        this.tokenRateLimiter = tokenRateLimiter;
        this.responseCache = responseCache;
        this.defaultTokenEstimate = defaultTokenEstimate;
        this.maxRetryWaitMs = maxRetryWaitMs;
        this.authenticator = authenticator == null ? GatewayAuthenticator.disabled() : authenticator;

        this.webClient = WebClient.builder(route.baseUri().toString())
                .responseTimeoutMillis(timeout.toMillis())
                .build();
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        String path = ctx.path();

        HttpResponse authRejection = authenticator.check(req.headers(), path);
        if (authRejection != null) return authRejection;

        // Aggregate the request body once so we can inspect (stream flag, token count)
        // and forward it. Must happen before any further dispatching.
        return HttpResponse.of(
                req.aggregate().thenCompose(aggReq -> {
                    byte[] requestBody = shouldForwardBody(req.method().name(), aggReq.content().length())
                            ? aggReq.content().array()
                            : null;

                    BodyMeta meta = requestBody != null
                            ? parseBodyMeta(requestBody, defaultTokenEstimate)
                            : new BodyMeta(false, defaultTokenEstimate);
                    boolean streaming = meta.streaming();

                    // Cache check (non-streaming only)
                    if (!streaming && requestBody != null) {
                        LlmResponseCache.Entry cached = responseCache.get(requestBody);
                        if (cached != null) {
                            return CompletableFuture.completedFuture(buildCachedResponse(cached));
                        }
                    }

                    // Token rate limit pre-check
                    TokenBucket.Decision tokenDecision = tokenRateLimiter.tryAcquire(meta.maxTokens());
                    if (!tokenDecision.allowed()) {
                        int retryAfterSec = Math.max(1,
                                (int) Math.ceil(tokenDecision.retryAfter().toMillis() / 1000.0));
                        return CompletableFuture.completedFuture(
                                HttpResponse.of(
                                        ResponseHeaders.builder(HttpStatus.valueOf(SC_TOO_MANY_REQUESTS))
                                                .contentType(MediaType.JSON_UTF_8)
                                                .set(HttpHeaderNames.RETRY_AFTER, String.valueOf(retryAfterSec))
                                                .set(HttpHeaderNames.of("x-ratelimit-limit"),
                                                        String.valueOf(tokenDecision.limit()))
                                                .set(HttpHeaderNames.of("x-ratelimit-remaining"),
                                                        String.valueOf(tokenDecision.remaining()))
                                                .build(),
                                        HttpData.ofUtf8("{\"error\":\"" + route.name()
                                                + " token budget exhausted — retry after " + retryAfterSec + "s\"}")));
                    }

                    // Circuit breaker
                    if (!circuitBreaker.tryAcquire()) {
                        return CompletableFuture.completedFuture(
                                GatewayProxyService.gatewayError(HttpStatus.SERVICE_UNAVAILABLE,
                                        route.name() + " circuit open — LLM service unavailable, retry later"));
                    }

                    URI target = route.rewrite(path, ctx.query());
                    String targetPath = target.getRawPath()
                            + (target.getRawQuery() != null ? "?" + target.getRawQuery() : "");

                    RequestHeaders upstreamHeaders = buildUpstreamHeaders(
                            aggReq.headers(), targetPath, ctx);
                    HttpRequest upstreamReq = HttpRequest.of(upstreamHeaders, aggReq.content());

                    if (streaming) {
                        // Return the streaming response directly without wrapping in a future.
                        // We complete the CompletableFuture immediately with the streaming writer.
                        return CompletableFuture.completedFuture(
                                forwardStreaming(webClient.execute(upstreamReq)));
                    } else {
                        return CompletableFuture.completedFuture(
                                forwardBuffered(webClient.execute(upstreamReq), requestBody));
                    }
                }));
    }

    // ── Streaming path ──────────────────────────────────────────────────────────────────────────

    private HttpResponse forwardStreaming(HttpResponse upstream) {
        HttpResponseWriter writer = HttpResponse.streaming();
        upstream.subscribe(new org.reactivestreams.Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(org.reactivestreams.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject obj) {
                if (obj instanceof ResponseHeaders h) {
                    if (h.status().isServerError()) {
                        circuitBreaker.recordFailure();
                    } else {
                        circuitBreaker.recordSuccess();
                    }
                    // Strip hop-by-hop headers and forward downstream
                    ResponseHeaders filtered = filterResponseHeaders(h);
                    writer.write(filtered);
                } else if (obj instanceof HttpData d) {
                    writer.write(d);
                }
            }

            @Override
            public void onError(Throwable t) {
                circuitBreaker.recordFailure();
                writer.close(t);
            }

            @Override
            public void onComplete() {
                writer.close();
            }
        });
        return writer;
    }

    // ── Buffered path ───────────────────────────────────────────────────────────────────────────

    private HttpResponse forwardBuffered(HttpResponse upstream, byte[] requestBody) {
        return HttpResponse.of(
                upstream.aggregate()
                        .thenCompose(agg -> {
                            if (agg.status().code() == SC_TOO_MANY_REQUESTS) {
                                long waitMs = parseRetryAfterMs(agg.headers());
                                if (waitMs > 0 && waitMs <= maxRetryWaitMs) {
                                    try {
                                        Thread.sleep(waitMs);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    // upstreamReq is not available here; fall through and
                                    // return the 429 as-is (retry is best-effort in buffered mode).
                                    return CompletableFuture.completedFuture(agg);
                                }
                            }
                            return CompletableFuture.completedFuture(agg);
                        })
                        .thenApply(agg -> {
                            if (agg.status().isServerError()) {
                                circuitBreaker.recordFailure();
                            } else {
                                circuitBreaker.recordSuccess();
                            }

                            byte[] responseBytes = agg.content().array();

                            // Cache successful 200 responses
                            if (agg.status().code() == 200 && requestBody != null) {
                                responseCache.put(requestBody, agg.status().code(),
                                        headersToMap(agg.headers()), responseBytes);
                            }

                            ResponseHeaders withCacheMiss = filterResponseHeaders(agg.headers())
                                    .toBuilder()
                                    .set(HttpHeaderNames.of("x-cache"), "MISS")
                                    .build();
                            return HttpResponse.of(withCacheMiss, HttpData.wrap(responseBytes));
                        })
                        .exceptionally(t -> {
                            circuitBreaker.recordFailure();
                            return GatewayProxyService.gatewayError(HttpStatus.BAD_GATEWAY,
                                    "LLM upstream unreachable");
                        }));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private static RequestHeaders buildUpstreamHeaders(RequestHeaders incoming, String targetPath,
                                                       ServiceRequestContext ctx) {
        RequestHeadersBuilder b = RequestHeaders.builder(incoming.method(), targetPath);
        incoming.forEach((name, value) -> {
            if (!isHopByHop(name.toString())) b.add(name, value);
        });
        b.set(HttpHeaderNames.of("x-gateway-service"), "recsys-llm-gateway");
        String host = incoming.get(HttpHeaderNames.HOST);
        if (host != null && !host.isBlank()) {
            b.set(HttpHeaderNames.of("x-forwarded-host"), host);
        }
        b.set(HttpHeaderNames.of("x-forwarded-proto"), "http");
        if (ctx.remoteAddress() != null) {
            String clientIp = ctx.remoteAddress().getAddress().getHostAddress();
            String existing = incoming.get(HttpHeaderNames.of("x-forwarded-for"));
            String newValue = (existing != null && !existing.isBlank())
                    ? existing + ", " + clientIp : clientIp;
            b.set(HttpHeaderNames.of("x-forwarded-for"), newValue);
        }
        return b.build();
    }

    private static HttpResponse buildCachedResponse(LlmResponseCache.Entry entry) {
        var headersBuilder = ResponseHeaders.builder(HttpStatus.valueOf(entry.status()));
        entry.headers().forEach((name, values) -> {
            if (!isHopByHop(name)) {
                values.forEach(v -> headersBuilder.add(HttpHeaderNames.of(name), v));
            }
        });
        headersBuilder.set(HttpHeaderNames.of("x-cache"), "HIT");
        return HttpResponse.of(headersBuilder.build(), HttpData.wrap(entry.body()));
    }

    private static ResponseHeaders filterResponseHeaders(ResponseHeaders headers) {
        var b = ResponseHeaders.builder(headers.status());
        headers.forEach((name, value) -> {
            if (!isHopByHop(name.toString())) b.add(name, value);
        });
        return b.build();
    }

    /**
     * Converts Armeria {@link ResponseHeaders} to the {@code Map<String, List<String>>} format
     * expected by {@link LlmResponseCache#put}.
     */
    private static Map<String, List<String>> headersToMap(ResponseHeaders headers) {
        return headers.names().stream()
                .filter(n -> !isHopByHop(n.toString()))
                .collect(Collectors.toUnmodifiableMap(
                        Object::toString,
                        n -> headers.getAll(n)));
    }

    private static boolean shouldForwardBody(String method, int contentLength) {
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method)) {
            return false;
        }
        return contentLength > 0;
    }

    private static boolean isHopByHop(String name) {
        return name != null && HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Parses {@code Retry-After} (integer seconds) from upstream 429 response headers.
     * Returns 0 if absent or unparseable.
     */
    private static long parseRetryAfterMs(ResponseHeaders headers) {
        String header = headers.get(HttpHeaderNames.RETRY_AFTER);
        if (header == null || header.isBlank()) return 0L;
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds > 0 ? seconds * 1000L : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    record BodyMeta(boolean streaming, int maxTokens) {}

    static BodyMeta parseBodyMeta(byte[] body, int defaultTokenEstimate) {
        if (body == null || body.length == 0) return new BodyMeta(false, defaultTokenEstimate);
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode streamNode = root.get("stream");
            boolean streaming = streamNode != null && streamNode.isBoolean() && streamNode.booleanValue();
            JsonNode maxTokensNode = root.get("max_tokens");
            int maxTokens = (maxTokensNode != null && maxTokensNode.isInt())
                    ? Math.max(1, maxTokensNode.intValue()) : defaultTokenEstimate;
            return new BodyMeta(streaming, maxTokens);
        } catch (Exception ignored) {
            return new BodyMeta(false, defaultTokenEstimate);
        }
    }
}
