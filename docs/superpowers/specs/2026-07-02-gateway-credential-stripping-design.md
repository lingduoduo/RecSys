# Gateway Credential Stripping — Design

**Date:** 2026-07-02
**Status:** Approved (pending spec review)
**Scope:** `com.recsys.application.gateway` (`GatewayProxyService` and
`LlmProxyService` upstream-header builders) + their existing tests. No k8s or
other changes. Fixes a finding from the API-gateway runtime verification.

## Goal

Stop the gateway from forwarding the caller's own gateway credentials
(`x-api-key`, `Authorization`) to backend services. The gateway is the auth
boundary and already forwards the verified identity via `x-authenticated-*`
headers, so the raw credential must not leak into the internal hop.

## Context

- Both proxy services build the upstream request headers in a static
  `buildUpstreamHeaders(...)`. Each copies inbound headers except a transport
  `HOP_BY_HOP` set and (since the phase-2 work) any `x-authenticated-*` header:
  `GatewayProxyService.java:144` and `LlmProxyService.java:294`
  (`if (!isHopByHop(n) && !n.regionMatches(true, 0, "x-authenticated-", 0, 16)) …`).
- `HOP_BY_HOP` contains only transport hop-by-hop names (`connection`,
  `content-length`, `host`, `proxy-authenticate`, `proxy-authorization`, `te`,
  `trailer`, `transfer-encoding`, `upgrade`, `keep-alive`, `expect`). It does
  **not** include `authorization` or `x-api-key`.
- Consequence (observed live during verification): a request authenticated with
  `x-api-key: <key>` is proxied with that header intact — the backend receives
  the gateway's API key. The same applies to a `Authorization: Bearer <jwt>`
  credential.

## Design

Extend the upstream-header filter in **both** proxies to also drop the gateway's
consumed credential headers, kept as a small dedicated set separate from the
transport `HOP_BY_HOP` set (they are gateway-consumed, not hop-by-hop):

```java
private static final Set<String> GATEWAY_CONSUMED_CREDENTIALS =
        Set.of("authorization", "x-api-key");
```

In `buildUpstreamHeaders`, change the copy filter from:

```java
if (!isHopByHop(n) && !n.regionMatches(true, 0, "x-authenticated-", 0, 16)) {
    b.add(name, value);
}
```

to also exclude the credential headers (case-insensitive), e.g. via a helper
`isStrippedInbound(n)` that returns true for hop-by-hop, `x-authenticated-*`, or a
`GATEWAY_CONSUMED_CREDENTIALS` member:

```java
if (!isStrippedInbound(n)) {
    b.add(name, value);
}
```

The identity-injection step (`principal.identityHeaders()`) is unchanged — the
backend still receives `x-authenticated-*`. Only the raw credential is removed.

This targets each proxy's **upstream-request** builder only (`GatewayProxyService`
line 144, `LlmProxyService` line 294). The response-side header copies
(cache-hit / streaming response paths in `LlmProxyService`) are not credential
forwards and are unchanged.

The two proxies already duplicate this filter logic (as they did for the
`x-authenticated-*` strip); this change follows that established pattern rather
than introducing a shared helper — a broader de-duplication is out of scope.

## Error Handling

No new error paths. Stripping a header the request did not send is a no-op. A
public-path / anonymous request carries no credential to strip.

## Testing

Extend the existing per-proxy tests, which already exercise `buildUpstreamHeaders`
for the anti-spoof strip:

- `GatewayProxyServiceTest` — send inbound `x-api-key: <k>` and
  `Authorization: Bearer <t>` with a principal; assert the **forwarded** headers
  contain neither (case-insensitive), while `x-authenticated-*` identity headers
  and normal headers still pass through.
- `LlmProxyServiceTest` — the same assertion against `LlmProxyService.buildUpstreamHeaders`.

Runtime-verifiable exactly as the finding was found: point a route at a
header-echo backend, send an authenticated request, and confirm the echoed
(forwarded) headers no longer include `x-api-key`/`authorization`.

## Out of Scope (YAGNI)

- De-duplicating the two proxies' `buildUpstreamHeaders`/`HOP_BY_HOP` copies.
- Stripping any other headers; response-side header handling.
- Making the stripped set configurable.

## Cross-cutting

- Java-only, confined to `com.recsys.application.gateway`. No k8s or app-wide
  changes.
- Backward-compatible for the intended architecture (backends trust the gateway
  and read `x-authenticated-*`); a backend that independently re-validated the
  forwarded credential would no longer receive it — intentional, per the auth-
  boundary decision.
- One commit; feature branch, PR to `main` (never commit to `main` directly).
