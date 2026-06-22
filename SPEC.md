# Spec: Remove dead `service/feedback/` package

## Objective

Simplify `com.recsys.service` by deleting the unused `feedback/` package. It is
dead code — `FeedbackService`, `EventPublisherService`, and `FeedbackEvent` have
**zero references** anywhere in production or tests, and are functionally
superseded by the live event pipeline in `com.recsys.online.event`
(`AsyncEventPublisher`, `ExperienceCollector`, etc.). Removing it shrinks the
service surface with no behavior change.

Pure deletion. No new code, no refactor of surviving classes.

### Who benefits
Maintainers of `service/` — three fewer files and one fewer package to reason
about; no more "is this the feedback path?" ambiguity against the real
`online/event` path.

### Success looks like
- `service/feedback/` no longer exists.
- Nothing else changes; `mvn test` stays green.

## Tech Stack

- Java 17, JUnit 5 + Mockito. Build: Maven.

## Commands

```bash
# Compile
mvn package -DskipTests

# Full suite (no feedback tests exist; this confirms nothing referenced it)
mvn test
```

## Scope

Delete the three files in `src/main/java/com/recsys/service/feedback/`:

```
service/feedback/
  FeedbackService.java          DELETE — only references EventPublisherService/FeedbackEvent
  EventPublisherService.java    DELETE — interface, only used by FeedbackService
  FeedbackEvent.java            DELETE — record, only used by FeedbackService/EventPublisherService
```

Reference audit (already run): `grep -rn` for each symbol across `src/` finds
matches **only within the package itself** — no production wiring, no tests, no
config. The package is self-contained dead code.

### Explicitly out of scope (per review)
- **`ScoreRanker` comparator reuse** — `ScoreRanker` duplicates
  `RecallScoring.BY_SCORE_DESC`, but consolidating it was considered and
  deliberately left out; the comparator stays in `RecallScoring` and
  `ScoreRanker` is untouched.
- `model/service/RankingStage` uses a *score-only* sort (no `itemId` tiebreak) —
  a genuinely different comparator, not a duplicate.
- The live `online/event/` publishers — unrelated, kept.

## Project Structure (after)

```
service/
  feedback/                 REMOVED
  hydrator/                 (unchanged)
  pagination/               (unchanged)
  ranking/                  (unchanged — ScoreRanker left as-is)
  recommendation/           (unchanged)
  retrieval/                (unchanged — from PR #135)
```

## Code Style

N/A — deletion only. No surviving file is edited.

## Testing Strategy

JUnit 5 + Mockito. No tests reference the deleted package, so none are removed or
rewritten. The existing full suite is the safety net: a green `mvn test` after
deletion proves nothing depended on `feedback/`.

## Boundaries

- **Always:** confirm zero references before deleting; run the full suite after.
- **Ask first:** deleting anything outside `service/feedback/`; touching
  `ScoreRanker`, `RecallScoring`, or the `online/event` publishers.
- **Never:** remove a referenced class; delete a test to make the build pass.

## Success Criteria

1. `src/main/java/com/recsys/service/feedback/` is gone (all 3 files).
2. No other production file is modified.
3. `mvn test` is green (BUILD SUCCESS); no test count change (there were no
   feedback tests).
4. `grep -rn "FeedbackService\|EventPublisherService\|service\.feedback" src`
   returns nothing.

## Open Questions

1. **`FeedbackEvent` as future API?** It's a clean validated record a future
   feedback endpoint might want. Default is to delete it (dead today; recoverable
   from git history). Flag if you'd rather keep the package as a placeholder.
