# Streaming job Redis authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Flink and Spark jobs authenticate to Redis, with the credential-building logic in the compiled, tested tree rather than in the modules that cannot be compiled.

**Architecture:** A new public `StreamingRedisUri` beside `LettuceClientFactory`, delegating to the same `standaloneUri` the services use; both jobs read `redis.username` / `redis.password` / `redis.tls` and call it. Unit tests cover the builder; nothing can cover the jobs.

**Tech Stack:** Java 17, Lettuce, JUnit 5 + AssertJ. Flink and Spark only at the call sites.

## Global Constraints

- **JDK 17.** Every Maven command: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`, run from the repo root.
- **`online/flink/` and `training/rulebased/` are excluded from the Maven compile.** Nothing there is compiled, tested, or able to fail a build. Keep the code you put there to the minimum — a parameter read and a one-line call — and put every decision in the compiled tree.
- **Do not change `LettuceClientFactory`'s existing behaviour or signatures.** `standaloneUri` at `:226` is package-private and stays that way; the new class lives in the same package and delegates to it. Services and jobs must produce identical URIs for identical inputs — that is the property being protected.
- **Credentials must cross the Spark closure boundary as primitives.** `ItemEmbeddingJob:172` builds its client *inside* `foreachPartition`, deliberately, so nothing non-serializable crosses. Read the values outside the lambda into local `String`/`boolean` variables and build the URI inside it. Do not capture a `RedisURI`, a config object, or anything holding one.
- **No credential may be logged**, and none may appear in a default. An unset password stays empty.
- The new test must be non-docker and added to the `resilience` profile `<includes>` in `pom.xml`.
- Never merge to `main` directly — this ships as a PR.
- Branch: `feat/streaming-job-redis-auth`, already created off `main` with the design committed.

## Facts established before this plan — do not rediscover

- `LettuceClientFactory.standaloneUri(String host, int port, String username, String password, boolean tls, int timeoutMs)` (`:226-235`) builds the URI: host, port, timeout, then `withAuth`, then `withSsl` when `tls`.
- `withAuth` (`:218-223`) sends a two-argument ACL `AUTH` when the username is non-blank, a legacy one-argument `AUTH` when only a password is set, and nothing when both are blank.
- `OnlineFeatureStreamingJob` reads config as `params.get("redis.host", "localhost")` (`:70`) and passes `host`/`port` into serializable sink classes; the client is created in each sink's `open()` (e.g. `:949`).
- `ItemEmbeddingJob` creates its client inside `foreachPartition` (`:172`), with a comment explaining that nothing non-serializable may cross the closure.
- `DEFAULT_TIMEOUT_MS` already exists in `LettuceClientFactory`; reuse it rather than inventing a number.

---

### Task 1: The shared URI builder

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/StreamingRedisUri.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/StreamingRedisUriTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Produces: `public static RedisURI StreamingRedisUri.from(String host, int port, String username, String password, boolean tls)` — Task 2's only entry point.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/redis/StreamingRedisUriTest.java`. It must assert that `StreamingRedisUri.from(...)` produces a `RedisURI` **equal to** what `LettuceClientFactory.standaloneUri(...)` produces for the same inputs — that equality is the point, because the failure this guards against is the two paths drifting so a job and a service authenticate differently against the same server.

Cover, at minimum:

```java
@Test void anonymousWhenBothBlank()            // "", ""      -> no AUTH
@Test void legacyAuthWhenOnlyPassword()        // "", "pw"    -> one-arg AUTH
@Test void aclAuthWhenUsernameAndPassword()    // "u", "pw"   -> two-arg AUTH
@Test void usernameWithoutPasswordStillAcl()   // "u", ""     -> two-arg AUTH, empty password
@Test void tlsOnAndOff()                       // both, host/port otherwise identical
@Test void blankAndNullAreTreatedAlike()       // null vs "" must not differ
```

That last one is not decoration: a Flink parameter default and an unset environment variable arrive as different things, and if they behave differently the job authenticates differently depending on how it was launched.

For each case, build the expectation by calling `LettuceClientFactory.standaloneUri` with the same arguments and `DEFAULT_TIMEOUT_MS`, and assert equality — do not hand-write the expected URI, which would let both sides drift together.

- [ ] **Step 2: Run it to confirm it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=StreamingRedisUriTest
```

Expected: compilation failure — `cannot find symbol: StreamingRedisUri`.

- [ ] **Step 3: Write the class**

Create `src/main/java/com/recsys/infrastructure/redis/StreamingRedisUri.java` in the same package, so it can call the package-private `standaloneUri`:

```java
package com.recsys.infrastructure.redis;

import io.lettuce.core.RedisURI;

/**
 * Builds a Redis URI for the Flink and Spark jobs, on the same terms as every service.
 *
 * <p>Those jobs live in {@code online/flink/} and {@code training/rulebased/}, which are excluded
 * from the Maven compile because they need Flink and Spark classpaths. Nothing there can be
 * compiled, tested, or made to fail a build — so the decision about what a Redis URI should look
 * like lives here instead, and each job calls this in one line.
 *
 * <p>They previously called {@code RedisURI.create(host, port)} with no credentials at all, which
 * is not a hardening gap but an outage waiting on a deploy: the moment {@code requirepass} is
 * applied they fail {@code NOAUTH}, and {@code OnlineFeatureStreamingJob} writes {@code u2vEmb:*}
 * and {@code topk:*} that every serving path reads.
 *
 * <p>Delegates to {@link LettuceClientFactory#standaloneUri} rather than reimplementing it. A job
 * and a service must authenticate identically against the same server; {@code StreamingRedisUriTest}
 * asserts equality with that method rather than against a hand-written URI, so the two cannot drift
 * apart in the same edit.
 */
public final class StreamingRedisUri {

    private StreamingRedisUri() {
    }

    /**
     * @param username blank or null for a legacy default-user login; non-blank for a Redis 6+ ACL
     *                 login. A Flink parameter default and an unset environment variable arrive
     *                 differently, so both must mean the same thing here.
     * @param password blank or null for no {@code AUTH} at all when the username is also blank
     */
    public static RedisURI from(String host, int port, String username, String password,
                                boolean tls) {
        return LettuceClientFactory.standaloneUri(host, port,
                username == null ? "" : username,
                password == null ? "" : password,
                tls, LettuceClientFactory.DEFAULT_TIMEOUT_MS);
    }
}
```

If `DEFAULT_TIMEOUT_MS` is private, widen it to package-private rather than duplicating the number — and say so in your report.

- [ ] **Step 4: Run the test**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=StreamingRedisUriTest
```

Expected: all cases pass.

- [ ] **Step 5: Prove the equality assertion bites**

Temporarily change `from` to pass `""` as the username unconditionally. Expected: `aclAuthWhenUsernameAndPassword` and `usernameWithoutPasswordStillAcl` fail. Revert by naming the exact path. A test that cannot fail is worse than none — five conformance tests in this repo have shipped asserting guarantees they did not provide.

- [ ] **Step 6: Add to the gate and commit**

In `pom.xml`, in the `resilience` profile's `<includes>`:

```xml
                <include>**/redis/StreamingRedisUriTest.java</include>
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add src/main/java/com/recsys/infrastructure/redis/StreamingRedisUri.java \
        src/test/java/com/recsys/infrastructure/redis/StreamingRedisUriTest.java pom.xml
git commit -m "feat: share the Redis URI builder with the streaming jobs"
```

---

### Task 2: Wire both jobs, and document what remains

**Files:**
- Modify: `src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java`
- Modify: `src/main/java/com/recsys/training/rulebased/ItemEmbeddingJob.java`
- Modify: `docs/system_design/22_Data_Leakage_Posture.md`, `docs/runbooks/redis-auth.md`

**Interfaces:**
- Consumes: `StreamingRedisUri.from(host, port, username, password, tls)` from Task 1.

**Neither job compiles in this build.** Read each call site and its surrounding class before editing, keep the change minimal, and state in your report exactly which lines you could not verify — which is all of them.

- [ ] **Step 1: Read the parameters in `OnlineFeatureStreamingJob`**

Beside the existing `redis.host` / `redis.port` reads near `:70`, add the three new ones, each defaulting to the matching environment variable so a job inherits the same configuration a service would:

```java
String redisUsername = params.get("redis.username", System.getenv("REDIS_USERNAME"));
String redisPassword = params.get("redis.password", System.getenv("REDIS_PASSWORD"));
boolean redisTls = Boolean.parseBoolean(params.get("redis.tls", System.getenv("REDIS_TLS")));
```

Thread them to every sink that currently takes `host`/`port` — `RedisRecentMoviesSink`, `RedisStringFeatureSink`, `RedisMovieMetricSink` and any other constructed with them around `:128-161`. They become `String`/`boolean` fields on those sinks, which are serializable types, so nothing about the existing serialization changes.

In each sink's `open()`, replace `RedisClient.create(RedisURI.create(host, port))` with
`RedisClient.create(StreamingRedisUri.from(host, port, username, password, tls))`.

- [ ] **Step 2: Do the same for `ItemEmbeddingJob`**

Read the three values **outside** the `foreachPartition` lambda into local variables, and build the URI inside it:

```java
RedisClient client = RedisClient.create(
        StreamingRedisUri.from(host, port, username, password, tls));
```

The comment at `:169-171` explains that the client is created inside the lambda so nothing non-serializable crosses the closure boundary. `String` and `boolean` are serializable; a `RedisURI` built outside and captured would be a change to that property. **Do not move the construction out of the lambda.**

- [ ] **Step 3: Verify what little can be verified**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn package -DskipTests
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: both succeed — and note in your report that neither exercises the two files you edited, because they are excluded from the compile. Confirm the exclusion still holds rather than assuming it: if either file has quietly become part of the build, that changes what this task means and you should say so.

- [ ] **Step 4: Update the posture document**

In `docs/system_design/22_Data_Leakage_Posture.md`, the gap-2 entry says the jobs cannot authenticate. Change it to: they can now, and whoever submits them must pass `redis.username` / `redis.password` / `redis.tls` or the matching environment variables. **Keep the outage risk** — it is not gone until those are supplied, and the entry must not read as closed. Record that the call sites are unverified because both modules are outside the compile.

Do not renumber any `##` heading and do not add a document.

- [ ] **Step 5: Update the Redis runbook**

In `docs/runbooks/redis-auth.md`, beside the existing rotation procedure, document the three parameters, that they fall back to `REDIS_USERNAME` / `REDIS_PASSWORD` / `REDIS_TLS`, and that the jobs authenticate as `default` — which is why PR #284 left `default` at `+@all`, and what would have to change to give them their own ACL user.

- [ ] **Step 6: Verify docs and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest
git add src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java \
        src/main/java/com/recsys/training/rulebased/ItemEmbeddingJob.java \
        docs/system_design/22_Data_Leakage_Posture.md docs/runbooks/redis-auth.md
git commit -m "feat: authenticate the streaming jobs to Redis"
```
