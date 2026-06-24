# MySQL Connection Pooling — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use `- [ ]`.

**Goal:** Replace per-call `DriverManager.getConnection` in `MySqlClient` with a lazy HikariCP pool, removing per-query handshake latency. Behavior-preserving.

**Architecture:** `MySqlClient` holds a lazily-built `HikariDataSource` (created on first `openConnection()`, only when enabled). `openConnection()` borrows from the pool; the pool is `readOnly`, small, env-tunable. `MySqlClient` becomes `AutoCloseable` to shut the pool. The `Connection`-accepting query/queryPage overloads are unchanged.

**Tech Stack:** Java 17, HikariCP 5.1.0 (new), JUnit 5 + Mockito + AssertJ.

## Global Constraints
- No change to query/queryPage/healthCheck semantics or the disabled-client contract (`openConnection` throws when disabled).
- `mvn clean test` green. Real pooling exercised only in a live-DB/integration env; unit tests cover lifecycle + the existing mocked-Connection query logic.
- Branch `optimize/mysql-connection-pooling` (spec already on branch).

---

### Task 1: Lazy HikariCP pool in `MySqlClient`

**Files:**
- Modify: `pom.xml` (add HikariCP)
- Modify: `src/main/java/com/recsys/infrastructure/persistence/MySqlClient.java`
- Test: `src/test/java/com/recsys/infrastructure/persistence/MySqlClientTest.java`

- [ ] **Step 1: Add HikariCP to `pom.xml`** (next to the Caffeine dep):
```xml
    <dependency>
      <groupId>com.zaxxer</groupId>
      <artifactId>HikariCP</artifactId>
      <version>5.1.0</version>
    </dependency>
```
Run `mvn -q package -DskipTests` to confirm it resolves.

- [ ] **Step 2: Write the failing lifecycle test** (append to `MySqlClientTest`):
```java
    @Test
    void isAutoCloseable_andCloseIsSafeBeforeAnyConnection() throws Exception {
        MySqlClient client = new MySqlClient(MySqlConnectionSettings.disabled());
        assertThat(client).isInstanceOf(AutoCloseable.class);
        client.close();  // no pool was ever created -> no-op, must not throw
        client.close();  // idempotent
    }
```
(Imports already include AssertJ.)

- [ ] **Step 3: Run — expect failure**
Run: `mvn -q test -Dtest=MySqlClientTest` → FAIL (`MySqlClient` not AutoCloseable / no `close()`).

- [ ] **Step 4: Implement the lazy pool + close()**
Add imports:
```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
```
Change the class declaration to implement `AutoCloseable`:
```java
public class MySqlClient implements AutoCloseable {
```
Add a lazily-initialized pool field + env helper near the top:
```java
    private volatile HikariDataSource dataSource;
```
Replace `openConnection()`'s body so it borrows from the pool (build lazily, keep the disabled guard and read-only semantics):
```java
    public Connection openConnection() throws SQLException {
        if (!settings.enabled()) {
            throw new IllegalStateException("MySQL is disabled; set MYSQL_ENABLED=true before opening connections");
        }
        return dataSource().getConnection();
    }

    private HikariDataSource dataSource() {
        HikariDataSource ds = dataSource;
        if (ds == null) {
            synchronized (this) {
                ds = dataSource;
                if (ds == null) {
                    HikariConfig cfg = new HikariConfig();
                    cfg.setPoolName("recsys-mysql");
                    cfg.setJdbcUrl(settings.url());
                    cfg.setUsername(settings.username());
                    cfg.setPassword(settings.password());
                    cfg.setReadOnly(true); // matches the previous per-connection setReadOnly(true)
                    cfg.setMaximumPoolSize(readIntEnv("MYSQL_POOL_MAX_SIZE", 5));
                    cfg.setMinimumIdle(readIntEnv("MYSQL_POOL_MIN_IDLE", 1));
                    cfg.setConnectionTimeout(readLongEnv("MYSQL_POOL_CONNECTION_TIMEOUT_MS", 10_000L));
                    cfg.setIdleTimeout(readLongEnv("MYSQL_POOL_IDLE_TIMEOUT_MS", 60_000L));
                    cfg.setMaxLifetime(readLongEnv("MYSQL_POOL_MAX_LIFETIME_MS", 1_800_000L));
                    ds = new HikariDataSource(cfg);
                    dataSource = ds;
                }
            }
        }
        return ds;
    }

    @Override
    public synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    private static int readIntEnv(String name, int def) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return def;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static long readLongEnv(String name, long def) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return def;
        try { return Long.parseLong(raw.trim()); } catch (NumberFormatException e) { return def; }
    }
```
(Remove the now-unused `java.sql.DriverManager` and `java.util.Properties` imports if they are no longer referenced — grep to confirm.)

- [ ] **Step 5: Run the test class**
Run: `mvn -q test -Dtest=MySqlClientTest` → PASS (lifecycle test + all existing mocked-Connection query tests, which never touch the pool).

- [ ] **Step 6: Commit**
```bash
git add pom.xml src/main/java/com/recsys/infrastructure/persistence/MySqlClient.java src/test/java/com/recsys/infrastructure/persistence/MySqlClientTest.java
git commit -m "perf: borrow MySQL connections from a lazy HikariCP pool"
```

---

### Task 2: Full-suite verification
- [ ] `mvn clean test` → BUILD SUCCESS, 0 failures.

## Self-Review
- Spec scope (lazy Hikari pool, readOnly, env-tunable, AutoCloseable) → Task 1. ✓
- Disabled-client contract preserved (throws before building a pool). ✓
- Connection-accepting overloads untouched → existing query tests pass. ✓
- No placeholders; env helpers + DriverManager/Properties removal noted. ✓
