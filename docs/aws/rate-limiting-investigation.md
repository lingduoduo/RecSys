# Rate Limiting Investigation

This service currently exposes `recsys-api-gateway` through an AWS Network Load Balancer
created from `k8s/base/api-gateway.yaml`. The application gateway is a Jetty reverse
proxy, and online serving already has an optional Redis-backed limiter controlled by
`ONLINE_REDIS_RATE_LIMIT_QPS`.

The Jetty gateway also supports an optional local token-bucket throttle:
`GATEWAY_RATE_LIMIT_RPS`, `GATEWAY_RATE_LIMIT_BURST`, and per-route overrides such as
`GATEWAY_RATE_LIMIT_MODEL_RPS`. This is per gateway pod, so it is useful as a local
safety rail but is not a replacement for API Gateway or Envoy global limits.

## Summary

Use layered protection:

1. Put AWS API Gateway in front of the NLB when the public API needs managed edge
   throttling, API keys, usage plans, request validation, authorizers, WAF attachment,
   and CloudWatch API metrics.
2. Use Envoy or Istio global rate limiting when the cluster needs one shared quota
   across gateway replicas, service replicas, paths, users, tenants, or JWT claims.
3. Keep service-local overload controls, such as the existing `RedisRateLimiter`,
   load shedding, readiness, and circuit breakers, as the last line of defense.

For this repo, API Gateway is the lowest-effort public-edge option because the current
Kubernetes service already publishes a single HTTP endpoint. Envoy/Istio is the better
long-term option if the platform is moving toward service-mesh ingress, per-route
policies, mTLS, or internal service-to-service limits.

## API Gateway Throttling

AWS API Gateway throttling is token-bucket based:

- `rateLimit` is the steady refill rate in requests per second.
- `burstLimit` is the bucket capacity and permits short spikes above the steady rate.
- Stage-level throttling applies to all methods in a REST API stage.
- Method-level overrides can narrow limits for expensive endpoints.
- Usage plans add per-client throttling and quotas when API keys are required.

Important caveat: AWS documents API Gateway throttling targets as best-effort, not hard
limits. They are good for smoothing traffic and discouraging abusive clients, but should
not be the only control for hard cost or backend protection.

Recommended edge defaults for this repo:

| Scope | Rate | Burst | Notes |
|---|---:|---:|---|
| Stage default | `1000 rps` | `2000` | Public edge safety rail; tune against load tests and AWS account quota. |
| `/api/model/*` | `200 rps` | `400` | Model inference is CPU and memory sensitive. |
| `/api/online/*` | `500 rps` | `1000` | Online serving already has Redis and load shedding controls. |
| `/api/catalog/*` | `1000 rps` | `2000` | Mostly read-heavy; adjust when Redis/MySQL becomes the bottleneck. |

Deploy shape:

```text
client -> API Gateway REST/HTTP API -> VPC Link -> internal NLB -> recsys-api-gateway
```

Operational notes:

- Prefer an internal NLB when API Gateway becomes the public entrypoint.
- Enable access logs and API metrics.
- Attach AWS WAF for IP reputation, request size, and coarse bot controls.
- Propagate a client identity key, such as API key, user id, tenant id, or JWT claim,
  if backend per-principal rate limiting is needed.

## Envoy / Istio Global Rate Limit

Envoy has two rate-limit modes:

- Local rate limit: each proxy enforces its own token bucket. This is fast and has no
  external dependency, but the effective total limit scales with replica count.
- Global rate limit: Envoy calls an external gRPC rate limit service, commonly backed
  by Redis, so all participating proxies share the same quota.

Istio exposes Envoy rate limiting through `EnvoyFilter`. The official Istio task warns
that `EnvoyFilter` exposes implementation details and requires caution during upgrades.
Envoy Gateway provides a higher-level `BackendTrafficPolicy` API for global rate limits,
which is easier to own if the platform can adopt Gateway API.

Recommended mesh defaults for this repo:

| Scope | Limit | Descriptor key |
|---|---:|---|
| All ingress traffic | `1000 rps` | Gateway / route |
| `/api/model/api/v1/recommend` | `200 rps` | Path |
| `/api/online/online/recommendation` | `500 rps` | Path |
| Authenticated users | Plan-specific | User id / API key / JWT claim |
| Anonymous clients | Low limit | Remote address, with trusted proxy handling |

Operational notes:

- Run the rate limit service with Redis or ElastiCache, not an in-memory store.
- Decide fail-open vs fail-closed per route. Public abuse controls can fail-closed;
  critical user traffic usually fails-open with local fallback.
- Add local Envoy token buckets to protect the global rate limit service from hot-path
  calls during bursts.
- Export 429 count, rate limit service latency, Redis latency, allowed/over-limit
  decisions, and fail-open count.

## Recommendation

Short term:

1. Add API Gateway in front of the existing NLB for public traffic.
2. Configure stage throttling plus method-level overrides for model and online routes.
3. Keep `ONLINE_REDIS_RATE_LIMIT_QPS` available for online serving because API Gateway
   throttling is best-effort and does not protect internal callers.

Medium term:

1. If service-mesh adoption is planned, evaluate Envoy Gateway first for Gateway API
   native global rate limiting.
2. Use Istio `EnvoyFilter` only when the cluster already standardizes on Istio ingress
   and accepts upgrade-sensitive filter configuration.
3. Move shared per-user, per-tenant, or per-route limits into Envoy global rate limit
   service backed by ElastiCache.

Do not replace the existing service-local controls. Edge and mesh limits reduce load
before requests reach Java, but service-local load shedding still handles dependency
slowness, Redis outages, and internal traffic.

## Sources

- AWS API Gateway `ThrottleSettings`: https://docs.aws.amazon.com/apigateway/latest/api/API_ThrottleSettings.html
- AWS API Gateway stage throttling: https://docs.aws.amazon.com/apigateway/latest/developerguide/set-up-stages.html
- Istio rate limits using Envoy: https://istio.io/latest/docs/tasks/policy-enforcement/rate-limit/
- Envoy HTTP rate limit filter: https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/rate_limit_filter.html
- Envoy Gateway global rate limit: https://gateway.envoyproxy.io/docs/tasks/traffic/global-rate-limit/
