# API Compatibility Policy

This is the contract between the RecSys gateway and its callers: what may change
without warning, what may not, and how much notice you get before something is removed.

## Versioning

The public surface is versioned in the URL path, immediately after `/api`:

```
POST /api/v1/recommend
GET  /api/v1/catalog/item?id=1
```

The version is owned by the gateway. Backends keep their own internal paths, which are
not part of this contract and are not reachable from outside the cluster.

**Unversioned paths are implicit v1 and are deprecated.** `GET /api/catalog/item` still
works and returns exactly what `GET /api/v1/catalog/item` returns; it carries deprecation
headers. Migrating is a path change only — request and response bodies are identical.

An unknown version returns `400`:

```json
{"error":"unsupported API version: v2; supported: v1"}
```

## What is a breaking change

**Additive — may ship at any time, without a version bump:**

- A new optional request field.
- A new response field.
- A new route.
- A new value in a field documented as open-ended.

Clients must tolerate unknown response fields. A client that rejects unrecognised JSON
keys is not compatible with this policy.

**Breaking — requires a new version:**

- Removing or renaming a response field.
- Tightening validation on an existing request field.
- Changing the status code returned for an unchanged condition.
- Changing default behaviour when a field is omitted.
- Removing a route.

## Support window

Two versions are supported concurrently: the current version N and its predecessor N−1.
A third is never promised.

## Deprecation and notice

A deprecated route or spelling responds with:

| Header | Meaning |
|---|---|
| `Deprecation: true` | This request shape is deprecated. |
| `Sunset: <HTTP-date>` | The earliest date it may be removed (RFC 8594). |
| `Link: <...>; rel="successor-version"` | The replacement path, when one is mechanically equivalent. |

`Sunset` is published when the deprecation is announced, never later. There is a minimum
of **twelve months** between that announcement and removal.

`Link` is emitted for the unversioned-spelling deprecation, where the successor is exactly
the same path under `/api/v1`. It is **not** emitted for the back-compat alias routes
(`/api/catalog`, `/api/model`, `/api/online`), because those do not map one-to-one onto
their replacements — see below.

## Deprecated today

| Deprecated | Replacement | Notes |
|---|---|---|
| Any unversioned `/api/...` path | The same path under `/api/v1` | Bodies identical; path change only |
| `/api/catalog/...` | `/api/v1/movies/...` and `/api/v1/recommend` | Not a one-to-one mapping — check the route you need |
| `/api/model/...` | `/api/v1/recommend` with `{"strategy":"model"}` | |
| `/api/online/...` | `/api/v1/recommend` with `{"strategy":"online"}`, `/api/v1/features` | |

## Removal

Removal is always an explicit, reviewed pull request. Nothing in the gateway expires a
route automatically, and a `Sunset` date passing does not by itself change behaviour.

This is deliberate. The project has no client inventory, so it cannot know who is still
calling a deprecated path. An enforcing sunset would be a scheduled outage for whoever
did not read the header. The date is a commitment about the *earliest* removal, not an
automated one.

## Detecting deprecation

Check for the `Deprecation` header on any response. In CI, failing a build when a
dependency starts returning `Deprecation: true` is the cheapest way to catch this early.

```bash
curl -sI https://<gateway>/api/catalog/item?id=1 | grep -i '^deprecation\|^sunset\|^link'
```
