# SSE Streaming in Recsys-Backend-Service

An investigation of how Server-Sent Events (SSE) streaming works in the system:
where it lives, how the streaming request lifecycle differs from buffered
requests, and how it interacts with the gateway's resilience, security, and
caching layers.

## The big picture

The system uses **no raw WebSockets**. The only client-facing streaming path is
**SSE / chunked passthrough in the LLM proxy** — the API Gateway
(`MicroserviceGatewayServer`) reverse-proxies an upstream LLM endpoint (Ollama or
an OpenAI-compatible service) and, when the client requests a token stream,
pipes the upstream's `text/event-stream` response straight through to the client
frame-by-frame. SSE is one-way (server → client), which is exactly what an LLM
token stream needs; nothing here requires a bidirectional socket.

Everything else in the system is either request/response HTTP or *internal*
server-to-server streaming (Redis Streams `sr:stream:<shard>`, the
Kafka → Flink → Redis feature pipeline) that never reaches a browser as a
socket.

## 1. Where SSE lives

- **Service:** [LlmProxyService.java](../../src/main/java/com/recsys/application/gateway/LlmProxyService.java)
  — an Armeria `HttpService` that reverse-proxies the LLM route(s).
- **Routes** (opt-in; only registered when the env var is set —
  [MicroserviceRoute.java:40-41](../../src/main/java/com/recsys/application/gateway/MicroserviceRoute.java#L40-L41)):
  - `llm-explanation` → prefix `/api/explanations`, `LLM_EXPLANATION_SERVICE_URL`
  - `llm` → prefix `/api/llm`, `LLM_SERVICE_URL`
- **Wiring:** the gateway splits LLM routes out from regular routes
  (`LLM_ROUTE_NAMES = {"llm", "llm-explanation"}`) and gives them a **dedicated,
  tuned `ClientFactory` and longer timeouts** so slow inference does not block
  the shared proxy pool ([MicroserviceGatewayServer.java:67-72, 148-162](../../src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java#L67-L72)).
  LLM routes are registered **before** the catch-all proxy so Armeria's
  longest-prefix match picks them first.

## 2. The streaming vs. buffered decision

Every LLM request is aggregated once so the proxy can inspect the body, then
dispatched down one of two paths based on a single flag
([LlmProxyService.java:148-203](../../src/main/java/com/recsys/application/gateway/LlmProxyService.java#L148-L203)):

```
stream:true  in JSON body  → forwardStreaming()   (SSE passthrough)
stream:false / absent      → forwardBuffered()    (aggregate + cache + retry)
```

`parseBodyMeta` ([LlmProxyService.java:400-413](../../src/main/java/com/recsys/application/gateway/LlmProxyService.java#L400-L413))
reads `"stream"` (boolean) and `"max_tokens"` (int) from the request JSON; a
malformed body falls back to non-streaming with a default token estimate.

## 3. The SSE streaming lifecycle

`forwardStreaming` ([LlmProxyService.java:209-245](../../src/main/java/com/recsys/application/gateway/LlmProxyService.java#L209-L245))
is a **reactive-streams passthrough** — it never buffers the body:

1. Create an Armeria `HttpResponseWriter` via `HttpResponse.streaming()` and
   return it to the client immediately (before the upstream has responded).
2. Subscribe to the upstream `HttpResponse` with `request(Long.MAX_VALUE)`
   (unbounded demand — the client's TCP backpressure flows through Armeria).
3. On each upstream `HttpObject`:
   - `ResponseHeaders` → record circuit-breaker success/failure by status,
     strip hop-by-hop headers, and `writer.write(filtered)`.
   - `HttpData` (an SSE chunk / `data:` frame) → `writer.write(d)` straight to
     the client.
4. `onError` → `circuitBreaker.recordFailure()` + `writer.close(t)`.
5. `onComplete` → `writer.close()`.

Key property: the client sees the first token as soon as the upstream emits it;
there is no aggregation, no size cap on the stream, and the `text/event-stream`
content type is preserved from the upstream headers.

## 4. Streaming vs. buffered: feature matrix

The two paths deliberately differ — streaming trades away caching and retry for
immediacy:

| Concern | Streaming (`forwardStreaming`) | Buffered (`forwardBuffered`) |
|---|---|---|
| Response delivery | Frame-by-frame passthrough | Aggregated, then sent whole |
| Response cache | **Skipped** — never cached | 200s cached by SHA-256 of body (`X-Cache: HIT/MISS`) |
| Retry-on-429 | **No** — surfaced to client immediately | Retries once, respects `Retry-After` (≤ `LLM_MAX_RETRY_WAIT_MS`) |
| Token rate-limit pre-check | Yes (`max_tokens` before forwarding) | Yes |
| Circuit breaker | Recorded per-frame on headers/error | Recorded on aggregate status/exception |
| Upstream-unreachable handling | `writer.close(t)` mid-stream | `502 Bad Gateway` "LLM upstream unreachable" |

The class doc calls this out explicitly: *"retries once (buffered mode only;
streaming is surfaced immediately)"* and *"caches non-streaming 200 responses."*

## 5. Cross-cutting concerns applied to SSE

**Security / identity.** `buildUpstreamHeaders`
([LlmProxyService.java:308-334](../../src/main/java/com/recsys/application/gateway/LlmProxyService.java#L308-L334))
runs on both paths: it strips client-spoofed `x-authenticated-*` identity
headers, strips gateway-consumed credentials (`authorization`, `x-api-key`, the
CloudFront `x-origin-secret`), injects the authenticated `GatewayPrincipal`'s
identity headers, and adds `x-forwarded-for/-host/-proto`. The gateway is the
sole identity authority; none of its credentials reach the LLM upstream. This is
the only behavior with dedicated tests
([LlmProxyServiceTest.java](../../src/test/java/com/recsys/application/gateway/LlmProxyServiceTest.java)).

**Token budget.** Before forwarding (streaming or not), `LlmTokenRateLimiter`
pre-checks the `max_tokens` budget and rejects with `429` +
`Retry-After`/`x-ratelimit-*` headers when exhausted.

**Circuit breaker.** Shared with the gateway health endpoint; opens on repeated
upstream 5xx/timeouts and fast-fails new requests with `503` during cooldown.
On the streaming path it observes the upstream *response headers* and any
mid-stream `onError`.

**Edge caching.** The gateway proxy path forces `Cache-Control: no-store`
([GatewayProxyService.java:68-73](../../src/main/java/com/recsys/application/gateway/GatewayProxyService.java#L68-L73))
so CloudFront never pins LLM responses (its 10 s default error-cache TTL would
otherwise cache a transient failure). LLM routes are POST-only and are not in
the CDN cache behaviors.

## 6. Connection tuning (why SSE stays alive)

The dedicated LLM `ClientFactory`
([MicroserviceGatewayServer.java:213-222](../../src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java#L213-L222))
is what keeps a long, slow token stream healthy:

| Env var | Default | Purpose |
|---|---|---|
| `LLM_TIMEOUT_MS` | 120 000 (120 s) | WebClient `responseTimeoutMillis` — LLM inference is slow |
| `LLM_CONNECT_TIMEOUT_MS` | 2 000 | Upstream connect timeout |
| `LLM_IDLE_TIMEOUT_MS` | 60 000 | Idle-connection reaper |
| `LLM_PING_INTERVAL_MS` | 20 000 | HTTP/2 keepalive PING — must stay **below** the idle timeout so a quiet stream isn't reaped |
| `LLM_MAX_RETRY_WAIT_MS` | 30 000 | Cap on honored `Retry-After` (buffered only) |
| `LLM_DEFAULT_TOKEN_ESTIMATE` | 1 000 | Token budget when `max_tokens` is absent |

The HTTP/2 PING (`pingIntervalMillis`) — **not** a WebSocket ping — is what holds
the connection open across gaps between token frames.

## 7. Sharp edges worth flagging

1. **Server-side request timeout is not cleared for streaming responses.** The
   gateway builds its server with `Server.builder().http(port)` and never
   overrides the Armeria server request timeout (default **10 s**) nor calls
   `ctx.clearRequestTimeout()` on the streaming path. Only the *client-side*
   `responseTimeoutMillis` (120 s) and idle timeout are tuned. If Armeria's
   request timeout applies to stream *completion* (time from request start until
   `writer.close()`), an LLM stream that runs longer than 10 s could be
   truncated. **Worth verifying with an integration test** — current tests cover
   only header handling, not stream lifetime.
2. **Streaming has no retry and no cache.** A `429` or `5xx` that arrives after
   the upstream headers are written is surfaced mid-stream; the client must
   handle a partial/failed stream itself. This is intentional but asymmetric
   with the buffered path.
3. **Token pre-check uses the client-declared `max_tokens`.** The budget is
   debited on the *request's* `max_tokens`, not on actual tokens streamed, so a
   client that under-declares `max_tokens` can under-pay its budget on the
   streaming path (no post-hoc reconciliation of streamed token count).
4. **No SSE-specific keepalive/heartbeat frame.** Liveness relies on the HTTP/2
   PING interval, not on SSE comment frames (`: keep-alive\n\n`); intermediaries
   that don't honor HTTP/2 PING could still idle-close a very slow stream.

## Summary

SSE streaming is a thin, well-factored reverse-proxy passthrough scoped entirely
to the LLM gateway. It reuses the gateway's auth, token-budget, circuit-breaker,
and header-sanitization machinery, and gets its own long-timeout HTTP/2 client so
slow inference streams stay alive — while deliberately opting out of caching and
retry that only make sense for buffered responses. The main thing to validate is
that the server-side request timeout does not cap long streams.
