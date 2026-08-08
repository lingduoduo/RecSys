# PostHog Pseudonymous distinct_id Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the application `userId` reaching PostHog, without losing per-user gradual rollout.

**Architecture:** `PostHogFeatureFlagProvider` hashes the caller-supplied `distinctId` with a required salt before it enters the request body, so PostHog receives a deterministic, stable, one-way pseudonym instead of an application identifier. A blank salt while PostHog is enabled fails construction, because an unsalted digest over this id space is trivially reversible.

**Tech Stack:** Java 17, Spring Boot configuration properties, Jackson, JUnit 5, AssertJ, Maven.

## Global Constraints

- Build/test with JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. Newer JDKs fail a clean compile of two pre-existing files — a known pre-existing condition, not something you introduced.
- Design doc: `docs/superpowers/specs/2026-08-05-posthog-pseudonymous-distinct-id-design.md`. Read it before starting.
- `distinct_id = sha256Hex(salt + ":" + userId)`, **full 64-character lowercase hex**, not a prefix. `GatewayPrincipal.sha256Prefix` truncates to six bytes because a rate-limit bucket tolerates collisions; a flag-targeting key does not.
- **The salt is required.** Blank or absent while PostHog is enabled must fail construction, exactly as a blank API key already does. Never fall back to sending the raw id, and never generate a per-process random salt — that would make bucketing differ per pod and break gradual rollout silently.
- Hashing happens at the boundary, in `PostHogFeatureFlagProvider` only. `EnvFeatureFlagProvider` and `CachingFeatureFlagProvider` keep the real id.
- Existing behavior must not change otherwise: a blank `distinctId` still short-circuits to `Optional.empty()` before any request is built, and flag resolution still returns what PostHog returns.
- Tests must be added to the `resilience` profile in `pom.xml`, which is what the PR gate runs.
- Never merge to `main` directly — this work ships as a PR.
- Branch: `feat/posthog-pseudonymous-distinct-id` (already created; the spec is already committed on it).

---

### Task 1: Hash the distinct_id at the PostHog boundary

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/featureflags/providers/PostHogFeatureFlagProvider.java`
- Modify: `src/main/java/com/recsys/config/FeatureFlagConfig.java`
- Modify: `src/main/resources/application.yml:25-31`
- Test: `src/test/java/com/recsys/infrastructure/featureflags/providers/PostHogFeatureFlagProviderTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `PostHogFeatureFlagProvider(String apiKey, String distinctIdSalt, URI host, Duration requestTimeout)` and the five-argument test form `PostHogFeatureFlagProvider(String apiKey, String distinctIdSalt, URI host, Duration requestTimeout, HttpClient httpClient, ObjectMapper objectMapper)`. `FeatureFlagConfig.Properties.PostHog` gains `getDistinctIdSalt()` / `setDistinctIdSalt(String)`.

The salt is a new constructor parameter rather than a setter, so the object cannot exist in a state
where it would send a raw id. That changes both constructors' arity; the only production caller is
`FeatureFlagConfig`, and the test file is updated in the same task.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/recsys/infrastructure/featureflags/providers/PostHogFeatureFlagProviderTest.java`,
first update the existing fixture — the constructor gains a salt as its second argument:

```java
        provider = new PostHogFeatureFlagProvider(
                "phc_test",
                "test-salt",
                URI.create("https://posthog.example"),
                Duration.ofSeconds(1),
                client,
                objectMapper);
```

In `resolvesBooleanFlagAndSendsEvaluationContext`, replace the raw-id assertion

```java
        assertThat(json.path("distinct_id").asText()).isEqualTo("user-1");
```

with one that pins the shape rather than a literal digest, so the test does not have to hard-code a
hash:

```java
        assertThat(json.path("distinct_id").asText())
                .as("distinct_id must be a full SHA-256 hex digest, not the application userId")
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo("user-1");
```

Then add these tests:

```java
    @Test
    void theRawUserIdNeverAppearsAnywhereInTheRequestBody() throws Exception {
        client.nextResponse = new StubResponse(200, """
                {"featureFlags":{"new-ranking":true}}
                """);

        provider.resolve(FeatureFlag.enabledByDefault("new-ranking"), "user-1", Map.of());

        // Asserted against the whole serialized body, not one field: a future change that added the
        // userId under some other key would still be a disclosure, and this test should catch it.
        assertThat(bodyFrom(client.lastRequest)).doesNotContain("user-1");
    }

    @Test
    void theSameUserAndSaltAlwaysProduceTheSameDistinctId() throws Exception {
        // Stability across instances is what makes PostHog's percentage rollouts bucket a user
        // consistently — a per-process value would reshuffle every pod and every restart.
        assertThat(distinctIdFor("user-1", "test-salt"))
                .isEqualTo(distinctIdFor("user-1", "test-salt"));
    }

    @Test
    void differentSaltsProduceDifferentDistinctIdsForTheSameUser() throws Exception {
        // This is what makes the salt load-bearing rather than decorative: without it the digest
        // of a small integer id space is a seconds-long rainbow table.
        assertThat(distinctIdFor("user-1", "salt-a"))
                .isNotEqualTo(distinctIdFor("user-1", "salt-b"));
    }

    @Test
    void differentUsersProduceDifferentDistinctIds() throws Exception {
        assertThat(distinctIdFor("user-1", "test-salt"))
                .isNotEqualTo(distinctIdFor("user-2", "test-salt"));
    }

    @Test
    void aBlankSaltIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new PostHogFeatureFlagProvider(
                "phc_test", "  ", URI.create("https://posthog.example"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("POSTHOG_DISTINCT_ID_SALT");

        assertThatThrownBy(() -> new PostHogFeatureFlagProvider(
                "phc_test", null, URI.create("https://posthog.example"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("POSTHOG_DISTINCT_ID_SALT");
    }

    /** Resolves once through a fresh provider and returns the distinct_id it sent. */
    private String distinctIdFor(String userId, String salt) throws Exception {
        CapturingHttpClient localClient = new CapturingHttpClient();
        localClient.nextResponse = new StubResponse(200, """
                {"featureFlags":{"new-ranking":true}}
                """);
        PostHogFeatureFlagProvider localProvider = new PostHogFeatureFlagProvider(
                "phc_test", salt, URI.create("https://posthog.example"),
                Duration.ofSeconds(1), localClient, objectMapper);

        localProvider.resolve(FeatureFlag.enabledByDefault("new-ranking"), userId, Map.of());

        return objectMapper.readTree(bodyFrom(localClient.lastRequest)).path("distinct_id").asText();
    }
```

Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` if absent. Reuse the file's
existing `bodyFrom(HttpRequest)` helper, `CapturingHttpClient` and `StubResponse` — do not write new
ones. If `bodyFrom` is private and takes only the captured request, it already does what these tests
need.

Leave `missingIdentityOrHttpFailureIsUnresolved` untouched: a blank `distinctId` must still
short-circuit before any request is built, and that test is what proves the hashing did not move
that check.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=PostHogFeatureFlagProviderTest
```

Expected: compilation failure — no constructor takes a salt.

- [ ] **Step 3: Add the salt and the hashing to the provider**

In `PostHogFeatureFlagProvider`, add these imports:

```java
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
```

Add the field beside the others:

```java
    private final String distinctIdSalt;
```

Replace both constructors:

```java
    public PostHogFeatureFlagProvider(String apiKey, String distinctIdSalt, URI host,
                                      Duration requestTimeout) {
        this(apiKey, distinctIdSalt, host, requestTimeout, HttpClient.newHttpClient(), new ObjectMapper());
    }

    public PostHogFeatureFlagProvider(String apiKey, String distinctIdSalt, URI host,
                                      Duration requestTimeout, HttpClient httpClient,
                                      ObjectMapper objectMapper) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("PostHog API key must not be blank");
        }
        // Required, not defaulted. distinct_id is a hash of the application userId, and userIds
        // here are small integers — an unsalted digest over that key space is a rainbow table
        // anyone can build in seconds, so a blank salt would look like a control and be none.
        // Refusing here is the same fail-closed shape as the blank-API-key check above.
        if (distinctIdSalt == null || distinctIdSalt.isBlank()) {
            throw new IllegalArgumentException(
                    "POSTHOG_DISTINCT_ID_SALT must be set when PostHog feature flags are enabled");
        }
        this.apiKey = apiKey;
        this.distinctIdSalt = distinctIdSalt;
        this.decideUri = Objects.requireNonNull(host, "host").resolve("/decide/?v=3");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }
```

In `resolve`, change the one line that puts the identifier into the body:

```java
            body.put("distinct_id", pseudonymize(distinctId));
```

Add the helper at the end of the class:

```java
    /**
     * The identifier PostHog is allowed to see: a salted, one-way digest of the application userId.
     *
     * <p>Deterministic and stable across pods and restarts, because the salt is shared
     * configuration — that is what lets PostHog's percentage rollouts bucket a given user
     * consistently. Full digest rather than a prefix: {@code GatewayPrincipal.sha256Prefix}
     * truncates because a rate-limit bucket tolerates collisions, and a flag-targeting key does not.
     *
     * <p>Rotating the salt re-buckets every user, so it is long-lived configuration. See
     * {@code docs/system_design/20_AuthN_AuthZ.md}.
     */
    private String pseudonymize(String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((distinctIdSalt + ":" + userId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
```

- [ ] **Step 4: Wire the salt through configuration**

In `src/main/java/com/recsys/config/FeatureFlagConfig.java`, add the property to the `PostHog`
class beside `apiKey`:

```java
        private String distinctIdSalt;
```

and its accessors beside the others:

```java
        public String getDistinctIdSalt() { return distinctIdSalt; }
        public void setDistinctIdSalt(String distinctIdSalt) { this.distinctIdSalt = distinctIdSalt; }
```

Then pass it in `featureFlagProvider`:

```java
            FeatureFlagProvider raw = new PostHogFeatureFlagProvider(
                    postHog.getApiKey(), postHog.getDistinctIdSalt(),
                    postHog.getHost(), postHog.getTimeout());
```

Do not add a blank check here — the provider's constructor is the single place that decides, so
there is one rule rather than two that must agree.

In `src/main/resources/application.yml`, add the key inside the `post-hog:` block, after `api-key`:

```yaml
      # Required when enabled: distinct_id is a salted hash of the application userId, never the
      # userId itself. Rotating this re-buckets every user — see 20_AuthN_AuthZ.md.
      distinct-id-salt: ${POSTHOG_DISTINCT_ID_SALT:}
```

- [ ] **Step 5: Run the tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='PostHogFeatureFlagProviderTest,FeatureFlagServiceTest,CachingFeatureFlagProviderTest,CompositeFeatureFlagProviderTest,EnvFeatureFlagProviderTest'
```

Expected: PASS. The four neighbouring suites prove the boundary placement — nothing else in the
feature-flag stack changed behavior.

- [ ] **Step 6: Verify the change bites**

Temporarily revert the `resolve` line to `body.put("distinct_id", distinctId);` and re-run
`PostHogFeatureFlagProviderTest`. Expected: `theRawUserIdNeverAppearsAnywhereInTheRequestBody` and
the shape assertion both fail. Restore the line. Report what you saw — a privacy control that cannot
fail is not a control.

- [ ] **Step 7: Add the test to the PR gate**

In `pom.xml`, inside the `resilience` profile's `<includes>`:

```xml
                <!-- The only path that sends an application user identifier to a third party.
                     Pins that PostHog receives a salted digest and never the userId itself. -->
                <include>**/featureflags/providers/PostHogFeatureFlagProviderTest.java</include>
```

- [ ] **Step 8: Run the PR gate and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add src/main/java/com/recsys/infrastructure/featureflags/providers/PostHogFeatureFlagProvider.java \
        src/main/java/com/recsys/config/FeatureFlagConfig.java \
        src/main/resources/application.yml \
        src/test/java/com/recsys/infrastructure/featureflags/providers/PostHogFeatureFlagProviderTest.java \
        pom.xml
git commit -m "feat: send PostHog a salted hash instead of the application userId"
```

---

### Task 2: Document the third-party identifier boundary

**Files:**
- Modify: `docs/system_design/20_AuthN_AuthZ.md`
- Modify: `.claude/CLAUDE.md`

**Interfaces:**
- Consumes: `POSTHOG_DISTINCT_ID_SALT` and the provider behavior from Task 1.
- Produces: nothing.

**Verify every claim against the code as committed, not against this plan.** Three separate reviews
on recent branches caught false statements in this exact file — a citation to a sharp edge that
documented a different test, a claim about what `CLAUDE.md` records, and a uniqueness claim that a
new section had just invalidated. Read what you are citing before citing it.

- [ ] **Step 1: Add the note to `20_AuthN_AuthZ.md`**

Append to the existing discussion of data reaching third parties — do **not** renumber any `##`
heading. Cover, in prose:

- PostHog feature flags are the only path that sends a user identifier outside the system, and what
  it sends is `sha256(salt + ":" + userId)`, never the userId.
- The path is dormant: disabled by default, requires a non-blank API key as well, set in no manifest
  or overlay, and permitted by no egress rule — `k8s/base/network-policy.yaml` allows no egress to
  PostHog, which is the same gap this file already records as sharp edge 9 ("Nothing permits egress
  on 443"). Cite sharp edge 9 only after reading it and confirming that is what it says.
- `POSTHOG_DISTINCT_ID_SALT` is required when PostHog is enabled; a blank salt fails startup rather
  than falling back to the raw id, because an unsalted digest over this id space is trivially
  reversible.
- Rotating the salt re-buckets every user, so a rollout in flight reshuffles. It is long-lived
  configuration.
- What it costs: nobody can look a specific user up in PostHog any more. That is intended, and it
  removes a debugging path someone enabling the flag might expect.

- [ ] **Step 2: Add the env var to CLAUDE.md**

In `.claude/CLAUDE.md`, add to the environment-variable prose:

```
`POSTHOG_DISTINCT_ID_SALT` (no default) is **required** when `POSTHOG_FEATURE_FLAGS_ENABLED=true`:
PostHog receives `sha256(salt + ":" + userId)` as `distinct_id`, never the application userId, and a
blank salt fails provider construction rather than falling back to the raw value. Rotating it
re-buckets every user. PostHog is off by default, set in no manifest, and permitted by no egress
rule.
```

- [ ] **Step 3: Verify and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='DocumentationIndexTest,DocumentedMechanismTest'
git add docs/system_design/20_AuthN_AuthZ.md .claude/CLAUDE.md
git commit -m "docs: record the third-party identifier boundary"
```

Do not push and do not open a PR; the controller handles that.

---

## Verification

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Must pass. The claim worth stating in the PR body from evidence rather than assertion: reverting the
hash makes two tests fail, demonstrated in Task 1 Step 6.
