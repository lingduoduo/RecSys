# Armeria Migration Design

**Date:** 2026-06-04
**Branch:** feat/armeria-migration (to be created)
**Scope:** Replace Jetty 11 with Armeria 1.30.1 across all three non-Spring services

---

## Background

The three hand-rolled Jetty servers (`RecSysServer`, `OnlinePredictionServer`, `MicroserviceGatewayServer`) use raw Jakarta EE servlets. Armeria provides HTTP/2, native async, built-in `WebClient`, streaming SSE support, and CORS/retry/circuit-breaker decorators — unlocking features that would otherwise require additional libraries.

`ModelApplication` (Spring Boot) is out of scope.

---

## Approach

**1:1 class mapping (`AbstractHttpService` subclasses), fully async.**

Each `*Servlet` becomes a `*Service extends BaseApiService`. Handler bodies run on `ctx.blockingTaskExecutor()` to offload Redis and ObjectMapper calls without blocking the Netty event loop. The gateway proxy replaces `java.net.HttpClient` with Armeria `WebClient`.

---

## Dependency Changes

| Action | Artifact |
|---|---|
| Remove | `org.eclipse.jetty:jetty-server:11.0.18` |
| Remove | `org.eclipse.jetty:jetty-servlet:11.0.18` |
| Add | `com.linecorp.armeria:armeria:1.28.4` (verify latest stable on Maven Central) |

`jakarta.servlet.*` imports are removed from all migrated files. Spring Boot's own servlet dependencies are unaffected.

---

## Base Class: `BaseApiService`

Replaces `BaseApiServlet extends HttpServlet` in `com.recsys.serving`.

`ApiServlet` in `com.recsys.streaming` becomes a thin subclass of `BaseApiService` (as it is today relative to `BaseApiServlet`).

### Helper method translation

| Before | After |
|---|---|
| `prepareJson(response)` | Dropped — content-type set inline on each returned `HttpResponse` |
| `writeJson(resp, status, obj)` | `HttpResponse.of(HttpStatus.valueOf(status), MediaType.JSON_UTF_8, mapper.writeValueAsBytes(obj))` |
| `writeError(resp, status, msg)` | Same pattern; returns `HttpResponse` |
| `request.getParameter("x")` | `ctx.queryParam("x")` |
| `request.getInputStream()` | `req.aggregate().thenApply(agg -> agg.content().toInputStream())` |
| CORS header per-response | `CorsService` decorator applied once at server build time, enabled only when `CORS_ALLOWED_ORIGIN` env var is set |

`BadRequestException` is unchanged — still a package-private `RuntimeException`; caught inside `exceptionally()` on the future rather than in a per-handler try/catch.

---

## Handler Pattern

All 14 handler classes follow the same async pattern:

```java
@Override
public HttpResponse get(ServiceRequestContext ctx, HttpRequest req) {
    return HttpResponse.from(
        CompletableFuture.supplyAsync(() -> {
            try {
                int userId = requiredIntParam(ctx, "userId");
                User u = dataManager.getUser(userId);   // blocking — runs on blockingTaskExecutor
                return writeJson(HttpStatus.OK, u);
            } catch (BadRequestException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }, ctx.blockingTaskExecutor())
    );
}
```

POST handlers aggregate the request body before dispatching:

```java
@Override
public HttpResponse post(ServiceRequestContext ctx, HttpRequest req) {
    return HttpResponse.from(
        req.aggregate().thenApplyAsync(agg -> {
            try {
                Body body = readJsonBody(agg, Body.class);
                // ...
                return writeJson(HttpStatus.OK, result);
            } catch (BadRequestException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }, ctx.blockingTaskExecutor())
    );
}
```

### Wildcard path (`/shards/*`)

`ShardedRecordServlet`'s `/shards/*` maps to:

```java
serverBuilder.service(Route.builder().pathPrefix("/shards/").build(), new ShardedRecordService(store));
```

Sub-path dispatch (`/records`, `/device`, `/shard`) uses `ctx.path()` string matching, replacing `request.getPathInfo()`.

---

## Server Bootstrap

Each `main()` replaces the Jetty `Server` + `ServletContextHandler` with an Armeria `ServerBuilder`:

```java
// Before
Server server = new Server(new InetSocketAddress(host, port));
ServletContextHandler context = new ServletContextHandler();
context.addServlet(new ServletHolder(new FooServlet(deps)), "/foo");
server.setHandler(context);
server.setStopAtShutdown(true);
server.start();
server.join();

// After
Server server = Server.builder()
    .http(port)
    .service("/foo", new FooService(deps))
    .build();
server.start().join();
```

Graceful shutdown: Armeria's `server.stop()` is registered via a JVM shutdown hook (equivalent to `setStopAtShutdown(true)`).

---

## Gateway Proxy

### `GatewayProxyServlet` → `GatewayProxyService`

`java.net.HttpClient` is replaced by one Armeria `WebClient` built per route's `baseUri`. Proxy flow:

```
inbound HttpRequest
  → auth check (GatewayAuthenticator — unchanged)
  → route match (MicroserviceRouteTable — unchanged)
  → rate limit check (GatewayRateLimiter — unchanged)
  → circuit breaker check (RouteCircuitBreaker — unchanged)
  → WebClient.execute(remapped request)
  → pipe HttpResponse directly to caller
```

The existing `RouteCircuitBreaker`, `GatewayRateLimiter`, and `GatewayAuthenticator` keep all internal logic unchanged. Only the transport layer swaps.

**Retry:** The `Thread.sleep(50)` retry loop is replaced by a `RetryingClient` decorator on the `WebClient`:

```java
WebClient.builder(baseUri)
    .decorator(RetryingClient.builder(RetryRule.onException(IOException.class))
        .maxTotalAttempts(2)
        .responseTimeoutMillisForEachAttempt(timeoutMs)
        .build())
    .build();
```

**Header forwarding:** Hop-by-hop header filtering (`HOP_BY_HOP_HEADERS` set) is unchanged; applied when building the upstream `RequestHeaders`.

### `LlmProxyServlet` → `LlmProxyService`

SSE/chunked streaming replaces the current `InputStream` pipe loop:

```java
HttpResponseWriter writer = HttpResponse.streaming();
webClient.execute(upstreamRequest)
    .subscribe(obj -> {
        if (obj instanceof HttpHeaders h) writer.write(h);
        else if (obj instanceof HttpData d) writer.write(d);
    }, writer::close, writer::close);
return writer;
```

This eliminates the 8 KB `ByteArrayOutputStream` buffer loop. Token-rate-limiting, cache-hit checks, and retry-on-429 logic remain before the upstream call, unchanged.

A separate `WebClient` with `LLM_TIMEOUT_MS` is kept for LLM routes (same rationale as today — LLM latency must not block the shared pool).

### Route registration order

Armeria resolves routes by specificity automatically. LLM routes (`/llm/*`, `/llm-explanation/*`) are registered before the catch-all `/**` for clarity, but ordering is not load-bearing.

---

## CORS

Conditional at server build time:

```java
String corsOrigin = System.getenv("CORS_ALLOWED_ORIGIN");
if (corsOrigin != null && !corsOrigin.isBlank()) {
    serverBuilder.decorator(
        CorsService.builder(corsOrigin)
            .allowAllRequestHeaders(true)
            .allowAllRequestMethods()
            .newDecorator()
    );
}
```

---

## Testing

- Existing unit tests for business logic (`RecommendationServiceTest`, `ShardedRecordStoreIntegrationTest`, etc.) are unaffected — they don't touch the HTTP layer.
- `GatewayAuthenticatorTest` and `MicroserviceRouteTest` test pure logic; unaffected.
- New integration tests for the Armeria servers can use Armeria's `ServerExtension` JUnit 5 rule to spin up the full server in-process.

---

## File Inventory

### Deleted (10 files)
- `src/main/java/com/recsys/serving/BaseApiServlet.java`
- `src/main/java/com/recsys/streaming/ApiServlet.java`
- `src/main/java/com/recsys/streaming/OnlineHealthServlet.java`
- `src/main/java/com/recsys/streaming/OnlineFeaturesServlet.java`
- `src/main/java/com/recsys/streaming/OnlinePredictionServlet.java`
- `src/main/java/com/recsys/streaming/OnlineOpsServlet.java`
- `src/main/java/com/recsys/streaming/ShardedRecordServlet.java`
- `src/main/java/com/recsys/microservice/GatewayProxyServlet.java`
- `src/main/java/com/recsys/microservice/LlmProxyServlet.java`
- `src/main/java/com/recsys/microservice/GatewayHealthServlet.java`

### Added
| New file | Replaces |
|---|---|
| `BaseApiService` (serving) | `BaseApiServlet` |
| `ApiService` (streaming) | `ApiServlet` |
| `OnlineHealthService` | `OnlineHealthServlet` |
| `OnlineFeaturesService` | `OnlineFeaturesServlet` |
| `OnlinePredictionService` | `OnlinePredictionServlet` |
| `OnlineOpsService` | `OnlineOpsServlet` |
| `ShardedRecordService` | `ShardedRecordServlet` |
| `GatewayProxyService` | `GatewayProxyServlet` |
| `LlmProxyService` | `LlmProxyServlet` |
| `GatewayHealthService` | `GatewayHealthServlet` |

### Modified in-place (extend `BaseApiServlet` today; will extend `BaseApiService`)
`HealthService`, `MovieService`, `UserService`, `RecommendationService`, `SimilarMovieService`, `SetEmbeddingService`, `PredictionService` — names unchanged, superclass and handler signatures updated.

Server entry points (`RecSysServer`, `OnlinePredictionServer`, `MicroserviceGatewayServer`) — `main()` bootstrap rewritten, business logic unchanged.

Server entry points (`RecSysServer`, `OnlinePredictionServer`, `MicroserviceGatewayServer`) are modified in-place.

---

## Out of Scope

- gRPC endpoints
- Armeria metrics/tracing integration (Micrometer, Zipkin)
- Replacing hand-rolled `RouteCircuitBreaker` with Armeria's `CircuitBreaker` decorator
- `ModelApplication` (Spring Boot)
