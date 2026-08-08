# PostHog pseudonymous distinct_id

Stop application user identifiers from reaching a third-party SaaS, without giving up per-user
gradual rollout.

## The gap

`RecommendationService` gates cold-start recommendations on a dynamic feature flag:

```java
&& featureFlagService.isEnabled(Flags.COLD_START_ENABLED, request.getUserId())
```

That `userId` becomes `distinct_id` in a POST to `https://us.i.posthog.com/decide/?v=3`
(`PostHogFeatureFlagProvider.resolve`). It is the only path in the system that sends an
application user identifier to an external service.

Finding #4 of the 2026-08-05 zero-data-leakage audit, ranked fourth of four.

**How dormant it actually is**, because the audit's "moderate" ranking overstates it:

- One call site. Nothing else passes a `distinctId` to `FeatureFlagService`.
- Double-gated. `FeatureFlagConfig` constructs the provider only when
  `POSTHOG_FEATURE_FLAGS_ENABLED=true` *and* the API key is non-blank. Defaults are `false` and
  empty.
- Absent from Kubernetes. No overlay or base manifest sets any `POSTHOG_*` variable, so the
  defaults hold in every environment.
- Unroutable. `k8s/base/network-policy.yaml` permits no egress to PostHog — indeed none on 443 at
  all, which `20_AuthN_AuthZ` records as sharp edge 9. On an enforcing CNI the request would fail
  at the network layer before disclosing anything.

The risk is therefore not "we are leaking user ids". It is that two environment variables silently
turn a recommendation path into a disclosure path, with nothing in the code marking that as a
decision.

## Scope

In scope: the identifier PostHog receives.

Out of scope:

- Removing PostHog. The per-user gradual rollout it provides is a deliberate capability; deleting
  it is a product decision, not a security cleanup.
- The `person_properties` map. `FeatureFlagService.isEnabled(flag, distinctId)` passes `Map.of()`
  at the one call site, so nothing populates it today. If it ever carries user attributes that is a
  fresh disclosure decision, and this design does not pre-authorize it.
- Egress policy for PostHog. Nothing permits it today and this design does not add permission; if
  PostHog is ever enabled in a cluster, the NetworkPolicy work is separate.
- The other three audit findings, all shipped in PRs #274, #275 and #276.

## The change

`PostHogFeatureFlagProvider` hashes the `distinctId` before it enters the request body:

```
distinct_id = sha256Hex(salt + ":" + userId)     // full 64-character lowercase hex
```

Full digest, not a prefix. `GatewayPrincipal.sha256Prefix` truncates to six bytes because a
rate-limit bucket tolerates collisions; a flag-targeting key does not, and the full digest costs
nothing here.

Three properties make this work as a feature-flag key: it is **deterministic**, so PostHog's
percentage rollouts bucket a given user consistently; **stable across pods and restarts**, because
the salt is shared configuration rather than per-process state; and **one-way**, so PostHog holds
no application identifier.

**The salt is required, and its absence fails startup.** A new `POSTHOG_DISTINCT_ID_SALT`
(`recsys.feature-flags.post-hog.distinct-id-salt`) has no default. If PostHog is enabled and the
salt is blank, `PostHogFeatureFlagProvider`'s constructor throws, exactly as it already throws on a
blank API key.

Failing closed is the whole point rather than a stylistic preference. Application userIds here are
small integers — `"1"`, `"42"` — so an *unsalted* SHA-256 is reversible by brute force over a
trivial key space; a rainbow table covering a million values is seconds of work. An unsalted hash
would look like a control and provide none. The two other ways to handle a missing salt are both
worse: sending the raw id defeats the change, and generating a per-process random salt makes
bucketing differ per pod and per restart, so gradual rollout breaks silently instead of loudly.

**Hashing happens at the boundary**, not in `FeatureFlagService`. Only the component that talks to
the third party needs the pseudonym. `EnvFeatureFlagProvider` and `CachingFeatureFlagProvider` keep
the real id — the cache key stays legible in-process, and a general service is not coupled to one
vendor's privacy concern.

Hashing is a private helper in the provider. SHA-256 appears locally in about a dozen classes in
this repo and there is no shared utility; introducing one is a refactor this change does not need.

## What it costs

**Per-user lookup in PostHog goes away.** Nobody can ask PostHog "what did user 42 get" any more,
because PostHog no longer knows about user 42. That is the intended effect, and it removes a
debugging path that anyone enabling the flag might expect to have.

**Rotating the salt re-buckets every user.** A rollout in flight would reshuffle which users are
inside it. The salt should be treated as long-lived configuration, and the runbook says so.

## Testing

- The hash is deterministic and stable: the same userId and salt produce the same `distinct_id`
  across separate provider instances.
- Different salts produce different `distinct_id`s for the same userId, which is what makes the
  salt load-bearing.
- The raw userId never appears in the request body. Asserted against the serialized JSON, not
  against a field — the test should fail if a future change adds the userId under any other key.
- A blank or absent salt with PostHog enabled fails construction, with the message naming the
  variable.
- Existing behavior is unchanged: a blank `distinctId` still short-circuits to `Optional.empty()`
  before any request is built, and flag resolution still returns what PostHog returns.

Tests go in the `resilience` profile in `pom.xml`, which is what the PR gate runs.

## Documentation

- `docs/system_design/20_AuthN_AuthZ.md`: note that the only third-party identifier path sends a
  salted hash, that PostHog remains disabled and unrouted, and that rotating the salt re-buckets
  every user so it is long-lived configuration. There is no feature-flags runbook and this change
  does not justify creating one, so the rotation caveat lives here.
- `.claude/CLAUDE.md`: `POSTHOG_DISTINCT_ID_SALT` in the env-var list, marked required-when-enabled.
