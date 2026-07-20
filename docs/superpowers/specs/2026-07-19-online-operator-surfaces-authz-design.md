# Operator-gate online-serving introspection surfaces — design

**Date:** 2026-07-19
**Status:** Approved (design)
**Branch:** `fix/online-operator-surfaces-authz` (stacked on `fix/auth-hardening-admin-token` / PR #196)

## Problem

Audit finding #4: the online-serving process (7010) exposes introspection and bulk-read
surfaces with no service-level auth — `GET /online/ops` (metrics / load-shed / rate-limit /
capacity snapshot) and `GET /shards/shard?index=N` (bulk dump of an entire shard's stream).
Only `POST /shards/topology` (reshard) is admin-gated.

Two of the audit's three sub-recommendations are already handled or don't apply:

- **NetworkPolicy** restricting 7010 ingress to the gateway already exists
  (`k8s/base/network-policy.yaml`, `recsys-online-serving`). No change needed.
- **Device-ownership** on `/shards/device` does not apply: under the trust model established
  for findings #1/#2 (trusted client/service lookup), backends legitimately read any device.

The residual gap: after `fix/gateway-auth-fail-closed` turns gateway auth on, the gateway's
`/api/online` catch-all passthrough still lets **any authenticated client** — not just
operators — reach `/online/ops` and `/shards/shard`. These are operator tools, not client API.

## Goal

Require an operator token for the introspection/bulk surfaces, so a valid client credential is
not sufficient to read ops internals or bulk-dump shards. Fail closed.

## Decisions (from brainstorm)

- **Scope:** gate `/online/ops` and `/shards/shard` only. Leave `/shards/device` (per-entity
  read), `/shards/records` (write), and the serving routes as normal authenticated data-plane.
- **Token:** reuse `SHARD_ADMIN_TOKEN` (already gates reshard) as the single 7010 operator token.
- **Unset posture:** fail closed — when `SHARD_ADMIN_TOKEN` is unset, these surfaces return 403,
  matching how reshard already behaves ("reshard disabled") and the fail-closed philosophy of #3.

## Design

**`AdminTokenGuard`** (new, `com.recsys.api.online`) — single source of truth:

- `isConfigured()` — token non-null and non-blank.
- `isAuthorized(String provided)` — `isConfigured()` AND constant-time (`MessageDigest.isEqual`)
  match of `provided` against the token.
- `newDecorator(AdminTokenGuard)` — an Armeria `DecoratingHttpServiceFunction` that reads the
  `X-Admin-Token` header and returns `403` (`{"error":"operator token required"}`) unless
  `isAuthorized`; otherwise delegates.
- `HEADER = "X-Admin-Token"` (same header the reshard endpoint already uses).

**`OnlinePredictionServer`** — build one guard from `System.getenv("SHARD_ADMIN_TOKEN")` and apply
`newDecorator` to the `/online/ops` route registration. `OnlineOpsService` itself is unchanged.

**`ShardedRecordService`** — replace the raw `adminToken` string field with an injected
`AdminTokenGuard` (constructed from the token). Use it for:
- the existing reshard check — `topologyStore == null || !guard.isConfigured()` → 403
  "reshard disabled"; `!guard.isAuthorized(header)` → 403 "invalid admin token" (messages
  preserved);
- the new gate on `handleReadShard` (`GET /shards/shard`) — `!guard.isAuthorized(header)` → 403.

This unifies the constant-time compare that #196 added into the guard, so #4 stacks on #196.

**k8s** — wire `SHARD_ADMIN_TOKEN` into `k8s/base/online-serving.yaml` from a Secret
(`recsys-online-admin`, key `admin-token`, `optional: true`) so the surfaces are provisionable;
they stay 403 until an operator creates the Secret. Document in CLAUDE.md that `SHARD_ADMIN_TOKEN`
now also gates `/online/ops` and `/shards/shard`.

## Testing

- **`AdminTokenGuardTest`** (new) — unconfigured ⇒ not authorized; configured + matching header
  ⇒ authorized; missing / wrong / same-length-wrong ⇒ not; decorator returns 403 vs delegates.
- **`OnlinePredictionServerIntegrationTest`** — register `/online/ops` (decorated) and the shard
  service with a token; assert `X-Admin-Token` ⇒ 200 for `/online/ops` and `/shards/shard`, and
  **no** token ⇒ 403; `/shards/device` still 200 without a token.
- **`ShardedRecordServiceIntegrationTest`** (docker) — add `X-Admin-Token` to `/shards/shard`
  calls; add a no-token ⇒ 403 case; `/shards/device` tests unchanged.
- **`ShardedRecordServiceReshardTest`** (#196) — still green through the guard (correct / missing /
  wrong / same-length-wrong / blank-token-disabled / null-topology).

## Non-goals

- No NetworkPolicy change (already correct).
- No device-ownership check (wrong trust model).
- No change to `/shards/device`, `/shards/records`, or the serving routes.

## Scope

`AdminTokenGuard` + `OnlinePredictionServer` + `ShardedRecordService` + `online-serving.yaml` +
CLAUDE.md + tests. One PR, stacked on #196.
