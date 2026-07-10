# Canonical Recommendation Gateway Entry Point

**Date:** 2026-07-10

**Scope:** API Gateway routing in `com.recsys.api.gateway` and
`com.recsys.application.gateway`.

## Problem

The API Gateway is the centralized public edge, but recommendation clients still
need to choose among backend-shaped prefixes such as
`/api/recommend/embedding`, `/api/recommend/model`,
`/api/recommend/online`, and `/api/recommend/sequential`. The gateway also keeps
older `/api/catalog`, `/api/model`, and `/api/online` routes. These overlapping
entry points expose deployment topology, duplicate route configuration, and make
the documented public contract harder to understand.

## Goal

Provide one canonical, task-oriented recommendation entry point:

```http
POST /api/recommend
Content-Type: application/json

{
  "userId": 42,
  "strategy": "online"
}
```

The optional `strategy` field selects `embedding`, `model`, `online`, or
`sequential`. When omitted, the gateway selects `model`.

All existing routes remain operational as deprecated aliases. Non-recommendation
task routes (`/api/users`, `/api/movies`, `/api/features`, `/api/knowledge`) and
the optional LLM routes are unchanged.

## Non-goals

- Removing an existing route or environment variable.
- Changing any backend request or response contract.
- Moving recommendation orchestration into the model service.
- Combining results from multiple strategies.
- Changing authentication, rate-limit, circuit-breaker, retry, or health policy.

## Architecture

Register a dedicated `RecommendationGatewayService` for the exact
`POST /api/recommend` route before the existing catch-all proxy. The service owns
only canonical-request validation, strategy selection, removal of the
gateway-only selector, and dispatch to an underlying recommendation route.

Extract the generic forwarding pipeline currently embedded in
`GatewayProxyService` into a package-internal reusable component. Both
`RecommendationGatewayService` and `GatewayProxyService` use this component so
canonical and deprecated routes have identical authentication, principal
propagation, credential stripping, rate limiting, circuit-breaking, retry,
timeout, and upstream-response behavior.

The existing recommendation route definitions remain the source of truth for
backend base URIs, health paths, route names, environment variables, circuit
breakers, and rate-limit buckets. The canonical service maps strategies to these
route names:

| Strategy | Existing route | Upstream path |
|---|---|---|
| `embedding` | `embed-recall` | `/v2/recommend` |
| `model` | `model-inference` | `/v2/recommend` |
| `online` | `online-blend` | `/v2/recommend` |
| `sequential` | `sequential` | `/v2/recommend` |

The canonical endpoint does not add a synthetic route to health aggregation.
Health continues to report the four underlying routes and their circuit states.

## Request flow

1. Armeria matches the exact `POST /api/recommend` route before the catch-all.
2. Gateway authentication runs against the canonical request path.
3. The request body is aggregated and parsed as a JSON object.
4. `strategy` is read, trimmed, and compared case-insensitively. A missing field
   selects `model`.
5. The gateway removes `strategy` from a copy of the JSON object and serializes
   the remaining object for forwarding.
6. The selected existing route supplies the client, timeout, circuit breaker,
   and rate-limit identity. The upstream path is `/v2/recommend`; the original
   query string, if present, is preserved.
7. The shared forwarding pipeline applies the existing gateway policies and
   passes the upstream response through unchanged.

The selector is intentionally removed because it is gateway routing metadata,
not part of any backend contract. Other JSON fields retain their values and
structure, though whitespace and object-key ordering are not guaranteed after
serialization.

## Validation and errors

- Any method other than `POST` on exact path `/api/recommend` returns `405 Method
  Not Allowed` as JSON and advertises `POST` in the `Allow` header.
- An empty body, malformed JSON, or a JSON value that is not an object returns
  `400 Bad Request` as JSON.
- A non-string or unsupported `strategy` returns `400 Bad Request` and lists the
  supported values without exposing backend addresses.
- A missing `strategy` selects `model`.
- Authentication failures retain existing status codes and response shapes.
- Rate-limit rejection remains `429`; an open circuit remains `503`; an
  unreachable upstream remains `502`.
- Backend status codes, headers allowed by the existing proxy policy, and bodies
  pass through unchanged.

## Compatibility and documentation

Every current prefix remains registered and behaves as before. Documentation
marks those recommendation prefixes and backend-oriented routes as deprecated
aliases and presents `POST /api/recommend` first. The route table documentation
must be updated to match `MicroserviceRoute.defaults()`, removing the current
stale entries for routes no longer present in code.

No deprecation response header is introduced in this change because adding one
consistently would require a separate compatibility policy and removal schedule.

## Testing

Unit tests cover:

- missing `strategy` dispatches to `model`;
- each supported strategy dispatches to the correct existing route;
- selector matching is trimmed and case-insensitive;
- `strategy` is absent from the forwarded body while all other JSON data remains;
- malformed, empty, non-object, non-string-strategy, and unsupported-strategy
  requests return `400`;
- non-`POST` requests return `405` with `Allow: POST`.

Integration tests cover:

- canonical requests reach the selected fake upstream at `/v2/recommend`;
- authentication and trusted identity headers match legacy proxy behavior;
- client credentials and spoofed identity headers remain stripped;
- rate limiting and circuit breaking use the selected underlying route;
- upstream success and error responses pass through;
- legacy aliases continue to proxy successfully.

Existing route-table tests are updated to assert the canonical strategy mapping
and the retained aliases. The focused gateway test suite and full Maven test
suite must pass before completion.

## Expected outcome

Clients gain a stable recommendation API independent of service placement while
existing consumers continue working. The gateway retains one policy pipeline,
and backend services require no changes.
