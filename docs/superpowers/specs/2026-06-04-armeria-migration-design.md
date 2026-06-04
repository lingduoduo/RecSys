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

### Framework additions

| Action | Artifact |
|---|---|
| Add (test scope) | `com.linecorp.armeria:armeria-junit5:1.28.4` — provides `ServerExtension` |

No other new test frameworks. Mockito and JUnit 5 are already in use.

### Existing tests — no changes

There are no existing `*Servlet` test files; every existing test targets pure business logic. All 70+ existing test classes compile and pass unchanged:

- `GatewayAuthenticatorTest`, `GatewayRateLimiterTest`, `LlmResponseCacheTest`, `LlmTokenRateLimiterTest`, `MicroserviceRouteTest`, `RouteCircuitBreakerTest` — gateway logic, unaffected
- `OnlineRecommendationServiceTest`, `OnlineLoadShedderTest`, `RedisRateLimiterTest`, etc. — streaming logic, unaffected
- `ShardedRecordStoreIntegrationTest`, `ShardedRecordStoreWriteTest`, `ShardedRecordStoreReadTest`, `ShardedRecordStoreTtlTest` — store logic, unaffected
- All `modelbased/` tests — Spring Boot service, out of scope

### New integration tests

Three new test classes, one per server. Each uses `@RegisterExtension ServerExtension` to spin up the full Armeria server in-process on a random port, with Mockito stubs for Redis-dependent collaborators.

---

#### `RecSysServerIntegrationTest`
`src/test/java/com/recsys/serving/RecSysServerIntegrationTest.java`

Stubs: `DataManager`, `CandidateGenerator`, `ShardedTopKStore` (Mockito)

| Test | Route | Expected |
|---|---|---|
| health check | `GET /health` | 200 `{"status":"ok"}` |
| get movie by id | `GET /item?id=1` | 200 JSON movie object |
| get movie — unknown id | `GET /item?id=999` | 404 JSON error |
| get recommendation | `GET /getrecommendation?userId=1&k=5` | 200 JSON list |
| get recommendation — missing userId | `GET /getrecommendation` | 400 JSON error |
| similar movies | `GET /similar?id=1&k=3` | 200 JSON list |
| set embedding — valid body | `POST /setembedding` with JSON | 200 |
| set embedding — empty body | `POST /setembedding` empty | 400 JSON error |
| compat predict route | `POST /v1/models/recmodel:predict` | 200 |
| unknown route | `GET /unknown` | 404 |
| REST alias — movie | `GET /movie?id=1` | 200 (same as `/item`) |
| REST alias — recommendation | `GET /recommendation?userId=1&k=3` | 200 |

---

#### `OnlinePredictionServerIntegrationTest`
`src/test/java/com/recsys/streaming/OnlinePredictionServerIntegrationTest.java`

Stubs: `OnlineRecommendationService`, `OnlineServingMetricsService`, `ShardedRecordStore` (Mockito)

| Test | Route | Expected |
|---|---|---|
| health check | `GET /health` | 200 JSON metrics snapshot |
| online recommendation | `GET /online/recommendation?userId=1&k=5` | 200 JSON |
| online recommendation — missing userId | `GET /online/recommendation` | 400 JSON error |
| online features | `GET /online/features?userId=1` | 200 JSON |
| ops snapshot | `GET /online/ops` | 200 JSON |
| write shard record — valid | `POST /shards/records` with `{deviceId, type, eventId}` | 200 JSON with `seqNum`, `shardIndex` |
| write shard record — missing deviceId | `POST /shards/records` | 400 JSON error |
| write shard record — bad type | `POST /shards/records` with `type:"INVALID"` | 400 JSON error |
| read by device | `GET /shards/device?deviceId=user-1` | 200 JSON with records + cursor |
| read by shard | `GET /shards/shard?index=0` | 200 JSON |
| load shed | `GET /online/recommendation?userId=1` when load shedder saturated | 429 with `Retry-After` header |
| rate limit | excess requests to `/online/recommendation` | 429 with `Retry-After` header |

---

#### `GatewayServerIntegrationTest`
`src/test/java/com/recsys/microservice/GatewayServerIntegrationTest.java`

Two `ServerExtension` instances: one for the gateway, one acting as a fake upstream (returns canned JSON). `MicroserviceRoute.defaults()` is overridden to point all base URIs at the fake upstream's port.

| Test | Input | Expected |
|---|---|---|
| route to backend | `GET /api/recsys/health` | 200 forwarded from fake upstream |
| unmatched path | `GET /no-such-route` | 404 `{"error":"..."}` |
| circuit breaker open | request when `RouteCircuitBreaker` forced open | 503 `{"error":"..."}` |
| rate limited | excess requests beyond token bucket | 429 with `Retry-After` |
| auth required — missing key | `GET /api/recsys/health` without `X-Api-Key` when auth enabled | 401 |
| auth required — wrong key | wrong key value | 403 |
| gateway header injected | any proxied request | upstream receives `X-Gateway-Service: recsys-api-gateway` |
| hop-by-hop stripped | request with `Connection`, `Transfer-Encoding` headers | upstream does NOT receive those headers |
| SSE streaming | upstream returns `text/event-stream` chunked body | client receives stream chunks in order |
| LLM route proxied | `POST /llm/v1/chat/completions` | forwarded to LLM fake upstream |
| LLM token budget exceeded | `POST /llm/...` when token limiter exhausted | 429 |
| LLM cache hit | same request body sent twice | second response has `X-Cache: HIT`, upstream called only once |

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

---

## Out of Scope

- gRPC endpoints
- Armeria metrics/tracing integration (Micrometer, Zipkin)
- Replacing hand-rolled `RouteCircuitBreaker` with Armeria's `CircuitBreaker` decorator
- `ModelApplication` (Spring Boot)
