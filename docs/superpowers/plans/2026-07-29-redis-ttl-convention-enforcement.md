# Redis TTL Convention Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make "a key without a TTL is authoritative" an observable invariant instead of an unchecked convention, and fix the one writer already violating it.

**Architecture:** A bounded runtime sampler walks the Redis keyspace one `SCAN` page per tick, carrying the cursor across ticks, and reports keys with no TTL whose prefix is not on a declared durable allow-list. Results publish as two Micrometer gauges alongside the existing Redis cache metrics on the online-serving process. Separately, `RedisReplicaLagProbe` stops writing its per-process marker as permanent state.

**Tech Stack:** Java 17, Lettuce (`io.lettuce.core`), Micrometer, JUnit 5, AssertJ, Mockito, Maven.

## Global Constraints

- Build with JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. Other major versions are rejected by the enforcer.
- Design source: `docs/superpowers/specs/2026-07-29-redis-ttl-convention-enforcement-design.md`.
- Metric names are exactly `redis_unexpected_persistent_keys` and `redis_keyspace_sampled_keys`. Key names must never become metric labels (unbounded cardinality); offending keys are logged, not tagged.
- The durable allow-list is exactly: `shard:topology`, `i2vEmb:`, `u2vEmb:`, `sr:seq:`, `bias:item:`.
- The probe never mutates the keyspace. No expiring, no deleting — it reports only.
- New test classes are mock-only (no Redis, no Docker, no timing) and must be added to the `-Presilience` profile in `pom.xml`.
- Follow the existing probe shape: daemon thread, `scheduleWithFixedDelay`, observer exceptions swallowed so sampling is never cancelled, `close()` idempotent.

---

### Task 1: Stop the lag-probe marker from being permanent state

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/RedisReplicaLagProbe.java:37`
- Test: `src/test/java/com/recsys/infrastructure/redis/RedisReplicaLagProbeTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: no new public API. `RedisReplicaLagProbe.MARKER_TTL_SECONDS` (package-private `static final int`, value `60`) exists for the test to reference.

- [ ] **Step 1: Write the failing test**

Add to `RedisReplicaLagProbeTest`, and add these imports at the top of the file:

```java
import io.lettuce.core.SetArgs;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.CommandArgs;
import java.util.concurrent.atomic.AtomicReference;
```

```java
    @Test void markerWriteCarriesATtlSoADeadInstanceLeavesNoPermanentKey() {
        Map<String, String> primary = new HashMap<>();
        Map<String, String> replica = new HashMap<>();
        replica.put("probe:test", "0:1750000000000");
        AtomicReference<SetArgs> captured = new AtomicReference<>();
        RedisExecutor exec = new StubExecutor() {
            @Override public <T> T execute(Function<RedisCommands<String, String>, T> fn) {
                return fn.apply(capturingCommands(primary, captured));
            }
            @Override public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
                return fn.apply(commands(replica));
            }
            @Override public <T> Optional<T> executeReplicaRead(Function<RedisCommands<String, String>, T> fn) {
                return Optional.ofNullable(fn.apply(commands(replica)));
            }
        };

        new RedisReplicaLagProbe(exec, Clock.systemUTC(), "probe:test").sample();

        assertThat(captured.get())
                .as("the marker is written per process instance; a bare SET makes it permanently "
                        + "resident and unevictable under volatile-lru")
                .isNotNull();
        CommandArgs<String, String> rendered = new CommandArgs<>(StringCodec.UTF8);
        captured.get().build(rendered);
        assertThat(rendered.toString()).contains("EX");
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> capturingCommands(Map<String, String> values,
                                                                   AtomicReference<SetArgs> captured) {
        return (RedisCommands<String, String>) Proxy.newProxyInstance(RedisCommands.class.getClassLoader(),
                new Class<?>[]{RedisCommands.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> values.get(args[0]);
                    case "set" -> {
                        values.put((String) args[0], (String) args[1]);
                        if (args.length > 2) captured.set((SetArgs) args[2]);
                        yield "OK";
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='RedisReplicaLagProbeTest' -DfailIfNoTests=false`
Expected: FAIL — `captured.get()` is null, with the assertion description about a bare SET. The current code calls the two-argument `set(key, value)`.

- [ ] **Step 3: Write minimal implementation**

In `RedisReplicaLagProbe.java`, add the import and constant, then change the write.

Import to add:

```java
import io.lettuce.core.SetArgs;
```

Constant, next to `DEFAULT_KEY_PREFIX`:

```java
    /**
     * The marker is read back inside the same {@link #sample()} call, so this only has to
     * outlive replication lag. It also bounds the leak: the key is per-process
     * ({@code DEFAULT_KEY_PREFIX + UUID}), so without a TTL every deploy, scale-up, or
     * crash-loop restart would leave another permanently-resident key that
     * {@code volatile-lru} can never evict.
     */
    static final int MARKER_TTL_SECONDS = 60;
```

Replace line 37:

```java
            redis.execute(commands -> commands.set(key, writtenSequence + ":" + now));
```

with:

```java
            redis.execute(commands ->
                    commands.set(key, writtenSequence + ":" + now, SetArgs.Builder.ex(MARKER_TTL_SECONDS)));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='RedisReplicaLagProbeTest' -DfailIfNoTests=false`
Expected: PASS, all three tests. The two pre-existing tests must still pass — the `commands(...)` fake ignores extra arguments, so they are unaffected.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/RedisReplicaLagProbe.java \
        src/test/java/com/recsys/infrastructure/redis/RedisReplicaLagProbeTest.java
git commit -m "fix: give the replica-lag marker a TTL instead of leaking a key per process"
```

---

### Task 2: `RedisPersistentKeyProbe` — sample the keyspace and classify

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/RedisPersistentKeyProbe.java`
- Test: `src/test/java/com/recsys/infrastructure/redis/RedisPersistentKeyProbeTest.java`

**Interfaces:**
- Consumes: `RedisExecutor.executeRead(Function<RedisCommands<String,String>, T>)`.
- Produces:
  - `RedisPersistentKeyProbe(RedisExecutor redis)` and
    `RedisPersistentKeyProbe(RedisExecutor redis, List<String> durablePrefixes, int pageSize)`
  - `RedisPersistentKeyProbe.KeyspaceSample` — a record with components
    `boolean available`, `int scanned`, `int unexpected`, `List<String> examples`,
    plus `static KeyspaceSample unavailable()`.
  - `KeyspaceSample sample()`
  - `void start(Duration interval, Consumer<KeyspaceSample> observer)`
  - `void close()`
  - `static final List<String> DEFAULT_DURABLE_PREFIXES`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/redis/RedisPersistentKeyProbeTest.java`:

```java
package com.recsys.infrastructure.redis;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisPersistentKeyProbeTest {

    /** A fake Redis holding key -> ttl seconds (-1 = no expiry), served as one SCAN page. */
    @SuppressWarnings("unchecked")
    private static RedisExecutor execWith(Map<String, Long> keyspace, String nextCursor) {
        RedisCommands<String, String> cmd = mock(RedisCommands.class);
        KeyScanCursor<String> page = mock(KeyScanCursor.class);
        when(page.getKeys()).thenReturn(List.copyOf(keyspace.keySet()));
        when(page.getCursor()).thenReturn(nextCursor);
        when(page.isFinished()).thenReturn("0".equals(nextCursor));
        when(cmd.scan(any(ScanCursor.class), any(ScanArgs.class))).thenReturn(page);
        keyspace.forEach((key, ttl) -> when(cmd.ttl(key)).thenReturn(ttl));

        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.executeRead(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        return exec;
    }

    @Test
    void flagsAKeyWithNoTtlThatIsNotDeclaredDurable() {
        RedisExecutor exec = execWith(Map.of("recsys:replica-lag-probe:abc", -1L), "0");

        RedisPersistentKeyProbe.KeyspaceSample sample = new RedisPersistentKeyProbe(exec).sample();

        assertThat(sample.available()).isTrue();
        assertThat(sample.scanned()).isEqualTo(1);
        assertThat(sample.unexpected()).isEqualTo(1);
        assertThat(sample.examples()).containsExactly("recsys:replica-lag-probe:abc");
    }

    @Test
    void doesNotFlagDeclaredDurableKeys() {
        RedisExecutor exec = execWith(Map.of(
                "shard:topology", -1L,
                "i2vEmb:42", -1L,
                "u2vEmb:7", -1L,
                "sr:seq:3", -1L,
                "bias:item:99", -1L), "0");

        RedisPersistentKeyProbe.KeyspaceSample sample = new RedisPersistentKeyProbe(exec).sample();

        assertThat(sample.scanned()).isEqualTo(5);
        assertThat(sample.unexpected()).isZero();
        assertThat(sample.examples()).isEmpty();
    }

    @Test
    void doesNotFlagKeysThatCarryATtl() {
        RedisExecutor exec = execWith(Map.of("filler:1", 300L, "svc:registry:online", 30L), "0");

        assertThat(new RedisPersistentKeyProbe(exec).sample().unexpected()).isZero();
    }

    @Test
    void carriesTheCursorAcrossSamplesAndWrapsWhenTheScanFinishes() {
        RedisExecutor exec = execWith(Map.of("leaky:1", -1L), "512");
        RedisPersistentKeyProbe probe = new RedisPersistentKeyProbe(exec);

        probe.sample();
        assertThat(probe.cursorPosition()).isEqualTo("512");

        RedisExecutor finishing = execWith(Map.of("leaky:2", -1L), "0");
        RedisPersistentKeyProbe wrapping = new RedisPersistentKeyProbe(finishing);
        wrapping.sample();
        assertThat(wrapping.cursorPosition())
                .as("a finished scan restarts, so sampling keeps covering the keyspace")
                .isEqualTo("0");
    }

    @Test
    void boundsHowManyOffendingKeysItReports() {
        RedisExecutor exec = execWith(Map.of(
                "leak:1", -1L, "leak:2", -1L, "leak:3", -1L, "leak:4", -1L, "leak:5", -1L), "0");

        RedisPersistentKeyProbe.KeyspaceSample sample = new RedisPersistentKeyProbe(exec).sample();

        assertThat(sample.unexpected()).isEqualTo(5);
        assertThat(sample.examples()).hasSize(3);
    }

    @Test
    void reportsUnavailableWithoutPropagatingWhenRedisFails() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.executeRead(any())).thenThrow(new IllegalStateException("redis down"));

        assertThat(new RedisPersistentKeyProbe(exec).sample().available()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='RedisPersistentKeyProbeTest' -DfailIfNoTests=false`
Expected: FAIL — compilation error, `cannot find symbol: class RedisPersistentKeyProbe`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/recsys/infrastructure/redis/RedisPersistentKeyProbe.java`:

```java
package com.recsys.infrastructure.redis;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reports keys that have no TTL and are not declared durable.
 *
 * <p>{@code volatile-lru} makes "has a TTL" the eviction boundary: cache-like keys expire and
 * are evictable, keys without a TTL are authoritative and structurally protected. That is only
 * correct while every writer honours it, and a writer that forgets a TTL silently converts its
 * key into permanently-resident, unevictable state.
 *
 * <p>This watches the keyspace rather than the code on purpose. The highest-volume writer in the
 * system is the Flink job, which is excluded from the Maven compile and writes through Lua, so no
 * source-level check can see it — nor can one see anything written out-of-band. The cost is that
 * detection is probabilistic: one bounded {@code SCAN} page per tick, cursor carried across ticks,
 * so coverage accrues over time at fixed cost per tick.
 *
 * <p>It never mutates the keyspace. A key misjudged as unexpected is exactly the authoritative
 * state the invariant exists to protect, so remediation is a human decision.
 */
public final class RedisPersistentKeyProbe implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisPersistentKeyProbe.class);

    /** Redis reports -1 for "key exists, no expiry set". */
    private static final long NO_EXPIRY = -1L;
    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int MAX_EXAMPLES = 3;

    /** The namespaces whose keys are authoritative and therefore legitimately TTL-less. */
    public static final List<String> DEFAULT_DURABLE_PREFIXES =
            List.of("shard:topology", "i2vEmb:", "u2vEmb:", "sr:seq:", "bias:item:");

    /**
     * @param examples a bounded sample of offending key names — logged, never used as a metric
     *                 label, because key names are unbounded cardinality.
     */
    public record KeyspaceSample(boolean available, int scanned, int unexpected, List<String> examples) {
        public static KeyspaceSample unavailable() {
            return new KeyspaceSample(false, 0, 0, List.of());
        }
    }

    private final RedisExecutor redis;
    private final List<String> durablePrefixes;
    private final int pageSize;

    private ScanCursor cursor = ScanCursor.INITIAL;
    private ScheduledExecutorService executor;

    public RedisPersistentKeyProbe(RedisExecutor redis) {
        this(redis, DEFAULT_DURABLE_PREFIXES, DEFAULT_PAGE_SIZE);
    }

    public RedisPersistentKeyProbe(RedisExecutor redis, List<String> durablePrefixes, int pageSize) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.durablePrefixes = List.copyOf(Objects.requireNonNull(durablePrefixes, "durablePrefixes"));
        this.pageSize = Math.max(1, pageSize);
    }

    /** Cursor this probe will resume from; "0" means the next sample restarts the scan. */
    synchronized String cursorPosition() {
        return cursor.getCursor();
    }

    public synchronized KeyspaceSample sample() {
        try {
            ScanCursor from = cursor;
            KeyScanCursor<String> page =
                    redis.executeRead(c -> c.scan(from, ScanArgs.Builder.limit(pageSize)));
            if (page == null) return KeyspaceSample.unavailable();

            cursor = page.isFinished() ? ScanCursor.INITIAL : ScanCursor.of(page.getCursor());

            List<String> keys = page.getKeys();
            List<String> offenders = new ArrayList<>();
            for (String key : keys) {
                if (isDeclaredDurable(key)) continue;
                Long ttl = redis.executeRead(c -> c.ttl(key));
                if (ttl != null && ttl == NO_EXPIRY) offenders.add(key);
            }

            List<String> examples = List.copyOf(offenders.subList(0, Math.min(MAX_EXAMPLES, offenders.size())));
            if (!offenders.isEmpty()) {
                log.warn("{} sampled key(s) have no TTL and are not declared durable; "
                                + "under volatile-lru these can never be evicted. Examples: {}",
                        offenders.size(), examples);
            }
            return new KeyspaceSample(true, keys.size(), offenders.size(), examples);
        } catch (RuntimeException failure) {
            return KeyspaceSample.unavailable();
        }
    }

    private boolean isDeclaredDurable(String key) {
        for (String prefix : durablePrefixes) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    public synchronized void start(Duration interval, Consumer<KeyspaceSample> observer) {
        Objects.requireNonNull(interval);
        Objects.requireNonNull(observer);
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (executor != null) throw new IllegalStateException("probe already started");
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "redis-persistent-key-probe");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                observer.accept(sample());
            } catch (Throwable ignored) {
                // An observer must not permanently cancel fixed-delay sampling.
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='RedisPersistentKeyProbeTest' -DfailIfNoTests=false`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/RedisPersistentKeyProbe.java \
        src/test/java/com/recsys/infrastructure/redis/RedisPersistentKeyProbeTest.java
git commit -m "feat: sample the keyspace for keys that have no TTL and are not declared durable"
```

---

### Task 3: Publish the sample as metrics

**Files:**
- Modify: `src/main/java/com/recsys/metrics/RedisCacheMetrics.java`
- Test: `src/test/java/com/recsys/metrics/RedisCacheMetricsTest.java`

**Interfaces:**
- Consumes: `RedisPersistentKeyProbe.KeyspaceSample` from Task 2.
- Produces: `RedisCacheMetrics.updateKeyspace(KeyspaceSample sample)`, and the gauges
  `redis_unexpected_persistent_keys` and `redis_keyspace_sampled_keys`.

- [ ] **Step 1: Write the failing test**

Add to `RedisCacheMetricsTest`, with this import at the top of the file:

```java
import com.recsys.infrastructure.redis.RedisPersistentKeyProbe.KeyspaceSample;
```

```java
    @Test
    void publishesTheKeyspaceSample() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisCacheMetrics metrics = new RedisCacheMetrics(registry);

        metrics.updateKeyspace(new KeyspaceSample(true, 200, 2, java.util.List.of("leak:1", "leak:2")));

        assertThat(registry.get("redis_keyspace_sampled_keys").gauge().value()).isEqualTo(200d);
        assertThat(registry.get("redis_unexpected_persistent_keys").gauge().value()).isEqualTo(2d);
    }

    @Test
    void anUnavailableKeyspaceSampleDoesNotReportAFalseAllClear() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisCacheMetrics metrics = new RedisCacheMetrics(registry);
        metrics.updateKeyspace(new KeyspaceSample(true, 200, 2, java.util.List.of("leak:1")));

        metrics.updateKeyspace(KeyspaceSample.unavailable());

        assertThat(registry.get("redis_unexpected_persistent_keys").gauge().value())
                .as("a failed scan must not look like the leak was fixed")
                .isEqualTo(2d);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='RedisCacheMetricsTest' -DfailIfNoTests=false`
Expected: FAIL — compilation error, `cannot find symbol: method updateKeyspace(...)`.

- [ ] **Step 3: Write minimal implementation**

In `RedisCacheMetrics.java`, add the import:

```java
import com.recsys.infrastructure.redis.RedisPersistentKeyProbe.KeyspaceSample;
```

Add two fields beside the existing `AtomicLong`s:

```java
    private final AtomicLong keyspaceSampled = new AtomicLong();
    private final AtomicLong unexpectedPersistentKeys = new AtomicLong();
```

Register them at the end of the constructor:

```java
        Gauge.builder("redis_keyspace_sampled_keys", keyspaceSampled, AtomicLong::get)
                .description("Keys examined by the most recent bounded keyspace sample")
                .register(registry);
        Gauge.builder("redis_unexpected_persistent_keys", unexpectedPersistentKeys, AtomicLong::get)
                .description("Sampled keys with no TTL that are not on the durable allow-list; "
                        + "under volatile-lru these can never be evicted")
                .register(registry);
```

Add the update method:

```java
    /**
     * An unavailable sample keeps the last-known counts. Reporting 0 for a scan that never ran
     * would be indistinguishable from the leak having been fixed.
     */
    public void updateKeyspace(KeyspaceSample sample) {
        Objects.requireNonNull(sample, "sample");
        if (!sample.available()) return;
        keyspaceSampled.set(Math.max(0, sample.scanned()));
        unexpectedPersistentKeys.set(Math.max(0, sample.unexpected()));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='RedisCacheMetricsTest' -DfailIfNoTests=false`
Expected: PASS, 5 tests (3 pre-existing plus 2 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/metrics/RedisCacheMetrics.java \
        src/test/java/com/recsys/metrics/RedisCacheMetricsTest.java
git commit -m "feat: publish unexpected-persistent-key counts as metrics"
```

---

### Task 4: Wire the probe into online serving, gate the tests, and validate against a real Redis

**Files:**
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`
- Modify: `pom.xml` (the `resilience` profile `<includes>`)

**Interfaces:**
- Consumes: `RedisPersistentKeyProbe` (Task 2), `RedisCacheMetrics.updateKeyspace` (Task 3).
- Produces: no new API. Adds env var `REDIS_PERSISTENT_KEY_PROBE_SECONDS` (default 60).

- [ ] **Step 1: Declare the probe variable**

In `OnlinePredictionServer.java`, next to `RedisCacheStatsProbe cacheStatsProbe = null;`:

```java
        RedisPersistentKeyProbe persistentKeyProbe = null;
```

Add the import next to the other `infrastructure.redis` imports:

```java
import com.recsys.infrastructure.redis.RedisPersistentKeyProbe;
```

- [ ] **Step 2: Start it beside the existing cache-stats probe**

Directly after the existing `cacheStatsProbe.start(...)` call:

```java
            persistentKeyProbe = new RedisPersistentKeyProbe(jedisPool);
            persistentKeyProbe.start(
                    Duration.ofSeconds(readIntEnv("REDIS_PERSISTENT_KEY_PROBE_SECONDS", 60)),
                    cacheMetrics::updateKeyspace);
```

- [ ] **Step 3: Close it on both shutdown paths**

In the shutdown hook block, beside `RedisCacheStatsProbe activeCacheStatsProbe = cacheStatsProbe;`:

```java
            RedisPersistentKeyProbe activePersistentKeyProbe = persistentKeyProbe;
```

and inside the hook body, after `activeCacheStatsProbe.close();`:

```java
                activePersistentKeyProbe.close();
```

In the `catch` cleanup block, after `if (cacheStatsProbe != null) cacheStatsProbe.close();`:

```java
            if (persistentKeyProbe != null) persistentKeyProbe.close();
```

- [ ] **Step 4: Add the new tests to the merge gate**

In `pom.xml`, in the `resilience` profile's `<includes>`, after
`<include>**/redis/RedisCacheStatsProbeTest.java</include>`:

```xml
                <include>**/redis/RedisPersistentKeyProbeTest.java</include>
```

- [ ] **Step 5: Verify the build and the gate**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q compile`
Expected: BUILD SUCCESS.

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience`
Expected: BUILD SUCCESS, count increased by the 6 new probe tests plus the 2 new metrics tests.

- [ ] **Step 6: Validate against a real Redis**

The unit tests use mocks, so confirm the probe works against real `SCAN`/`TTL` semantics.
Requires `redis-server` on PATH (`brew install redis`).

```bash
redis-server --port 6399 --save '' --appendonly no --daemonize yes
redis-cli -p 6399 set shard:topology '{"version":1}'          # durable, allow-listed
redis-cli -p 6399 setex filler:1 300 value                     # TTL'd, fine
redis-cli -p 6399 set recsys:replica-lag-probe:stale marker    # the violation
redis-cli -p 6399 keys '*'
```

Then drive the probe against it from `jshell` with the project on the classpath:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
JAVA_HOME=$(/usr/libexec/java_home -v 17) REDIS_PORT=6399 \
  jshell --class-path "target/classes:$(cat /tmp/cp.txt)"
```

`REDIS_PORT` must be exported before `jshell` starts — `LettuceClientFactory.fromEnv()` reads
`System.getenv()`. In the `jshell` session:

```java
import com.recsys.infrastructure.redis.*;
var probe = new RedisPersistentKeyProbe(LettuceClientFactory.fromEnv());
probe.sample();
```

Expected: `scanned=3`, `unexpected=1`, `examples=[recsys:replica-lag-probe:stale]`, and a WARN
line naming that key. `shard:topology` must NOT be flagged (allow-listed) and `filler:1` must NOT
be flagged (has a TTL).

Tear down: `redis-cli -p 6399 shutdown nosave`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/api/online/OnlinePredictionServer.java pom.xml
git commit -m "feat: run the persistent-key probe on online serving"
```

---

### Task 5: Correct the three overstated claims in the docs

**Files:**
- Modify: `docs/system_design/02_Caching.md`
- Modify: `docs/runbooks/elasticache-local.md`

**Interfaces:**
- Consumes: the metric names from Task 3.
- Produces: nothing consumed by other tasks.

The spec lists a third correction — dropping the "production ElastiCache unverified" framing.
Do not go looking for it in the repo: `grep` confirms that claim exists only in the assistant's
session memory, never in `docs/`. It is corrected there, outside this plan. No repo change.

- [ ] **Step 1: Correct the Lua overshoot magnitude in `02_Caching.md`**

In the "Running the claim instead of asserting it" subsection of §8, the second bullet currently
implies the Flink sinks can push Redis far past `maxmemory`. Replace its final sentence — the one
beginning "so the batch size in one invocation bounds how far past `maxmemory` Redis can go" and
its "Sizing `maxmemory` with no headroom" follow-on — with:

```markdown
  In this system the per-invocation writes are small, so the practical overshoot is kilobytes,
  not the 2× the simulation shows: `SET_IF_NEWER_WITH_LINEAGE_SCRIPT` touches 5 keys and
  `ATOMIC_TOPK_SCRIPT` writes `top-k` members (default **10**) into 2 ZSets. The simulation
  reaches 15.8 MB only because it writes 3000 keys in one `EVAL`, which no sink does. The
  mechanism is worth knowing before someone adds a batching writer; it does not justify
  resizing `maxmemory` today.
```

- [ ] **Step 2: Correct the same claim in the runbook**

In `docs/runbooks/elasticache-local.md`, in scenario 4's bullet, replace the sentence beginning
"This is not hypothetical for this system" through the end of that bullet with:

```markdown
   The mechanism is real, but the magnitude here is not: the sinks write 5 keys
   (`SET_IF_NEWER_WITH_LINEAGE_SCRIPT`) or `top-k` members, default 10 (`ATOMIC_TOPK_SCRIPT`),
   per invocation. The 15.8 MB above comes from writing 3000 keys in a single `EVAL`, which is a
   synthetic worst case, not sink behavior. Worth knowing before adding a batching writer.
```

- [ ] **Step 3: Rewrite sharp edge 7 as enforced-by-sampling**

In `02_Caching.md`, replace sharp edge 7 in "Sharp edges — notes" with:

```markdown
7. **The eviction boundary is a writer convention, now sampled rather than assumed.**
   `volatile-lru` is only correct while *every* cache-like writer sets a TTL. Nothing at write
   time enforces that, so
   [`RedisPersistentKeyProbe`](../../src/main/java/com/recsys/infrastructure/redis/RedisPersistentKeyProbe.java)
   walks one bounded `SCAN` page per tick and publishes `redis_unexpected_persistent_keys` for
   keys with no TTL outside the declared durable prefixes (`shard:topology`, `i2vEmb:`,
   `u2vEmb:`, `sr:seq:`, `bias:item:`). It watches the keyspace rather than the code because the
   Flink sinks — the highest-volume writer — are excluded from the Maven compile and write
   through Lua. Two residual gaps: detection is **probabilistic**, so a rarely-written key may
   take many ticks to surface; and the allow-list is itself a declaration that can go stale if a
   new durable namespace is added without updating it.
```

- [ ] **Step 4: Add the probe to the observability table**

In §8's "Observability" metric table, add a row after `redis_cache_available`:

```markdown
| `redis_unexpected_persistent_keys` | is someone writing keys that can never be evicted? |
```

- [ ] **Step 5: Verify the docs still satisfy the index tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='DocumentationIndexTest,DocumentedMechanismTest' -DfailIfNoTests=false`
Expected: PASS. No new files were added, so README indexing is unchanged.

- [ ] **Step 6: Commit**

```bash
git add docs/system_design/02_Caching.md docs/runbooks/elasticache-local.md
git commit -m "docs: correct the Lua overshoot magnitude and record sharp edge 7 as sampled"
```

---

## Final verification

- [ ] Run the full default suite:
  `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test`
  Expected: BUILD SUCCESS, 0 failures (baseline before this plan: 1420 tests).
- [ ] Run the merge gate: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience`
  Expected: BUILD SUCCESS.
- [ ] Open a PR (never merge to `main` directly). Body should state: the leak fixed, the sampler
  added, the metric names, and the two corrected claims.
