# Spec: MySQL Connection Pooling

## Objective
Replace per-call `DriverManager.getConnection` in `MySqlClient` with a bounded connection pool, removing TCP+auth handshake latency from every query while keeping idle-time cost negligible.

## Context / open decision
File: `src/main/java/com/recsys/infrastructure/persistence/MySqlClient.java` (line 51: `DriverManager.getConnection(...)` inside try-with-resources per `query`).
The current code deliberately avoids a pool ("pay only when a query runs"). MySQL is an **optional** backend (pagination / secondary features), so impact depends on call frequency.
- **Decision (recommended): add a small pool anyway.** A pool with `minimumIdle=0` (or 1) costs almost nothing when unused but removes the per-call handshake when used. This is strictly better than per-call connect with no real downside.
- Confirm during the plan whether MySQL is on any latency-sensitive path; if it is genuinely never hot, this can be deferred — but the change is cheap and low-risk.

## Scope
- Add `com.zaxxer:HikariCP` dependency.
- In `MySqlClient`, hold one `HikariDataSource` built from `MySqlConnectionSettings` (URL, credentials, props). Configure conservatively and env-tunably: `maximumPoolSize` (default small, e.g. 5), `minimumIdle` (default 0 or 1), `connectionTimeout`, `idleTimeout`, `maxLifetime`. `query()` borrows from the pool (try-with-resources returns it).
- Add `close()`/`AutoCloseable` to shut the pool down; wire it into the owning service's lifecycle.
- Preserve: identical query API, result mapping, exception behavior; reuse `MySqlConnectionSettings`.

## Out of Scope
- ORM/JPA introduction; query changes; pagination logic changes.
- Pooling for any non-MySQL client.

## Testing
- Mirror existing `MySqlClientTest`/`MySqlConnectionSettingsTest` harness. If tests run against a real/embedded MySQL via a `@Tag("docker")` profile, keep them excluded by default (per the repo's Surefire convention) and assert pool wiring with a unit test that builds the `HikariDataSource` from settings (no live DB needed: assert config values).
- A lifecycle test: `close()` shuts the pool; no leaked connections/threads.
- `mvn clean test` green (Docker-tagged DB tests remain opt-in).

## Risks
- HikariCP starts a housekeeping thread — bounded and stopped by `close()`; ensure the pool is created lazily so a missing MySQL config never breaks services that don't use it.
- Connection settings (SSL, timezone) must carry over verbatim into the Hikari config.

## Success
- `MySqlClient` borrows from a bounded Hikari pool; no per-call `DriverManager.getConnection`; pool is lazy + closeable; existing tests green.
