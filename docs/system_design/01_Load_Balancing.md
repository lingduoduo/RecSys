# Load Balancing in Recsys-Backend-Service

An investigation of how traffic is spread across healthy, non-overloaded instances:
the real production stack (AWS ALB → kube-proxy / topology-aware routing → Armeria
client-side health-checked groups), the **capacity-weight feedback signal** that lets
an instance tell the balancer how loaded it is, and an in-memory L7
`ApplicationLoadBalancer` model used as a routing reference. The theme is
**capacity-aware balancing** — not just "is it up?" but "how much more can it take?"

## The big picture

Load balancing here is three cooperating layers, only two of which are on the
production data path:

| Layer | What it is | Prod or reference |
|---|---|---|
| Edge + cluster LB | AWS ALB Ingress → kube-proxy ClusterIP → topology-aware routing | **production** |
| Client-side LB | Armeria `UpstreamEndpointGroups` (health-checked, per-backend) | **production** |
| Capacity-weight feedback | `X-Capacity-Weight` / `suggestedWeight` from the load shedders | **production** (signal) |
| `ApplicationLoadBalancer` | in-memory L7 listener→rule→target-group→round-robin model | **reference / tested** |

The distinctive piece is the feedback: most balancers only know liveness, but here
each instance also emits a **0–100 capacity weight** so the balancer can shift traffic
away from a saturated node *before* it starts failing health checks.

## 1. The production load-balancing stack

Real traffic is balanced by infrastructure, in three nested tiers:

- **AWS ALB Ingress (edge).** A WAF-protected ALB is the sole public entry to the
  gateway (the EKS overlay drops the NLB and patches the gateway Service to
  `ClusterIP`); see the README [Kubernetes & EKS](../../README.md#kubernetes--eks) and the
  [CDN Edge investigation](12_CDNS.md) for the CloudFront→ALB edge.
- **kube-proxy + topology-aware routing (in-cluster).** Service-to-service calls resolve
  through ClusterIP names, and `trafficDistribution: PreferClose` prefers same-AZ
  endpoints to cut cross-AZ cost — detailed in
  [17_Scalability](17_Scalability.md#1-compute-tier-scaling--hpa-is-the-real-autoscaler).
- **Armeria client-side LB (gateway → backends).** The gateway wraps each upstream in a
  `HealthCheckedEndpointGroup` (`UpstreamEndpointGroups`) that drops a down backend from
  selection and load-balances across the healthy replicas — covered in
  [09_API_Gateway](09_API_Gateway.md#5-resilience-the-gateway-applies-cross-links) and
  [11_Service_Discovery](11_Service_Discovery.md#3-cloud-map-dns--health-checked-endpoint-groups).

At the data layer, AZ-aware Redis read routing is a form of read load-balancing across
replicas — [04_Replication](04_Replication.md#1-redis-read-replicas--az-aware-read-routing).

## 2. Capacity-weight feedback — the app → LB signal

The one piece of load balancing the *application* owns is the capacity signal. Both
load shedders compute a weight that falls as the instance fills up:

```
suggestedWeight = shuttingDown ? 0 : max(0, round((1 - utilization) * 100))
```

([`LoadShedder`](../../src/main/java/com/recsys/loadshed/LoadShedder.java) on model-serving,
[`OnlineLoadShedder`](../../src/main/java/com/recsys/loadshed/OnlineLoadShedder.java) on 7010).
That weight is surfaced two ways:

- **As a response header** — every model-serving response carries
  `X-Capacity-Weight: <0–100>`
  ([`RecommendationController`](../../src/main/java/com/recsys/api/rest/RecommendationController.java)
  sets it from `loadShedder.snapshot().suggestedWeight()`), so an inline balancer can
  weight-shift per response.
- **On the health surface** — `GET /health/load` and `GET /online/ops` expose the same
  `suggestedWeight`, and `GET /health/ready` returns `503` past the drain utilization so
  the LB removes the node from rotation entirely.

```bash
curl -s -D - -o /dev/null -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" -d '{"userId":"123","k":5}' | grep -i x-capacity-weight
# X-Capacity-Weight: 89
```

The result is a graceful gradient rather than a binary in/out: at 11% utilization the
weight is ~89 and the node takes a full share; as it approaches the concurrency cap the
weight decays toward 0 and traffic drifts elsewhere; past the drain threshold it fails
readiness and drains. The shedders themselves are the [Fault Tolerance
investigation](18_Fault_Tolerance.md#2-overload-protection--shed-fast-never-queue-unbounded).

## 3. The `ApplicationLoadBalancer` L7 model

[`ApplicationLoadBalancer`](../../src/main/java/com/recsys/infrastructure/alb/ApplicationLoadBalancer.java)
and the `infrastructure/alb/` package model an ALB-style Layer-7 balancer in memory:
a `route(port, path, host, method)` call resolves the `AlbListener` for the port,
evaluates its `ListenerRule`s (path-pattern conditions) in priority order to pick a
target-group name, and then asks the
[`AlbTargetGroup`](../../src/main/java/com/recsys/infrastructure/alb/AlbTargetGroup.java) for
the next target. Target selection is **round-robin over healthy targets only**:
`nextTarget()` filters to routable targets and advances an `AtomicInteger` counter
(`counter.getAndIncrement() % healthy.size()`), so an unhealthy target is skipped and
each healthy one gets an even share.

It is **a reference/tested model, not the production data path** — real routing is done
by the AWS ALB and Armeria (§1). Its value is as an executable spec of the listener →
rule → target-group → health-aware-round-robin shape the EKS ALB ingress implements, and
as the unit under test for that routing logic.

## 4. How the layers relate

- **Load-bearing in production:** AWS ALB (edge), kube-proxy + topology-aware routing
  (in-cluster), Armeria health-checked endpoint groups (gateway → backends), AZ-aware
  Redis read routing (data). These actually move packets.
- **The feedback loop:** the capacity-weight signal is what makes any of those balancers
  *capacity-aware* rather than merely *liveness-aware* — the app publishes `X-Capacity-
  Weight` / `suggestedWeight`, and an ALB/Envoy/mesh weights traffic accordingly.
- **Reference:** `ApplicationLoadBalancer` documents and tests the L7 routing algorithm
  without being in the request path.

## 5. Testing

- **The L7 model** — `ApplicationLoadBalancerTest` covers the whole shape:
  listener-rule routing by priority, path-pattern matching, and health-aware round-robin
  over the target group (including skipping unhealthy targets).
- **Capacity weight** — the load-shedder tests (`LoadShedderTest`,
  `OnlineLoadShedderTest`) cover `suggestedWeight = (1 − util) × 100` and the
  shutting-down → 0 case.
- **Client-side health-aware LB** — `GatewayUpstreamHealthCheckIntegrationTest` covers
  dropping an unhealthy upstream and fast-failing.

## Design specs & plans

Each production load-balancing layer has an explicit design spec (with a paired
implementation plan) under `docs/superpowers/`; the in-memory `ApplicationLoadBalancer`
model has none (it is a reference/test artifact, not a shipped feature).

- **Client-side health-aware LB** — [Health-Aware Upstream Discovery (Option A1)](../superpowers/specs/2026-07-10-gateway-upstream-endpoint-discovery-design.md)
  ([plan](../superpowers/plans/2026-07-10-gateway-upstream-endpoint-discovery.md)): the
  Armeria `HealthCheckedEndpointGroup` per backend that drops unhealthy replicas (§1).
- **Same-AZ routing** — [Cross-AZ Traffic Reduction](../superpowers/specs/2026-07-02-cross-az-traffic-reduction-design.md)
  ([reduction plan](../superpowers/plans/2026-07-02-cross-az-traffic-reduction.md),
  [AZ-aware reads plan](../superpowers/plans/2026-07-08-az-aware-redis-reads.md)):
  `trafficDistribution: PreferClose` and AZ-aware Redis reads (§1).
- **The edge ALB** — [Gateway WAF Ingress](../superpowers/specs/2026-07-02-gateway-waf-ingress-design.md)
  ([plan](../superpowers/plans/2026-07-02-gateway-waf-ingress.md)): the WAF-protected
  ALB Ingress that replaces the NLB as the sole public entry (§1).
- **Capacity-weight feedback** — [Overload Protection Hardening](../superpowers/specs/2026-07-08-overload-protection-design.md)
  ([plan](../superpowers/plans/2026-07-08-overload-protection.md)): the load shedders
  that compute `suggestedWeight` and emit `X-Capacity-Weight` (§2).

## Sharp edges — notes

1. **`ApplicationLoadBalancer` isn't in the request path.** It's a tested model of the
   ALB's L7 routing, not the production balancer — don't mistake it for where real
   traffic is routed.
2. **Capacity-weight needs an external consumer.** The app *emits* `X-Capacity-Weight`,
   but nothing balances on it unless an ALB target-group / Envoy / mesh is configured to
   read it; on its own it's just an observable signal.
3. **Two independent "is it up?" signals.** Armeria health checks (~10 s, drops from LB)
   and the registry TTL (~20–40 s) detect a down backend on different timescales — see
   [11_Service_Discovery](11_Service_Discovery.md); capacity weight is a third, finer
   signal that acts before either trips.
4. **Readiness drain is binary; weight is a gradient.** `/health/ready` → `503` removes a
   node entirely past the drain threshold, whereas the weight decays smoothly below it —
   they're two stages of the same back-pressure, not alternatives.
5. **Round-robin is even, not capacity-aware, inside the model.** `AlbTargetGroup`
   round-robins healthy targets equally; capacity-aware shifting happens at the *real* LB
   via the weight header, not inside the in-memory model.
