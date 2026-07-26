# Fault-Tolerance Hardening — Design

**Date:** 2026-07-26  
**Status:** Approved (design)  
**Author:** brainstormed with Codex

## Problem

The service already has layered fault tolerance: route circuit breakers, bounded
recall bulkheads, admission controls, per-channel timeouts, fail-open Redis
guards, graceful drain, durable outbox delivery, Saga compensation, multi-AZ
deployment, and a warm-standby region. The deterministic resilience suite is
healthy, but the investigation identified four residual risk classes:

1. Redis rate limiting fails open without a local emergency ceiling, so a Redis
   outage can remove the only cluster-wide traffic bound.
2. The shared circuit breaker does not associate recovery completion with the
   admitted half-open probe. A stale request admitted before the circuit opened
   can race with recovery and mutate the new circuit state.
3. Partial recall degradation is observable, but an entirely degraded empty
   response can still look too similar to a healthy empty recommendation.
4. Heavy resilience suites are excluded from normal Maven test execution,
   dependency convergence is not enforced, and regional promotion remains a
   manual sequence with limited machine-verifiable evidence.

This design reduces those risks without replacing the existing resilience
primitives, changing the system's availability-first posture, or automating an
unsafe data-tier or traffic cutover.

## Goals

- Bound traffic locally while the global Redis limiter is failing open.
- Make circuit-breaker recovery transitions safe under concurrent completion.
- Make wholly degraded recommendations explicit to clients and operators.
- Fail builds on dependency convergence problems, including mixed Netty lines.
- Run deterministic resilience checks on every pull request and environmental
  load/chaos checks on a schedule.
- Make standby promotion idempotent, validated, and auditable.
- Keep data-tier promotion and regional traffic cutover operator-confirmed.
- Introduce no new production runtime dependency or infrastructure service.

## Non-Goals

- Replacing the existing primitives with Resilience4j or another library.
- Globally changing fail-open dependencies to fail closed.
- Fully automating Aurora, ElastiCache, Route53, or equivalent traffic promotion.
- Selecting production thresholds without production-like measurements.
- Enforcing absolute latency thresholds on shared CI hardware.
- Redesigning recommendation ranking, recall quotas, or public API payloads
  unrelated to degradation signaling.

## Decisions

| Area | Decision |
|---|---|
| Delivery shape | Three independently reviewable phases: runtime, CI, DR |
| Outage posture | Bounded fail-open |
| Runtime dependencies | No new production library or service |
| Healthy API contract | Preserve existing status codes, headers, and latency |
| Emergency rejection | Existing `429` + `Retry-After` contract |
| DR automation | Automate preparation and validation |
| Cutover | Explicit operator confirmation |
| Performance gates | Enforce invariants; report environment-sensitive latency |

## Phase 1: Runtime Containment

### Emergency local admission

`RedisRateLimiter` remains the cluster-wide authority while Redis is available.
When a Redis evaluation fails or the embedded circuit refuses a request, the
request must pass a conservative per-instance emergency limiter before it is
allowed.

The emergency limiter uses the repository's existing token-bucket primitive. It
has a separate capacity/rate configuration because the correct safe degraded
rate is not necessarily the normal global rate divided by the current replica
count. Configuration must:

- default to a conservative, enabled value when the Redis limiter itself is
  enabled;
- permit an explicit disable for rollback;
- reject invalid negative values at startup rather than silently widening the
  degraded path;
- leave the emergency limiter inactive when global Redis limiting is disabled.

The request flow is:

1. Global limiting disabled: allow normally; emergency limiting is irrelevant.
2. Circuit permits Redis call and Redis returns a decision: honor that decision.
3. Redis call throws: record the circuit failure, then consult the emergency
   limiter.
4. Circuit is open or another request owns the half-open probe: consult the
   emergency limiter without calling Redis.
5. Emergency allowance: return allowed with `failOpen=true`.
6. Emergency rejection: return rejected with `failOpen=true` and a positive
   `Retry-After`.

The existing `Decision` surface should be extended only if callers need to
distinguish an emergency rejection from an authoritative Redis rejection.
Existing callers must continue using the same HTTP `429` response path.

Micrometer and the online operations snapshot must expose:

- Redis-authoritative allows and rejects;
- fail-open emergency allows and rejects;
- current circuit state;
- emergency limiter capacity/rate configuration.

Metrics must not use bucket or principal values as tags, avoiding unbounded
cardinality.

### Circuit-breaker recovery ownership

The circuit breaker must return an acquisition token/permit that identifies
whether the call was admitted in CLOSED state or as the unique HALF_OPEN probe.
Completion is recorded against that permit.

Required semantics:

- CLOSED calls can contribute consecutive failures while the breaker remains in
  the same generation.
- Opening the breaker advances its generation.
- Only the permit for the current generation's HALF_OPEN probe may close or
  reopen recovery.
- Success or failure from an older generation is ignored for state-transition
  purposes.
- Exactly one half-open probe is live at a time.
- Existing route and Redis wrappers retain their public behavior.

This removes the stale-completion race without serializing the request path or
adding a global lock.

### Explicit recall degradation

The current `X-Recall-Degraded` header remains the compatibility surface. It
will be supplemented with a bounded reason value derived from the existing
degradation classification:

- `partial`: at least one channel degraded and useful candidates remain;
- `all_channels`: every attempted channel degraded;
- `fallback`: the response was recovered exclusively from a non-personalized
  floor or cached result;
- absent/healthy: no degradation occurred.

The reason must be exposed through a response header and aggregate metrics, not
through unbounded channel-name metric tags. A naturally empty, healthy recall
must remain distinguishable from an empty result caused by unavailable
channels. Existing successful recommendation status codes remain unchanged.

### Dependency convergence

Maven dependency management will align Netty artifacts to a single compatible
line selected by the framework BOM already used by the application. Maven
Enforcer dependency convergence (or the narrowest equivalent already available
in the build) will run during validation.

The change must remove the Armeria inconsistent-Netty warning in resilience
tests. It must not suppress the warning with a JVM flag.

## Phase 2: Continuous Resilience Verification

### Pull-request gate

A named Maven resilience profile will run deterministic tests for:

- circuit-breaker concurrency and stale completion;
- emergency limiter allow/reject/recovery behavior;
- bulkhead saturation and channel degradation;
- admission and graceful drain;
- outbox lease, deadline, retry, and dead-event transitions;
- Saga and TCC compensation;
- degraded-response headers and reasons;
- dependency convergence.

The profile must be suitable for every pull request: no Docker daemon, cloud
credentials, production endpoints, or timing-sensitive absolute latency checks.
Each asynchronous test must have an explicit deadline.

### Scheduled environmental verification

GitHub Actions will run the existing `load` and `docker` groups on a schedule and
through manual dispatch. Jobs will have workflow and test-level timeouts.

Machine-readable artifacts will record:

- offered and accepted concurrency;
- admission and bulkhead rejection counts;
- degraded recommendation ratio;
- timeout and recovery behavior;
- Redis boundary-limit behavior;
- graceful-drain completion;
- test environment metadata.

Correctness invariants fail the workflow. Hardware-sensitive latency and
throughput measurements are uploaded and summarized but initially do not fail
on fixed numeric thresholds. This avoids teaching shared-runner variance to look
like a reliability regression.

### Release evidence

The scheduled workflow output is retained as release evidence. A manual run is
the pre-release procedure for changes to admission, retry, timeout, cache,
messaging, or deployment behavior.

## Phase 3: Safe DR Orchestration

### Command model

The existing `scripts/dr-standby-capacity.sh` workflow will evolve into
idempotent commands with explicit region and cluster context:

- `promote`: apply only standby capacity changes, then validate readiness;
- `verify`: perform all read-only readiness and drift checks;
- `demote`: restore the warm floor without changing traffic or data roles;
- `cutover-check`: read-only proof that prerequisites for operator cutover hold;
- `failback-check`: read-only proof that failback prerequisites hold.

No command in this phase changes a database writer, Redis primary, or public DNS
record.

### Preconditions and validation

Every mutating command must validate before applying:

- Kubernetes context and target region match the requested standby;
- rendered overlays contain no placeholder image and preserve image identity;
- primary and standby manifests satisfy the existing drift rules;
- the requested operation is valid from the observed capacity state.

Promotion readiness requires:

- expected HPA minimums and maximums;
- successful rollout and ready replicas;
- schedulable topology spread;
- PDB health;
- service readiness endpoints;
- configured dependency reachability;
- no ambiguous partial apply.

The scripts must stop on unknown or contradictory state. Re-running after a
partial failure must safely converge or continue validation.

### Auditable report

Each command writes a versioned JSON report containing:

- command, timestamp, target region, and cluster context;
- source commit and rendered manifest digest;
- each check, its observed value, and pass/fail status;
- capacity changes attempted;
- final readiness result;
- explicit remaining operator actions.

Human-readable output remains concise and is derived from the same check
results.

### Operator-confirmed cutover

Runbooks will keep data promotion and traffic cutover as separate explicit
steps. `cutover-check` succeeds only when evidence shows that:

- standby application capacity is ready;
- required data tiers report the intended writable/primary roles;
- replication lag or accepted RPO is recorded;
- the operator has selected the intended traffic target.

The tool provides evidence; it does not infer authority to promote data or
traffic.

Failback follows the same model and refuses readiness while replication
direction, writer identity, health, or capacity is ambiguous.

### DR testing

Shell tests will cover rendered manifests and scripted command responses without
accessing production. Tests include wrong-context refusal, placeholder-image
refusal, partial rollout, unhealthy PDB, idempotent re-run, report generation,
and confirmation that preparation commands never invoke data-tier or DNS
mutation.

A scheduled dry-run renders both regions and runs `verify` against fixtures.
The existing game-day remains the end-to-end validation against real
infrastructure.

## Rollout and Rollback

The phases deploy independently:

1. Ship circuit ownership, emergency limiting, degradation signals, and
   dependency alignment behind configuration where appropriate.
2. Make deterministic CI required after it is stable; introduce scheduled
   workflows as non-blocking for one observation cycle, then enforce invariants.
3. Deploy DR verification first, exercise it in a game day, then allow its
   promote/demote capacity mutations.

Rollback paths:

- emergency limiting can be explicitly disabled, restoring current fail-open
  behavior;
- response reason headers are additive and can be disabled without changing
  response bodies;
- CI scheduled jobs can be made non-blocking without removing test coverage;
- DR `demote` restores warm capacity and never changes data or traffic roles.

Circuit recovery ownership and Netty convergence are correctness fixes and do
not receive behavior rollback flags; they are reverted through normal code
rollback if necessary.

## Testing Strategy

All runtime behavior changes follow test-driven development:

1. Add one failing behavioral test.
2. Confirm it fails for the intended missing guarantee.
3. Add the minimum implementation.
4. Run the focused test and adjacent suite.
5. Refactor only while green.

Concurrency tests use injectable clocks and coordination barriers rather than
sleep-based probability. DR tests use fixtures or stub executables and assert
observable commands/reports, never production mutation.

The final verification set includes:

- the required deterministic resilience profile;
- the normal Maven unit suite;
- Maven dependency convergence;
- Kubernetes overlay rendering;
- DR shell tests;
- one manual scheduled-workflow dispatch when GitHub execution is available.

## Acceptance Criteria

1. Redis failure or an open Redis circuit cannot admit more than the configured
   per-instance emergency rate, and both allowances and rejections are visible.
2. Only the current half-open probe can transition a recovering breaker; a
   deterministic concurrent test proves stale completion is ignored.
3. Clients and operators can distinguish healthy empty, partially degraded,
   wholly degraded, and fallback-only recommendation outcomes.
4. Maven validation uses one compatible Netty line and fails future convergence
   conflicts.
5. Deterministic resilience checks run on every pull request; scheduled
   environmental checks are bounded and publish machine-readable evidence.
6. Standby promotion is idempotent and produces an auditable readiness report.
7. DR preparation commands cannot mutate data-tier roles or public traffic.
8. Existing healthy-path API behavior remains compatible and no new production
   runtime dependency or infrastructure service is introduced.
