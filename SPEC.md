# Spec: Consolidate `saga/` orchestrators onto a shared base

## Objective

`com.recsys.saga` has two orchestrators — `SagaOrchestrator` (compensation-based
saga) and `TccSagaOrchestrator` (Try/Confirm/Cancel) — that duplicate ~60 lines
of identical infrastructure: the `store`/`publisher`/`clock` fields and
constructor, `runWithRetry`, `transition`, `publish`, `now()`, and the
find-or-create + terminal-status skeleton. This spec extracts that shared
infrastructure into one base and merges both orchestrators into a single file as
nested classes — **with zero change to orchestration behavior or emitted
events.**

Pure internal refactor. No behavior change. The AWS Step Functions renderers are
out of scope (not selected for this pass).

### Who benefits
Maintainers of the saga reference implementation — one place to change retry,
event-publishing, and persistence wiring instead of two that can drift.

### Success looks like
- The duplicated infra exists once, in a shared base.
- The two orchestrators live in one file as nested classes.
- Every emitted `SagaTransitionEvent`, status transition, and exception message
  (modulo the unchanged step-name + "compensation/cancel errors" text the tests
  assert) is identical.
- `mvn test` is green after the mechanical test updates.

## Tech Stack

- Java 17, JUnit 5 + AssertJ. Build: Maven.

## Commands

```bash
# Compile
mvn package -DskipTests

# Saga tests
mvn test -Dtest='com.recsys.saga.*'

# Full suite
mvn test
```

## Scope — one consolidation (as selected)

### Extract a shared orchestrator base; merge both into `SagaOrchestrators.java`

New file `SagaOrchestrators.java` — a non-instantiable namespace holding an
abstract base plus the two concrete orchestrators as nested classes:

```java
public final class SagaOrchestrators {
    private SagaOrchestrators() {}

    /** Shared durable-orchestration infrastructure for both saga styles. */
    abstract static class Base {
        protected final SagaStateStore store;
        protected final SagaEventPublisher publisher;
        protected final Clock clock;
        private final String retryFailureNoun;   // "step" | "TCC step"

        protected Base(SagaStateStore store, SagaEventPublisher publisher, Clock clock,
                       String retryFailureNoun) { ... }

        protected SagaInstance findOrCreate(String sagaId, String correlationId,
                                            String payloadJson, SagaDefinition definition) { ... }
        protected void runWithRetry(SagaInstance saga, SagaStep step, SagaStepAction action) { ... }
        protected void transition(SagaInstance saga, SagaStatus status,
                                  SagaEventType eventType, String stepName) { ... }
        protected void publish(SagaInstance saga, SagaEventType type, String stepName) { ... }
        protected Instant now() { return clock.instant(); }
    }

    /** Compensation-based saga (was SagaOrchestrator). */
    public static final class Standard extends Base {
        public Standard(SagaStateStore store, SagaEventPublisher publisher, Clock clock) {
            super(store, publisher, clock, "step");
        }
        public SagaInstance execute(... actions, compensations) { ... }   // forward loop + compensate()
    }

    /** Try/Confirm/Cancel (was TccSagaOrchestrator). */
    public static final class Tcc extends Base {
        public Tcc(SagaStateStore store, SagaEventPublisher publisher, Clock clock) {
            super(store, publisher, clock, "TCC step");
        }
        public SagaInstance execute(... participants) { ... }   // tryAll/confirmAll + cancel()
    }
}
```

What moves to `Base` (identical in both today):
- fields `store`/`publisher`/`clock` + null-defaulting constructor
- `runWithRetry` — the only difference between the two copies is the failure noun
  (`"step failed after…"` vs `"TCC step failed after…"`), parameterized via
  `retryFailureNoun` passed by each subclass's constructor. The `: <stepName>`
  suffix is preserved, so tests asserting the step name still pass.
- `transition`, `publish`, `now`
- `findOrCreate` (the `store.find(...).orElseGet(create + SAGA_STARTED publish)`
  block, byte-identical in both)

What stays in each concrete class (genuinely different):
- `Standard`: the forward step loop and `compensate(...)` (its `"compensation
  errors"` wording, `markStepCompleted`/`markCompensated`).
- `Tcc`: `tryAll`/`confirmAll`/`cancelTriedButUnconfirmed`/`participantFor` (its
  `"cancel errors"` wording, tried/confirmed/cancelled marks).
- The terminal-status guard (`status==COMPLETED||FAILED → return`) stays inline at
  the top of each `execute` after `findOrCreate`.

Delete `SagaOrchestrator.java` and `TccSagaOrchestrator.java`.

### Explicitly out of scope (per review)
- The AWS renderers (`AwsStepFunctionsSagaDefinition`,
  `AwsTccStepFunctionsSagaDefinition`) and their duplicated `escape`/PascalCase/
  Retry-block/scan helpers — **left untouched** this pass.
- All small value/enum/interface types (`SagaStep`, `SagaInstance`, `SagaStatus`,
  `SagaEventType`, `SagaBackoff`, stores, exceptions) — unchanged.

## Project Structure (after)

```
saga/
  SagaOrchestrators.java                NEW — Base + Standard + Tcc (replaces 2 files)
  SagaInstance.java / SagaStep.java / SagaDefinition.java        (unchanged)
  SagaStateStore.java / InMemorySagaStateStore.java             (unchanged)
  SagaStatus.java / SagaEventType.java / SagaTransitionEvent.java (unchanged)
  SagaBackoff.java / SagaException.java / SagaConflictException.java (unchanged)
  SagaStepAction.java / TccParticipant.java / SagaEventPublisher.java (unchanged)
  AwsStepFunctionsSagaDefinition.java / AwsTccStepFunctionsSagaDefinition.java (unchanged)
```

## Code Style

- Container `public final` with private constructor; concretes are
  `public static final class … extends Base`.
- `Base` is package-private (`abstract static class Base`) — it's an internal
  implementation detail, not public API.
- Shared fields `protected final`; helper methods `protected`.
- Preserve every event id format, status value, and message string exactly
  (other than the parameterized retry noun, which reproduces today's two strings).

## Testing Strategy

JUnit 5 + AssertJ. No new tests. The existing `SagaOrchestratorTest` and
`TccSagaOrchestratorTest` are the safety net — they pin event sequences, status
transitions, compensation/cancel ordering, idempotent replay, and the
`"compensation errors"`/`"cancel errors"` failure messages. They need mechanical
constructor-name updates only:

- `new SagaOrchestrator(...)` → `new SagaOrchestrators.Standard(...)`
  (10 sites: `SagaOrchestratorTest` ×5, `TccSagaOrchestratorTest` ×5 → `.Tcc`)
- variable type declarations `SagaOrchestrator x` / `TccSagaOrchestrator x`
  → `SagaOrchestrators.Standard` / `SagaOrchestrators.Tcc`
- the `{@link SagaOrchestrator}` javadoc in `AwsStepFunctionsSagaDefinition`
  → `{@link SagaOrchestrators.Standard}`

Verify: `mvn test -Dtest='com.recsys.saga.*'` green, then full `mvn test` green.

## Boundaries

- **Always:** preserve emitted events, status transitions, ordering, and message
  text; update the saga tests in the same change and run them.
- **Ask first:** changing any event/status/message; touching the AWS renderers or
  any non-orchestrator saga type; changing public constructor signatures beyond
  the type rename.
- **Never:** alter orchestration semantics; delete a test to make the build pass.

## Success Criteria

1. `SagaOrchestrators.java` exists with `Base` + `Standard` + `Tcc`;
   `SagaOrchestrator.java` and `TccSagaOrchestrator.java` are deleted.
2. `runWithRetry`/`transition`/`publish`/`now`/`findOrCreate` exist once (in `Base`).
3. `git diff` shows no change to event id strings, `SagaStatus`/`SagaEventType`
   usage, or the `SagaTransitionEvent` construction.
4. `mvn test` green; no file outside `saga/` changed except the two saga tests'
   constructor names.

## Open Questions

1. **Nested names.** `Standard` / `Tcc` chosen for the concretes. Alternative:
   keep the old names as nested (`SagaOrchestrators.Saga` / `.Tcc`) or use
   `.Compensating` / `.Tcc`. Default: `Standard` / `Tcc`. Flag if you prefer
   different names.
