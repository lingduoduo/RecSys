# Robust MySQL Catalog Querying Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden optional MySQL reads and expose a Flyway-managed, cursor-paginated movie catalog at `GET /v1/catalog/movies` on port 6010 and `/api/catalog/v1/catalog/movies` through the gateway.

**Architecture:** Keep MySQL opt-in and outside recommendation hot paths. Validated settings configure a lazy read-only Hikari client; a fixed-query repository reads `limit + 1`; a service owns signed filter-bound cursors; an Armeria adapter owns HTTP mapping; conditional Flyway bootstrap owns schema startup.

**Tech Stack:** Java 17, Maven, JDBC, HikariCP 5.1, MySQL 8.4, Flyway, Armeria 1.28, JUnit 5, Mockito, AssertJ, Testcontainers.

## Global Constraints

- `MYSQL_ENABLED=false` remains the default and must not contact MySQL.
- Enabled mode requires `MYSQL_CURSOR_SIGNING_KEY` with at least 32 UTF-8 bytes.
- Limits default to 20 and must be in `1..100`; ordering is `popularity_score DESC, id DESC`.
- Query timeout defaults to 2 seconds and must be in `1..30`.
- Reads make at most two attempts and retry transient connection failures only, never timeouts.
- Request data is bound; no request-derived SQL fragments.
- Existing recommendation, Redis, ONNX, auth, rate-limit, and circuit-breaker behavior remains unchanged.
- Use red-green-refactor for every behavior change.

## File Structure

- `application/catalog/CatalogCursorCodec.java`: versioned HMAC cursor codec.
- `application/catalog/MovieCatalogService.java`: validation, lookahead paging, cursors.
- `domain/catalog/CatalogMovie.java`, `CatalogPage.java`: transport-neutral records.
- `infrastructure/persistence/MovieCatalogRepository.java`: two fixed indexed queries.
- `infrastructure/persistence/MySqlExceptionClassifier.java`: retry/status classification.
- `infrastructure/persistence/CatalogDatabaseBootstrap.java`: conditional Flyway migration.
- `api/serving/MovieCatalogApiService.java`: Armeria adapter.
- `resources/db/migration/V1__create_movies_catalog.sql`: table and indexes.

---

### Task 1: Validated MySQL Runtime Settings

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/persistence/MySqlConnectionSettings.java`
- Modify: `src/test/java/com/recsys/infrastructure/persistence/MySqlConnectionSettingsTest.java`

**Interfaces:** Produces `queryTimeoutSeconds()`, `maxReadAttempts()`, `retryBackoffMillis()`, and `cursorSigningKey()`.

- [ ] Write failing tests proving disabled defaults `(2, 2, 50, blank key)`, enabled-mode missing/short signing-key rejection, and timeout/attempt/backoff range validation.
- [ ] Run `mvn test -Dtest=MySqlConnectionSettingsTest`; expect compilation/assertion failures for missing fields and validation.
- [ ] Extend the record to:

```java
public record MySqlConnectionSettings(
    boolean enabled, String url, String username, String password,
    int queryTimeoutSeconds, int maxReadAttempts,
    long retryBackoffMillis, String cursorSigningKey) {}
```

- [ ] Parse `MYSQL_QUERY_TIMEOUT_SECONDS=2`, `MYSQL_READ_MAX_ATTEMPTS=2`, `MYSQL_READ_RETRY_BACKOFF_MS=50`, and `MYSQL_CURSOR_SIGNING_KEY`; validate timeout `1..30`, attempts `1..2`, backoff `0..1000`, and enabled key length. Keep `safeDescription()` secret-free.
- [ ] Rerun `mvn test -Dtest=MySqlConnectionSettingsTest`; expect PASS.
- [ ] Commit: `git commit -am "feat(mysql): validate catalog query settings"`.

---

### Task 2: Mandatory Deadlines and Bounded Read Retry

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/persistence/MySqlExceptionClassifier.java`
- Create: `src/test/java/com/recsys/infrastructure/persistence/MySqlExceptionClassifierTest.java`
- Modify: `src/main/java/com/recsys/infrastructure/persistence/MySqlClient.java`
- Modify: `src/test/java/com/recsys/infrastructure/persistence/MySqlClientTest.java`

**Interfaces:** `isRetryableRead(SQLException)`, `isTimeout(SQLException)`; public query overloads use configured timeout and retry.

- [ ] Write classifier tests: SQLState class `08` and `SQLTransientConnectionException` retry; `SQLTimeoutException`, states `42`/`28`, and mapping failures do not.
- [ ] Run `mvn test -Dtest=MySqlExceptionClassifierTest`; expect compilation failure.
- [ ] Implement exception-chain classification, checking timeout before connection transience.
- [ ] Write failing client tests proving mandatory `setQueryTimeout`, connection reacquisition and success on attempt two, no retry for timeout/syntax, max-attempt propagation, and interrupt restoration.
- [ ] Add package-private test seams:

```java
@FunctionalInterface interface ConnectionProvider { Connection open() throws SQLException; }
@FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
```

- [ ] Implement retry only in overloads that own/reacquire connections. Preserve try-with-resources and never log binds.
- [ ] Run `mvn test -Dtest=MySqlExceptionClassifierTest,MySqlClientTest,MySqlConnectionSettingsTest`; expect PASS.
- [ ] Commit persistence production/tests with message `feat(mysql): enforce deadlines and retry transient reads`.

---

### Task 3: Signed Filter-Bound Cursors

**Files:**
- Create: `src/main/java/com/recsys/application/catalog/CatalogCursorCodec.java`
- Create: `src/test/java/com/recsys/application/catalog/CatalogCursorCodecTest.java`

**Interfaces:** `CatalogCursorCodec(String key)`, `encode(Position)`, `decode(String,String)`, `Position(String genre, BigDecimal popularityScore, long movieId)`, `InvalidCursorException`.

- [ ] Write failing tests for filtered/unfiltered round trips, tampering, filter mismatch, unsupported version, malformed Base64/numbers, and token length over 2,048.
- [ ] Run `mvn test -Dtest=CatalogCursorCodecTest`; expect compilation failure.
- [ ] Implement deterministic version-1 UTF-8 payload plus HMAC-SHA256, two Base64url segments, `MessageDigest.isEqual`, exact decimal strings, and a generic domain decode error.
- [ ] Rerun the focused test; expect PASS.
- [ ] Commit with message `feat(catalog): add signed filter-bound cursors`.

---

### Task 4: Self-Validating SQL Plans

**Files:**
- Modify: `src/main/java/com/recsys/application/pagination/MillionScalePaginationSql.java`
- Modify: `src/test/java/com/recsys/application/pagination/MillionScalePaginationSqlTest.java`
- Modify: `src/test/java/com/recsys/infrastructure/persistence/MySqlClientTest.java`

**Interfaces:** `SqlPlan` rejects placeholder/bind-count mismatches; existing pagination APIs remain source-compatible.

- [ ] Add failing tests for too few/many binds and valid generated cursor plans.
- [ ] Run `mvn test -Dtest=MillionScalePaginationSqlTest`; expect mismatch tests to fail.
- [ ] Count `?` characters in these internal fixed plans and throw `IllegalArgumentException` with expected/actual counts. Explicitly document that quoted question marks are outside this utility contract.
- [ ] Update intentionally incomplete mock plans, then run `mvn test -Dtest=MillionScalePaginationSqlTest,MySqlClientTest`; expect PASS.
- [ ] Commit with message `feat(mysql): validate sql plan bindings`.

---

### Task 5: Flyway Schema and Conditional Bootstrap

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/db/migration/V1__create_movies_catalog.sql`
- Create: `src/main/java/com/recsys/infrastructure/persistence/CatalogDatabaseBootstrap.java`
- Create: `src/test/java/com/recsys/infrastructure/persistence/CatalogDatabaseBootstrapTest.java`

**Interfaces:** `CatalogDatabaseBootstrap.migrate(MySqlConnectionSettings)`; indexes `idx_movies_genre_popularity_id`, `idx_movies_popularity_id`.

- [ ] Write a failing test proving disabled settings do not create/run a migration engine; inject a package-private runner factory.
- [ ] Run `mvn test -Dtest=CatalogDatabaseBootstrapTest`; expect compilation failure.
- [ ] Add compatible `flyway-core`, `flyway-mysql`, and test-scoped `org.testcontainers:mysql` dependencies.
- [ ] Implement enabled-only Flyway migration from `classpath:db/migration`.
- [ ] Add forward-only DDL for `movies` and both approved indexes.
- [ ] Run `mvn test -Dtest=CatalogDatabaseBootstrapTest,MySqlConnectionSettingsTest`; expect PASS.
- [ ] Commit with message `feat(catalog): manage mysql schema with flyway`.

---

### Task 6: Fixed Repository and Lookahead Paging Service

**Files:**
- Create: `src/main/java/com/recsys/domain/catalog/CatalogMovie.java`
- Create: `src/main/java/com/recsys/domain/catalog/CatalogPage.java`
- Create: `src/main/java/com/recsys/infrastructure/persistence/MovieCatalogRepository.java`
- Create: `src/test/java/com/recsys/infrastructure/persistence/MovieCatalogRepositoryTest.java`
- Create: `src/main/java/com/recsys/application/catalog/MovieCatalogService.java`
- Create: `src/test/java/com/recsys/application/catalog/MovieCatalogServiceTest.java`

**Interfaces:** `fetch(String, Position, int): List<CatalogMovie>` and `list(String, Integer, String): CatalogPage`.

- [ ] Write failing repository tests proving the filtered/unfiltered index hints, bound values, seek tuple, and bound fetch limit.
- [ ] Run `mvn test -Dtest=MovieCatalogRepositoryTest`; expect compilation failure.
- [ ] Implement the records and two fixed SQL shapes. Map `BigDecimal`, `Instant`, and nullable `Integer` without caller predicates.
- [ ] Write failing service tests for normalization, default/bounds, cursor filter checks, `limit + 1`, trimming, exact final page, and next cursor from the last returned row.
- [ ] Run `mvn test -Dtest=MovieCatalogServiceTest`; expect compilation failure.
- [ ] Implement validation and paging in the service with `InvalidCatalogRequestException` for transport mapping.
- [ ] Run `mvn test -Dtest=MovieCatalogRepositoryTest,MovieCatalogServiceTest,CatalogCursorCodecTest`; expect PASS.
- [ ] Commit with message `feat(catalog): add indexed mysql movie paging`.

---

### Task 7: HTTP API and Server Lifecycle

**Files:**
- Create: `src/main/java/com/recsys/api/serving/MovieCatalogApiService.java`
- Create: `src/test/java/com/recsys/api/serving/MovieCatalogApiServiceTest.java`
- Modify: `src/main/java/com/recsys/api/serving/BaseApiService.java`
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java`
- Create: `src/test/java/com/recsys/api/serving/RecSysServerCatalogWiringTest.java`

**Interfaces:** `GET /v1/catalog/movies`; invalid input 400, disabled/unavailable 503, timeout 504, unexpected failure 500.

- [ ] Write failing HTTP tests for success JSON, `limit=0/101/non-number`, invalid cursor, disabled client, connection failure, timeout, and unexpected SQL failure.
- [ ] Run `mvn test -Dtest=MovieCatalogApiServiceTest`; expect compilation failure.
- [ ] Add a strict bounded integer parser (do not clamp), implement the API on `ctx.blockingTaskExecutor()`, and return `Cache-Control: no-store`.
- [ ] Write failing wiring tests proving disabled startup registers a 503 route without migration/connection, enabled construction migrates before use, and shutdown closes `MySqlClient`.
- [ ] Extract a package-private catalog component factory/lifecycle holder; register `/v1/catalog/movies`; close the client on shutdown and startup failure.
- [ ] Run `mvn test -Dtest=MovieCatalogApiServiceTest,RecSysServerCatalogWiringTest,BaseApiServiceCachingTest`; expect PASS.
- [ ] Commit with message `feat(catalog): expose mysql movie catalog api`.

---

### Task 8: Gateway Contract, Auth, and Configuration

**Files:**
- Modify: `src/test/java/com/recsys/application/gateway/MicroserviceRouteTest.java`
- Modify: `src/test/java/com/recsys/application/gateway/GatewayAuthenticatorTest.java`
- Modify if exact public access is selected: `k8s/base/configmap.yaml`
- Modify: `src/main/resources/application.yml`

**Interfaces:** Existing `/api/catalog` route rewrites `/api/catalog/v1/catalog/movies` to `/v1/catalog/movies`; no new route object.

- [ ] Add a gateway rewrite test including `genre`, `limit`, and `cursor` query parameters.
- [ ] Add an auth test matching existing public movie-read policy; never allow the entire `/api/catalog` prefix anonymously.
- [ ] Run `mvn test -Dtest=MicroserviceRouteTest,GatewayAuthenticatorTest,GatewayRouteTableTest`; expect routing to pass and exact auth policy to identify any config change.
- [ ] If matching `/api/catalog/item` public access, add only `/api/catalog/v1/catalog/movies` to configured public paths. Add timeout/retry/signing placeholders to `application.yml` without a secret default.
- [ ] Rerun the gateway tests; expect PASS.
- [ ] Commit with message `test(gateway): cover mysql catalog routing`.

---

### Task 9: Real MySQL Index and Pagination Proof

**Files:**
- Create: `src/test/java/com/recsys/infrastructure/persistence/MovieCatalogMySqlIntegrationTest.java`

**Interfaces:** `@Tag("docker")`, MySQL 8.4 Testcontainer, production Flyway/repository/service.

- [ ] Write an integration test that migrates, seeds tied scores and exact-sized pages, traverses filtered/unfiltered cursors without duplicates/omissions, verifies exact final `hasMore=false`, and checks `EXPLAIN` keys for both indexes.
- [ ] Run `mvn test -DexcludedGroups=load -Dgroups=docker -Dtest=MovieCatalogMySqlIntegrationTest`; record the first migration/query/plan failure before edits.
- [ ] Fix only defects demonstrated by this test; retain recorded `EXPLAIN` evidence for any optimizer-related adjustment.
- [ ] Rerun the same command; expect PASS.
- [ ] Commit with message `test(catalog): verify mysql indexes and stable paging`.

---

### Task 10: Documentation, Review, and Full Verification

**Files:**
- Modify: `README.md`
- Modify only if implementation clarified behavior: `docs/superpowers/specs/2026-07-15-robust-mysql-catalog-querying-design.md`

- [ ] Document the endpoint, Flyway lifecycle, cursor semantics, Docker command, and `MYSQL_QUERY_TIMEOUT_SECONDS`, `MYSQL_READ_MAX_ATTEMPTS`, `MYSQL_READ_RETRY_BACKOFF_MS`, `MYSQL_CURSOR_SIGNING_KEY` without a real key.
- [ ] Run focused tests:

```bash
mvn test -Dtest='MySql*Test,Catalog*Test,MovieCatalog*Test,RecSysServerCatalogWiringTest,MicroserviceRouteTest,GatewayAuthenticatorTest,GatewayRouteTableTest'
```

- [ ] Run `mvn test`; expect BUILD SUCCESS and zero failures/errors with Docker/load tests excluded.
- [ ] When Docker is available, rerun `mvn test -DexcludedGroups=load -Dgroups=docker -Dtest=MovieCatalogMySqlIntegrationTest`; otherwise report Docker verification as unavailable.
- [ ] Run `git diff --check` and `git status --short`; expect no whitespace errors and only intentional files.
- [ ] Commit docs with message `docs: document robust mysql catalog serving`.
- [ ] Invoke `superpowers:requesting-code-review`; address technically verified findings using `superpowers:receiving-code-review`; rerun affected tests and the full suite before completion.

## Plan Self-Review

- Every approved requirement maps to a task: settings, deadlines, retries, cursor integrity, bind validation, exact paging, migrations, dual indexes, repository/service/API, gateway contract, Testcontainers `EXPLAIN`, docs, and verification.
- Types line up: codec `Position` feeds repository/service; repository returns `CatalogMovie`; service returns `CatalogPage`; API alone maps HTTP.
- No generic search, writes, ORM, arbitrary sorts, or port-8080 exposure is included.
- Enabled mode cannot silently use a default signing secret, and any anonymous gateway access is exact-path only.
