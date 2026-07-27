# Pagination Optimization Design

**Date:** 2026-07-27

## Goal

Make pagination consistent, secure, and efficient across the repository while preserving the existing MySQL catalog contract.

The primary change is a shared forward-only, signed live-keyset contract for recommendation feeds in model serving and online serving. The existing MySQL movie catalog remains a specialized signed keyset implementation because its exact decimal database ordering differs materially from volatile recommendation ranking.

## Scope

### In scope

- Recommendation pagination in model serving and online serving.
- Signed, query-bound recommendation cursors.
- Temporary compatibility with existing unsigned `v2` recommendation cursors.
- Exact `hasMore` detection through one-item lookahead.
- Bounded recall and ranking work for deep-page requests.
- Reusable MySQL `queryPage` lookahead correctness.
- Tests, metrics, configuration, and operational documentation.

### Out of scope

- Snapshot or session-backed pagination.
- Backward or previous-page navigation.
- Offset-based public API pagination.
- Cursor payload encryption.
- A generic framework shared by catalog and recommendation cursor codecs.
- Changes to the movie catalog's public pagination semantics.

## Existing Behavior

The repository currently contains three relevant pagination implementations:

1. `CursorPaginationService` slices a newly ranked in-memory recommendation list using an unsigned Base64 cursor anchored by `(score, itemId)`.
2. The MySQL movie catalog uses a signed, genre-bound keyset cursor anchored by `(popularity_score, id)` and fetches `limit + 1`.
3. `MillionScalePaginationSql.delayedJoinPage` supports optimized offset pagination over a covering-index subquery, but no public production endpoint uses it.

Recommendation behavior is inconsistent across serving paths. `RecommendationOrchestrator` paginates a fixed `5 * limit` candidate window, while `OnlineBlendingPipeline` ignores both `cursor` and `excludedItemIds` and always returns a null cursor. The generic MySQL `queryPage` helper emits a cursor when exactly `pageSize` rows are returned, which can cause an unnecessary empty terminal request because it does not require lookahead proof.

## Chosen Approach

Use a unified signed live-keyset contract for recommendation feeds.

Each request recomputes the current eligible ranking, seeks strictly after the cursor's `(score DESC, itemId ASC)` anchor, and uses one-item lookahead to prove whether more results exist. This remains stateless and allows rankings to change between requests. It minimizes omissions and duplicates according to the current ordering but does not promise snapshot consistency.

The catalog cursor remains separate. Its `BigDecimal` position, SQL filter binding, and database index contract should not be forced into the recommendation cursor abstraction.

## Recommendation API Contract

Recommendation responses retain `nextCursor` and add `hasMore`.

- `nextCursor` is non-null exactly when `hasMore` is true.
- The first request omits the cursor or supplies null.
- Pagination is forward-only.
- Changing `limit` between requests is allowed because it does not affect ordering.
- Reusing a cursor with a different user or pagination-affecting query is rejected.
- If the bounded source cannot prove that another item exists, the response terminates with `hasMore=false`.

The catalog response contract remains unchanged: `items`, `nextCursor`, and `hasMore`.

## Cursor Format and Security

Introduce `RecommendationCursorCodec` as the only component responsible for recommendation cursor wire encoding.

The signed cursor payload contains:

- Format version.
- User ID.
- A normalized query fingerprint.
- Anchor score.
- Anchor item ID.
- Issued-at timestamp.

The query fingerprint includes the normalized, deterministically ordered exclusion set and any future input that changes eligibility or ranking. It excludes `limit`.

The cursor is Base64 URL encoded and authenticated with HMAC-SHA256. Cursor confidentiality is not required. Decoding:

- Enforces a maximum encoded length before allocating decoded structures.
- Uses strict UTF-8 decoding.
- Validates the format version and field count.
- Rejects non-finite scores.
- Verifies the signature with a constant-time comparison.
- Enforces maximum age.
- Verifies user and query binding before recall work begins.
- Returns a domain-specific invalid-cursor exception with no sensitive details.

The signing configuration supports an active signing key and one optional previous verification key. New cursors are always signed by the active key. Either configured key may verify cursors during rotation. Each key must contain at least 32 UTF-8 bytes.

## Legacy Cursor Migration

Existing unsigned `v2:<score>:<itemId>` cursors remain decodable only while a compatibility flag is enabled.

Legacy cursors cannot be authenticated, query-bound, or expiry-checked. Consequently:

- They receive strict structural, length, item-ID, and finite-score validation.
- Their use increments a dedicated metric.
- They are never reissued; every next cursor uses the signed format.
- Operators disable compatibility after at least one configured maximum cursor lifetime has elapsed following deployment of signed cursor issuance.

When compatibility is disabled, legacy cursors return the same generic invalid-cursor `400` as other invalid cursors.

## Components and Responsibilities

### `RecommendationCursorCodec`

- Encode new signed cursor positions.
- Decode and authenticate signed positions.
- Optionally decode strictly validated legacy positions.
- Bind signed cursors to a user and normalized query fingerprint.
- Enforce expiry, key rotation, token length, and numeric validity.

### `RankedListCursor`

Becomes an internal immutable position model. It no longer owns the public wire format. It validates that anchored scores are finite and item IDs are nonblank and bounded.

### `CursorPaginationService`

Accepts an already-decoded position and an ordered ranked list. It:

- Validates or asserts the `(score DESC, itemId ASC)` ordering contract.
- Seeks strictly after the complete anchor tuple.
- Does not resume by ID alone when the anchor score changed.
- Takes at most `limit + 1` qualifying items.
- Returns at most `limit` items, the next internal position, and `hasMore`.

The service remains generic over ranked item type through score and ID extractors.

### Recommendation pagination coordinator

The orchestration boundary owns wire concerns:

1. Normalize the query.
2. Decode and bind the cursor before expensive work.
3. Request candidates within the configured recall budget.
4. Rank in the stable total order.
5. Invoke `CursorPaginationService`.
6. Hydrate only returned items.
7. Encode the next internal position when `hasMore` is true.
8. Build the consistent response and trace metadata.

Implement this coordination as a focused `RecommendationPaginationCoordinator` shared by
`RecommendationOrchestrator` and `OnlineBlendingPipeline`, so cursor validation, page assembly,
and response invariants have one owner.

### Model-serving path

Replace the fixed `5 * limit` pagination assumption with a configurable bounded recall budget. The source must produce enough ordered candidates to seek past the cursor and obtain `limit + 1` qualifying results, subject to the hard ceiling.

The implementation must avoid integer overflow when calculating candidate bounds and must reject or terminate adversarial deep-page requests without unbounded CPU or memory work.

### Online-serving path

`OnlineBlendingPipeline` must honor `cursor` and `excludedItemIds` and use the same recommendation page contract as model serving. The online source should request a bounded candidate window rather than only `query.limit()` results.

If the source cannot supply enough candidates to prove more results exist, it terminates honestly with `hasMore=false`; it must not emit a speculative cursor.

### MySQL reusable pagination

`MySqlClient.queryPage` adopts explicit lookahead semantics:

- The SQL plan returns at most `pageSize + 1` rows.
- The helper treats the extra row as proof of `hasMore`.
- It returns only the first `pageSize` rows.
- It extracts the next cursor from the final returned row only when lookahead exists.

The supplied SQL plan must bind a limit of `pageSize + 1`; this is an explicit method contract.
`queryPage` validates the returned row count is at most `pageSize + 1`, trims the lookahead row,
and derives `hasMore` from the extra row. Existing callers and tests are migrated together.

`MovieCatalogService` already implements correct lookahead pagination and should retain its current behavior. Reuse the generic helper only if doing so preserves its SQL, error mapping, retry, and index contracts without adding coupling.

### Offset pagination

Keep `MillionScalePaginationSql.delayedJoinPage` as an internal opt-in utility for deep random-access use cases. Do not expose offset pagination through recommendation or catalog APIs. Its covering-index delayed join remains preferable to fetching full rows during an offset scan, but its cost is still proportional to the offset.

## Ordering and Live-Feed Semantics

Recommendation ranking must have a deterministic total order:

1. Score descending.
2. Item ID ascending.

The seek predicate is strictly after the anchor:

- Lower score; or
- Equal score and lexicographically greater item ID.

Every page is evaluated against the current ranking. Items inserted before the anchor are not returned. Items newly inserted after the anchor may be returned. Items whose scores move across the anchor can appear or disappear according to their new position. This is an explicit consequence of live pagination rather than a snapshot guarantee.

Exclusions are part of the signed query fingerprint. Clients cannot add or remove exclusions while continuing the same signed traversal. To change exclusions, they start a new traversal without the old cursor.

## Configuration

Add environment-backed settings for:

- Active recommendation cursor signing key.
- Optional previous verification key.
- Maximum recommendation cursor age.
- Legacy recommendation cursor compatibility.
- Maximum candidate recall/ranking budget per page request.
- Maximum recommendation cursor encoded length if not a fixed constant.

Production serving startup fails when recommendation pagination is enabled without a valid active signing key. Local and test construction should use explicit deterministic keys rather than silent insecure defaults.

Configuration descriptions and logs must never include key material.

## Error Handling

Invalid, oversized, expired, tampered, non-finite, unsupported-version, user-mismatched, or query-mismatched cursors map to a generic `400 Bad Request`.

The public response does not reveal:

- Whether signature or expiry validation failed.
- Decoded cursor contents.
- Expected user or query fingerprints.
- Signing configuration.

Internally, safe reason categories support metrics. Raw cursors and user identifiers are never logged. Unexpected pagination failures retain the serving path's existing `500` behavior.

## Performance and Resource Bounds

- Cursor validation occurs before recall, ranking, or hydration.
- Pagination remains stateless; no server-side page sessions or snapshots are stored.
- Candidate recall and ranking have a hard per-request ceiling.
- Only returned items are hydrated.
- One-item lookahead provides exact `hasMore` within the bounded candidate source.
- MySQL catalog queries continue using the composite covering indexes for `(genre, popularity_score, id)` and `(popularity_score, id)`.
- Public APIs do not use `OFFSET`.

Recall-budget exhaustion is not reported as `hasMore=true`. The system cannot claim that more results exist when it did not inspect enough candidates to prove it.

## Observability

Add counters for:

- Cursor decode failures by safe reason category.
- Legacy cursor acceptance.
- Pages returned.
- Terminal pages.
- Recall-budget exhaustion.
- Signed cursor verification through the previous rotation key.

Metrics must not contain raw cursor values, user IDs, item IDs, or unbounded query-derived labels.

## Testing

### Cursor codec

- Signed round trip.
- Payload and signature tampering.
- Active and previous-key verification.
- Expiry boundary.
- Oversized token rejection before decode.
- Malformed Base64 and malformed UTF-8.
- Unsupported versions and malformed field counts.
- `NaN`, positive infinity, and negative infinity rejection.
- Blank and oversized item ID rejection.
- User and query mismatch.
- Deterministic exclusion fingerprinting.
- Limit changes remain valid.
- Legacy acceptance, metrics, strict validation, and compatibility-disabled rejection.

### In-memory pagination

- First, middle, exact terminal, and short terminal pages.
- Tied scores use item ID as the stable tie-breaker.
- Removed anchor.
- Removed items before the anchor.
- Anchor score changes.
- New items before and after the anchor.
- Live reordering semantics are explicit and tested.
- Unordered input is rejected in tests or caught by a development assertion, according to the final implementation plan.
- `nextCursor` and `hasMore` always agree.

### Serving paths

- Model and online serving expose equivalent pagination contracts.
- Online serving honors exclusions and cursors.
- Invalid cursors map to generic `400`.
- Cursor validation occurs before recall.
- Only returned items are hydrated.
- Recall-budget exhaustion terminates honestly.
- Legacy cursors upgrade to signed cursors.

### MySQL

- Generic `queryPage` fetches/trims lookahead and does not emit a cursor for an exact terminal page.
- Existing catalog traversal has no duplicates or omissions across tied scores.
- Filter binding and cursor tamper tests remain intact.
- Existing `EXPLAIN` index-contract tests continue to validate both catalog indexes.
- Delayed-join offset SQL tests remain unchanged unless API clarification requires mechanical updates.

## Documentation and Rollout

Update the configuration guide and relevant architecture/API documentation with:

- Signed recommendation cursor semantics.
- Live rather than snapshot consistency.
- Forward-only navigation.
- Key generation and rotation.
- Legacy compatibility rollout and retirement.
- Recall-budget behavior.
- Consistent `nextCursor` and `hasMore` response fields.

The durable documentation is consolidated in
`docs/system_design/19_Pagination.md`. The README documentation map links to it,
while the indexing, partitioning, and scalability investigations retain only
their domain-specific summaries and cross-links. Documentation must distinguish
current behavior from approved but not-yet-implemented behavior.

Recommended rollout:

1. Deploy signed issuance with legacy decoding enabled.
2. Observe legacy-use and cursor-failure metrics for at least one maximum cursor lifetime.
3. Rotate keys once in a non-production environment to verify previous-key handling.
4. Disable legacy decoding after usage reaches the agreed operational threshold.
5. Remove the legacy codec path in a later cleanup after compatibility is no longer required.

## Acceptance Criteria

- Model and online recommendation endpoints honor the same forward live-keyset contract.
- Every newly issued recommendation cursor is authenticated and bound to its user and normalized query.
- Legacy cursors can be temporarily accepted through explicit configuration.
- Invalid cursors fail before recall work and return a generic `400`.
- Responses emit a cursor exactly when `hasMore` is true.
- Exact terminal pages do not require an empty follow-up request.
- Candidate processing is bounded by configuration.
- The catalog endpoint retains its signed keyset behavior and index-backed SQL.
- No public production endpoint introduces offset pagination.
- Unit, integration, cross-path, security, and index-contract tests pass.
