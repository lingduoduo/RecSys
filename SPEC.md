# Spec: Simplify `service/retrieval/`

## Objective

The multi-channel retrieval layer (`com.recsys.service.retrieval`) has grown to
14 files with repeated scoring/merge boilerplate across its recall channels,
plus a set of dead `QuotaSpec` factory methods left over from a pre-`QuotaPolicy`
design. This spec removes the duplication and the dead code, and collapses the
six `channels/` implementations into one file — **with zero change to recall
behavior, channel `name()` keys, scores, or ordering.**

Pure internal refactor. No new channels, no scoring-policy changes.

### Who benefits
Maintainers of all three serving paths that wire these channels — offline
(`RecSysServer`, 6010), model (`ModelRuntimeProvider`, 8080), online
(`OnlinePredictionServer`, 7010). One source of truth for the rank-decay /
blend / comparator patterns; less copy-paste drift.

### Success looks like
- The four duplicated patterns exist once, in a shared helper.
- Dead `QuotaSpec.warm/cold` statics are gone.
- `channels/` is one file instead of six.
- Every channel still emits the same `name()`, scores, and candidate ordering.
- `mvn test` is green (after the mechanical test updates below).

## Tech Stack

- Java 17, SLF4J, JUnit 5 + Mockito, AssertJ
- Build: Maven

## Commands

```bash
# Compile
mvn package -DskipTests

# Retrieval-focused tests
mvn test -Dtest='com.recsys.service.retrieval.**,RecommendationOrchestratorTest'

# Full suite
mvn test
```

## Scope — three consolidations (all approved)

### 1. Extract shared helpers → new `RecallScoring`

New package-local class `com.recsys.service.retrieval.RecallScoring` (next to the
`RecallChannel` interface). Holds the four duplicated patterns:

```java
public final class RecallScoring {
    private RecallScoring() {}

    /** Candidate ordering used everywhere: score desc, then itemId asc (stable, deterministic). */
    public static final Comparator<MovieCandidate> BY_SCORE_DESC =
            Comparator.comparingDouble(MovieCandidate::score).reversed()
                    .thenComparing(MovieCandidate::itemId);

    /** Rank-decay candidates: ids[i] -> score 1/(i+1), tagged with the channel name. */
    public static List<MovieCandidate> rankScored(List<String> ids, String channel) { ... }

    /** Merge ids into `into` with rank-decay weighted by `weight`: into[id] += weight * 1/(rank+1). */
    public static void blendRankDecay(Map<String, Double> into, List<String> ids, double weight) { ... }

    /** Apply blendRankDecay across each (window -> weight) entry, pulling getTopKIds(window, limit). */
    public static void blendWindows(Map<String, Double> into, TrendingStore store,
                                    Map<String, Double> weights, int limit) { ... }

    /** Parse the numeric user id, empty if non-numeric (the common channel guard). */
    public static OptionalInt parseUserId(RecommendationQuery query) { ... }
}
```

Refactor call sites:
- `BY_SCORE_DESC` → replaces the 5 inline comparators (`MultiChannelRecallService`
  ×4, `UserSimilarityChannel` ×1).
- `rankScored` → `Embedding`, `Popularity` (redis branch), `OnlineRecentHistory`.
- `blendWindows` / `blendRankDecay` → `Trending` (windows) and `ColdStart`
  (windows + popularity).
- `parseUserId` → `UserSimilarity` and `OnlineRecentHistory` (the identical
  `try/parseInt → List.of()` guard). `MultiChannelRecallService` keeps its own
  parse (its non-numeric branch picks the cold quota, not empty — different
  behavior, left as-is). `Embedding`/`GenreHistory` keep the bare `parseInt`
  (they intentionally throw on bad input → recorded as a channel failure).

### 2. Remove dead `QuotaSpec.warm(int)` / `QuotaSpec.cold(int)`

These statics are unused in production — `QuotaPolicy` supersedes them and
`QuotaPolicy.defaultMovie()` reproduces their numbers exactly. The `QuotaSpec`
record itself (constructor, `slots()`, `slotsFor()`) stays — it's the per-request
slot carrier used by `QuotaPolicy` and `MultiChannelRecallService`.

- Delete `QuotaSpec.warm(int)` and `QuotaSpec.cold(int)`.
- Delete `QuotaSpecTest.java` (it exclusively tests the removed statics).
- Rewrite the one `QuotaPolicyTest` line that uses `QuotaSpec.cold(limit).slots()`
  as a golden value — replace with the literal expected slot map.

### 3. Merge `channels/` six files → one `Channels.java`

Collapse the six `RecallChannel` impls into one container with public nested
static classes (the pattern used in the serving refactor). `ColdStartChannel`
stays in `coldstart/` (it's grouped with the quota logic, not in `channels/`).

```
channels/Channels.java
  Channels.Embedding             (was EmbeddingChannel)
  Channels.GenreHistory          (was GenreHistoryChannel)
  Channels.Popularity            (was PopularityChannel)
  Channels.Trending              (was TrendingChannel)
  Channels.UserSimilarity        (was UserSimilarityChannel; keeps its private
                                  Neighbor / CandidateScore records + helpers)
  Channels.OnlineRecentHistory   (was OnlineRecentHistoryChannel)
```

Each nested class keeps its existing constructors, fields, and — critically — its
exact `name()` string. Construction sites change from `new EmbeddingChannel(x)`
to `new Channels.Embedding(x)`.

## Project Structure (after)

```
service/retrieval/
  RecallChannel.java                         (unchanged interface)
  RecallScoring.java                         NEW shared helper
  channels/Channels.java                     NEW — 6 nested channels (replaces 6 files)
  coldstart/ColdStartChannel.java            refactored to use RecallScoring
  coldstart/QuotaPolicy.java                 (unchanged)
  coldstart/QuotaSpec.java                   warm/cold statics removed
  multichannel/MultiChannelRecallService.java  uses RecallScoring.BY_SCORE_DESC
  multichannel/RecallConfig.java             (unchanged)
  multichannel/ChannelHealthMonitor.java     (unchanged)
  rulebased/ItemEmbeddingJob.java            (unrelated job — untouched)
```

## Code Style

- Container class is `public final` with a private constructor; channels are
  `public static final class` extending nothing (they `implements RecallChannel`).
- `name()` strings are the wire/quota contract — never change them; a comment in
  `Channels.java` notes this.
- Helper methods are `static`, side-effecting variants take the target map as the
  first arg (`into`), matching the existing `merge`-into-map style.
- Preserve every existing constant (`SCORE`, `FALLBACK_SCORE`, `PREDEFINED_WEIGHTS`,
  `MAX_NEIGHBORS`, recency-boost numbers) verbatim.

## Routes / Contract — MUST stay identical (hard constraints)

| Channel | `name()` (unchanged) | Old type → New type |
|---|---|---|
| embedding | `embedding` | `EmbeddingChannel` → `Channels.Embedding` |
| genre history | `genre_history` | `GenreHistoryChannel` → `Channels.GenreHistory` |
| popularity | `popularity` | `PopularityChannel` → `Channels.Popularity` |
| trending | `trending` | `TrendingChannel` → `Channels.Trending` |
| user similarity | `user_similarity` | `UserSimilarityChannel` → `Channels.UserSimilarity` |
| online recent history | `online_recent_history` | `OnlineRecentHistoryChannel` → `Channels.OnlineRecentHistory` |

These strings are keys in `QuotaPolicy.defaultMovie/defaultOnline/defaultModelRetrieval`
fraction maps — a typo silently breaks quota allocation.

## Testing Strategy

JUnit 5 + Mockito + AssertJ. No new tests required — existing per-channel and
service tests are the safety net. Mechanical updates only:

- **Production wiring** (construct channels): `serving/RecSysServer.java`,
  `model/service/ModelRuntimeProvider.java`,
  `online/serving/OnlinePredictionServer.java` — update `new XxxChannel(...)` →
  `new Channels.Xxx(...)` and imports.
- **Channel tests** (6 files, references only — assertions unchanged):
  `EmbeddingChannelTest`, `GenreHistoryChannelTest`, `PopularityChannelTest`,
  `TrendingChannelTest`, `UserSimilarityChannelTest`, `OnlineRecentHistoryChannelTest`.
- **QuotaSpec cleanup:** delete `QuotaSpecTest.java`; fix one golden line in
  `QuotaPolicyTest.java`.

Verification order: retrieval test subset green → full `mvn test` green.

## Boundaries

- **Always:** preserve channel `name()` strings, scores, candidate ordering, and
  all numeric constants; update wiring + tests in the same change and run them.
- **Ask first:** changing any `name()` string, score formula, or `QuotaPolicy`
  numbers; touching `MultiChannelRecallService`'s merge logic beyond swapping in
  the shared comparator; touching anything outside `service/retrieval` + the named
  wiring/test files.
- **Never:** add/remove a channel or endpoint; change wire output; delete a test
  that covers live code (only `QuotaSpecTest`, which covers dead code, is removed —
  and that was explicitly approved).

## Success Criteria

1. `RecallScoring` exists and is the sole home of the comparator, `rankScored`,
   `blendRankDecay`/`blendWindows`, and `parseUserId`.
2. The 5 inline comparators, 3 inline rank-decay loops, and 2 window-blend loops
   are replaced by helper calls.
3. `QuotaSpec.warm/cold` and `QuotaSpecTest` are deleted; `QuotaSpec` record API
   otherwise intact.
4. `channels/` is a single `Channels.java`; the 6 old files are deleted; all
   construction sites compile against the nested names.
5. All 6 channel `name()` strings unchanged; `git diff` shows no edits to score
   constants or quota fractions.
6. `mvn test` green; no production code outside `service/retrieval` changed except
   the 3 wiring files.

## Open Questions

1. **`Channels.java` size.** Folding `UserSimilarity` (169 lines) in makes one
   ~450-line file. Approved as "merge channel files"; flag if you'd prefer
   `UserSimilarity` kept as its own file (it's the one heavyweight channel) and
   only the five lightweight channels merged.
2. **SPEC.md reuse.** This overwrites the serving-refactor `SPEC.md` (already
   captured in PR #134). If you want both kept on disk, say so and I'll name this
   `SPEC-retrieval.md`.
