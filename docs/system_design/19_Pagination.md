# Pagination in Recsys-Backend-Service

An investigation of pagination across recommendation feeds, the MySQL catalog,
and the reusable SQL layer. This document separates behavior that exists today
from the approved optimization that has not yet been implemented.

## The big picture

The repository favors **keyset pagination** for sequential traversal:

- Recommendation results use a `(score, itemId)` seek anchor over an in-memory
  ranking.
- The MySQL movie catalog uses a signed `(popularity_score, id)` seek anchor over
  composite indexes.
- Offset pagination exists only as a delayed-join SQL helper for true
  random-access pages.

Keyset pagination avoids walking and discarding every preceding row. It does not,
by itself, provide snapshot consistency: if values that determine ordering change
between requests, rows can move across the cursor boundary.

## 1. Current recommendation pagination

The catalog/model-serving `POST /v2/recommend` path ranks a fresh candidate window
and passes it to
[`CursorPaginationService`](../../src/main/java/com/recsys/application/pagination/CursorPaginationService.java).
The cursor anchors the final returned `(score DESC, itemId ASC)` tuple.

Current wire format:

```text
base64url("v2:<score>:<itemId>")
```

The implementation first looks for the anchor item ID and otherwise finds the
first tuple strictly after the anchor. It emits `nextCursor` when more items exist
inside the ranked window.

Important current limitations:

- The cursor is encoded but not signed, so clients can alter its contents.
- Non-finite floating-point scores and oversized tokens are not rejected
  explicitly.
- Recall is bounded to `limit * recallMultiplier` (default `5 * limit`), so the
  cursor traverses only a newly computed window rather than a complete result set.
- The ID fast path ignores an anchor score change.
- The online-serving implementation currently ignores `cursor` and
  `excludedItemIds` and returns `nextCursor = null`.
- Recommendation responses do not expose an explicit `hasMore` field.

This is a **live feed**, not a frozen snapshot. Re-ranking between requests can
move items before or after the anchor, so the API must not claim absolute
duplicate- or omission-free traversal under score changes.

## 2. Current MySQL catalog pagination

`GET /v1/catalog/movies` is the strongest existing pagination path. It orders by:

```sql
ORDER BY popularity_score DESC, id DESC
```

and seeks with:

```sql
WHERE (popularity_score, id) < (?, ?)
LIMIT ?
```

Filtered requests add `genre = ?`. The repository forces the corresponding
composite index:

- `idx_movies_genre_popularity_id (genre, popularity_score DESC, id DESC)`
- `idx_movies_popularity_id (popularity_score DESC, id DESC)`

[`MovieCatalogService`](../../src/main/java/com/recsys/application/catalog/MovieCatalogService.java)
requests `limit + 1`, trims the lookahead row, and returns `nextCursor` exactly
when `hasMore` is true.

[`CatalogCursorCodec`](../../src/main/java/com/recsys/application/catalog/CatalogCursorCodec.java)
uses a versioned HMAC-SHA256 token that:

- Binds the cursor to the normalized genre filter.
- Preserves the exact `BigDecimal` score.
- Enforces a 2 KiB token bound and strict UTF-8/numeric parsing.
- Uses constant-time signature comparison.
- Requires a signing key of at least 32 UTF-8 bytes.

Catalog traversal is index-efficient and tamper-evident, but updates to
`popularity_score` can still move rows because reads do not share a database
snapshot across HTTP requests.

## 3. Offset pagination

[`MillionScalePaginationSql.delayedJoinPage`](../../src/main/java/com/recsys/application/pagination/MillionScalePaginationSql.java)
supports deep random access:

```sql
SELECT t.<payload>
FROM table t
JOIN (
    SELECT id, sort_column
    FROM table FORCE INDEX (<covering_index>)
    WHERE ...
    ORDER BY sort_column, id
    LIMIT ? OFFSET ?
) page_keys ON t.id = page_keys.id
ORDER BY page_keys.sort_column, page_keys.id
```

Only the inner covering-index scan pays the offset cost; full base rows are
loaded after the page keys are known. This is preferable to discarding full rows,
but the work remains proportional to the offset. No public production endpoint
currently exposes this mode.

Use keyset pagination for next/previous traversal. Reserve delayed join for an
explicit product requirement such as an administrative “jump to page 1000.”

## 4. Approved recommendation optimization

The approved design introduces one forward-only signed live-keyset contract for
both model and online serving. This section is **planned**, not current behavior.
The full approved specification is
[Pagination Optimization Design](../superpowers/specs/2026-07-27-pagination-optimization-design.md).

### Shared contract

Each request will:

1. Validate and bind the cursor before recall work.
2. Recompute the current eligible ranking.
3. Seek strictly after `(score DESC, itemId ASC)`.
4. Collect up to `limit + 1` qualifying items within a bounded recall budget.
5. Return at most `limit` items.
6. Emit `nextCursor` exactly when `hasMore` is true.

Changing `limit` is allowed. Changing the user, exclusions, or another
ordering/eligibility input invalidates the cursor.

### Signed recommendation cursor

The new token will contain:

- Format version.
- User ID.
- Normalized query fingerprint.
- Anchor score and item ID.
- Issued-at timestamp.

HMAC-SHA256 authenticates the payload. Decoding will enforce token and item-ID
bounds, strict UTF-8, finite scores, expiry, query binding, and constant-time
signature comparison. An active key signs new tokens; an optional previous key
supports rotation.

Existing unsigned `v2` tokens can be accepted temporarily behind an explicit
compatibility flag. They will receive strict structural validation, increment a
legacy-use metric, and upgrade to a signed token on the next page.

### Shared coordinator

A `RecommendationPaginationCoordinator` will own:

- Query normalization and fingerprinting.
- Cursor decoding before expensive work.
- Bounded candidate retrieval.
- Stable ordering and seek pagination.
- Lookahead and response invariants.
- Encoding the next signed cursor.

`RecommendationOrchestrator` and `OnlineBlendingPipeline` will use the same
coordinator, eliminating their current contract mismatch.

## 5. Live-feed semantics

The recommendation order is total and deterministic for a single computation:

1. Score descending.
2. Item ID ascending.

The cursor resumes at the first tuple with a lower score, or the same score and a
lexicographically greater ID.

Across requests:

- A new item before the anchor is not returned.
- A new item after the anchor may be returned.
- A score change can move an item across the boundary.
- An excluded or removed anchor falls back to the tuple seek position.

These rules are predictable and stateless, but deliberately weaker than snapshot
pagination. Snapshot guarantees would require storing a ranked result or a
database snapshot identifier and are outside the approved scope.

## 6. Resource bounds and exact termination

Recommendation recall and ranking will have a configurable per-request ceiling.
This prevents forged or deep cursors from causing unbounded CPU and memory work.
Only returned items are hydrated.

One-item lookahead proves `hasMore` within the bounded source. If the source
cannot inspect enough candidates to prove that another result exists, it
terminates with `hasMore=false` rather than issuing a speculative cursor.

The reusable `MySqlClient.queryPage` will use the same lookahead rule: its SQL
plan returns at most `pageSize + 1`, it trims the extra row, and it extracts the
next cursor from the final returned row only when the extra row exists.

## 7. Errors, observability, and rollout

Invalid, oversized, expired, tampered, non-finite, or query-mismatched cursors
return a generic `400 Bad Request`. Responses and logs do not reveal cursor
payloads, user identifiers, signing keys, or validation internals.

Bounded metrics will count:

- Decode failures by safe reason.
- Legacy cursor acceptance.
- Pages and terminal pages.
- Recall-budget exhaustion.
- Verification through the previous rotation key.

Rollout order:

1. Issue signed cursors while accepting legacy cursors.
2. Observe legacy-use and failure metrics for at least one maximum cursor age.
3. Exercise previous-key verification in a non-production rotation.
4. Disable legacy decoding.
5. Remove the legacy path in a later cleanup.

## 8. Testing

The optimization requires:

- Codec tests for signing, tampering, expiry, rotation, malformed input, finite
  scores, query binding, and legacy migration.
- Pagination tests for ties, removed anchors, score changes, insertions,
  exclusions, exact terminal pages, and ordering validation.
- Cross-serving contract tests proving model and online paths agree.
- Resource-bound tests proving cursor validation precedes recall and recall
  exhaustion terminates honestly.
- Generic MySQL lookahead tests.
- Existing catalog traversal, cursor-security, and real MySQL `EXPLAIN`
  index-contract regression tests.

## Sharp edges — notes

1. **Live keyset is not snapshot pagination.** Stable tuple ordering does not
   freeze scores across requests.
2. **`hasMore` is evidence-based.** A cursor is emitted only when lookahead proves
   another result within the bounded source.
3. **Cursor security and cursor privacy differ.** HMAC prevents tampering but does
   not encrypt the payload.
4. **Offset remains proportional to depth.** Delayed join reduces row-fetch cost,
   not the index entries scanned.
5. **Catalog and recommendation cursors stay separate.** Exact SQL decimals and
   volatile model scores have different validation and lifecycle requirements.
