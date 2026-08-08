# Reject unsigned pagination cursors by default

Make the signed-cursor mechanism the actual behavior instead of an option the deployed
configuration opts out of.

## The gap

`RecommendationCursorCodec.decode` routes any token without a `.` to `decodeLegacy`, and
`RECOMMENDATION_CURSOR_ACCEPT_LEGACY` defaults to `true` — in
`RecommendationPaginationConfig` and again explicitly in `k8s/base/configmap.yaml`.

The signed path validates four things:

| Check | Signed path | Legacy path |
|---|---|---|
| HMAC signature (active or previous key) | yes | **no** |
| Expiry against `RECOMMENDATION_CURSOR_MAX_AGE_SECONDS` (900 s) | yes | **no** |
| Cursor `userId` equals the request's `userId` | yes | **no** |
| Query fingerprint — `k`, exclusions, variant | yes | **no** |

`decodeLegacy` base64-decodes the token, checks a `v2:` prefix, splits on `:`, and returns the
position. So a caller can hand-craft `base64("v2:<score>:<itemId>")` and seek to an arbitrary
position in a ranked list, with no signature, no lifetime, and no binding to the query that
produced it.

**What this is and is not.** It is not cross-user disclosure: `userId` comes from the request, not
the cursor, and since PR #275 a user-tier caller may only name their own. It is an integrity bypass
of the pagination contract within a caller's own results, plus a cursor with unbounded lifetime.
The structural problem is worse than the immediate impact — a migration affordance is switched on
in production configuration with no sunset, and while it stays on the signed mechanism it was meant
to bridge from is fully bypassable.

Found while auditing the "submit-token, pagination-cursor and outbox payload handling" dimension of
the 2026-08-05 zero-data-leakage audit, which had never been examined. The other two components in
that dimension are clean: submit tokens are random UUIDs held in Redis under a TTL with an atomic
compare-and-delete consume, carrying no user data; outbox event payloads carry a `userId` but only
to internal transport (Redis outbox, then Kafka or SQS), never to a third party.

## Why it is safe to flip now

Signed cursors shipped in `5ecd0a8` on 2026-07-27. A signed cursor's maximum age is 900 seconds.
Any legacy cursor a client still holds was therefore issued more than nine days ago, against a
mechanism whose own successor expires in fifteen minutes. No live pagination session spans that gap.

Nothing in the codebase issues a legacy cursor — `LEGACY_PREFIX` appears only in `decodeLegacy`, and
`encode` has always produced `payload.signature`. The only sources are builds that predate
`5ecd0a8`.

This is worth stating precisely: the change is empty in practice **by elapsed time**, not by
construction. A caller holding a pre-2026-07-27 cursor would start receiving a cursor rejection.

## The change

**Flip the default in code**, in `RecommendationPaginationConfig`: `RECOMMENDATION_CURSOR_ACCEPT_LEGACY`
defaults to `false`. Doing it in code rather than only in the manifest means safe-by-default holds
everywhere — local runs, tests, and any future overlay — instead of only where a ConfigMap remembers
to say so.

**Set it explicitly to `"false"` in `k8s/base/configmap.yaml`** as well, so an operator reading the
manifest still sees the knob rather than having to know a code default.

**Keep `decodeLegacy` and the environment variable.** They become the escape hatch: if something
unexpected surfaces, flipping configuration is faster than redeploying code. Deleting the path is
the more aggressive option and belongs in a later cleanup, once the flag has sat at `false` through
a release.

The cost of keeping the hatch, stated plainly: the bypass remains one configuration flip away
indefinitely. That is why the default is pinned by a test rather than merely changed.

## Testing

One new test asserting `RecommendationPaginationConfig.fromEnvironment` yields `acceptLegacy=false`
when the variable is unset. The entire finding is that a permissive default reached production
configuration unnoticed; a test on the default is what stops it drifting back, and it is the only
assertion that would have caught this in the first place.

Behavioral coverage of both flag states already exists — `CrossPathConsistencyTest` constructs
fixtures with `acceptLegacy` true and false — so no new behavioral test is needed. The existing
`CursorFailureReason.LEGACY_DISABLED` path is what a rejected legacy cursor already produces.

The test goes in the `resilience` profile in `pom.xml`, which is what the PR gate runs.

## Documentation

`docs/system_design/19_Pagination.md`: record that legacy cursor acceptance is off by default, the
evidence that made that safe (signed cursors from 2026-07-27, 900-second maximum age), that the
environment variable remains as an escape hatch rather than a supported mode, and that a caller
holding a pre-2026-07-27 cursor receives a rejection.
