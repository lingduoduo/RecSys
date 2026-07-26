# Database Indexing in Recsys-Backend-Service

An investigation of how the relational read model earns its query performance:
which secondary indexes exist and why, how every query is *pinned* to its index
and kept honest by contract tests plus a real `EXPLAIN`, and the three index-access
patterns (covering count, keyset seek, delayed-join deep-offset) the code emits.
This is the indexing counterpart to the
[Partitioning investigation](14_Partitioning.md) — where that doc treats a keyset
cursor as a way to *partition a result set*, this one treats the same query as an
*index range-scan* and asks which B-tree makes it cheap.

## The big picture

MySQL here is a deliberately small, opt-in read model (the serving hot paths stay
on Redis/ONNX), so indexing is governed by two rules:

- **An index is a contract, not a hope.** Every fixed query pins its plan with
  `FORCE INDEX (...)` rather than trusting the optimizer, and that pinning is
  guarded by a static contract test (hint + exact column order) *and* a
  Docker-tagged `EXPLAIN` that asserts the optimizer actually chose the index.
- **Widen an index only on evidence.** The secondary indexes deliberately omit
  projected payload columns; covering is added only when `EXPLAIN ANALYZE`,
  slow-query logs, or representative benchmarks show clustered-row lookup is the
  bottleneck — trading a smaller, cheaper-to-maintain B-tree for an occasional
  clustered lookup until proven otherwise.

The full secondary-index inventory (7 indexes across three migrations):

| Index | Table | Columns | Serves |
|---|---|---|---|
| `idx_movies_genre_popularity_id` | `movies` | `(genre, popularity_score DESC, id DESC)` | Genre-filtered catalog page |
| `idx_movies_popularity_id` | `movies` | `(popularity_score DESC, id DESC)` | Global (unfiltered) catalog page |
| `idx_outbox_claim` | `event_outbox` | `(status, next_attempt_at, created_at)` | Dispatcher claim scan |
| `idx_outbox_lease` | `event_outbox` | `(status, lease_expires_at)` | Expired-lease reclaim |
| `idx_outbox_reconcile` | `event_outbox` | `(destination, status, created_at, broker_acknowledged_at)` | Reconciliation sweep |
| `idx_outbox_aggregate` | `event_outbox` | `(aggregate_type, aggregate_id, created_at)` | Per-aggregate history |
| `idx_saga_correlation` | `saga_instance` | `(correlation_id, created_at)` | Saga lookup by correlation |

An allowlist test (`MySqlIndexContractTest`) asserts the migrations define
*exactly* these seven and no stray indexes.

## 1. The catalog secondary indexes

`V1__create_movies_catalog.sql`
([src/main/resources/db/migration/V1__create_movies_catalog.sql](../../src/main/resources/db/migration/V1__create_movies_catalog.sql))
creates `movies` (`id`, `title`, `year`, `genre`, `popularity_score DECIMAL(12,6)`,
`updated_at`) and two composite indexes:

```sql
-- inline in CREATE TABLE movies
INDEX idx_movies_genre_popularity_id (genre, popularity_score DESC, id DESC)

-- standalone
CREATE INDEX idx_movies_popularity_id ON movies (popularity_score DESC, id DESC)
```

Both catalog queries order by `(popularity_score DESC, id DESC)` — popularity is
the sort key and `id` is the deterministic tiebreaker that also anchors the keyset
cursor (§3).

**Why both are needed.** MySQL's leftmost-prefix rule means the
genre-leading B-tree (`idx_movies_genre_popularity_id`) is ordered by `genre`
first, so it can only give a popularity order *within a genre*. Serving a
**global** `ORDER BY popularity_score` from it would require scanning every genre
partition and merge-sorting — so a dedicated `(popularity_score DESC, id DESC)`
index provides the global order directly. The genre index is not redundant with
the global one, and vice versa.

**Why payload columns are omitted.** Appending `title` / `year` / `updated_at`
would make either index *covering* (the query answered entirely from the index,
no clustered-row lookup), but it would unconditionally grow the B-tree, add
buffer-pool pressure, and amplify writes. With no repository-level evidence that
the clustered lookup is the bottleneck, covering is deliberately deferred — see
the [MySQL index audit](../superpowers/specs/2026-07-18-mysql-index-audit-design.md)
and [robust catalog querying](../superpowers/specs/2026-07-15-robust-mysql-catalog-querying-design.md)
designs. The verification evidence is defined in [§6](#6-testing-the-indexes): the
static index contracts and the Docker-tagged optimizer assertion are both required.

## 2. Plan pinning and query-to-index contracts

The two catalog queries are not hand-tuned per call — they live as fixed plans in
[`MovieCatalogRepository`](../../src/main/java/com/recsys/infrastructure/persistence/MovieCatalogRepository.java),
each carrying a `FORCE INDEX` hint so the optimizer can't drift to a worse plan:

```sql
-- FILTERED_SQL
FROM movies FORCE INDEX (idx_movies_genre_popularity_id)
WHERE genre = ? AND (popularity_score, id) < (?, ?)
ORDER BY popularity_score DESC, id DESC LIMIT ?

-- UNFILTERED_SQL
FROM movies FORCE INDEX (idx_movies_popularity_id)
WHERE (popularity_score, id) < (?, ?)
ORDER BY popularity_score DESC, id DESC LIMIT ?
```

The row-tuple comparison `(popularity_score, id) < (?, ?)` is the keyset seek
predicate (§3); the opening page uses a sentinel "before the first row" anchor
(`BEFORE_FIRST_SCORE = 1000000.000000`, `id = Long.MAX_VALUE`).

That pinning is enforced at two levels — both required for **every** new
production query:

- **Static contract** —
  [`MySqlIndexContractTest`](../../src/test/java/com/recsys/infrastructure/persistence/MySqlIndexContractTest.java)
  via
  [`MySqlIndexContractAssertions`](../../src/test/java/com/recsys/infrastructure/persistence/MySqlIndexContractAssertions.java)
  checks, with no database, that (a) the migration declares the index *exactly
  once*, (b) with the exact ordered column list (inline or standalone form), and
  (c) the repository plan contains `FORCE INDEX (<name>)`, the expected equality
  predicate, and the expected `ORDER BY`. A separate allowlist test asserts the
  migration set defines only the seven workload-required indexes.
- **Real `EXPLAIN`** —
  [`MovieCatalogMySqlIntegrationTest`](../../src/test/java/com/recsys/infrastructure/persistence/MovieCatalogMySqlIntegrationTest.java)
  (`@Tag("docker")`, Testcontainers `mysql:8.4`) runs the real Flyway migration,
  seeds rows with score ties (to exercise the `id` tiebreaker), and asserts the
  optimizer's chosen `key` equals the expected index. This is the authoritative
  check — the static test proves the *hint* is present; `EXPLAIN` proves the
  *engine* honors it.

So a new catalog query ships three things together: a Flyway-managed index, a
static FORCE-INDEX/column-order contract, and a Docker-tagged `EXPLAIN` assertion
([index audit design](../superpowers/specs/2026-07-18-mysql-index-audit-design.md)).
Plans execute through
[`MySqlClient`](../../src/main/java/com/recsys/infrastructure/persistence/MySqlClient.java) —
a **read-only** HikariCP pool (`setReadOnly(true)`, max 5) that applies a
per-statement `setQueryTimeout` (`MYSQL_QUERY_TIMEOUT_SECONDS`, default 2) and
binds every request value as a positional parameter.

## 3. Index-access patterns via `MillionScalePaginationSql`

[`MillionScalePaginationSql`](../../src/main/java/com/recsys/application/pagination/MillionScalePaginationSql.java)
is the reusable builder that turns a table + index into MySQL-friendly SQL. Every
request value is a bind parameter; only identifiers are validated (regex-checked),
predicates rejecting blank/`;` fragments, page size bounded 1–1000, and a `SqlPlan`
record that enforces placeholder-count == bind-count. It emits three access
patterns, each mapped to how it rides an index:

- **Covering-index count** — `countWithCoveringIndex(...)` emits
  `SELECT COUNT(*) FROM t FORCE INDEX (<covering>) <where>`, forcing the narrow
  secondary index instead of the clustered PK so `COUNT` walks the smaller B-tree
  (materially cheaper on large tables).
- **Keyset seek** — `cursorPage(...)` emits `FORCE INDEX (<covering>)` plus the
  seek predicate `(sort <op> ? OR (sort = ? AND id <op> ?))` and
  `ORDER BY sort dir, id dir LIMIT ?` — **no `OFFSET`**. This is an index
  range-scan that starts *after* the last returned tuple, so page N costs the same
  as page 1. `cursorPageBefore(...)` walks backward with reversed operators. The
  cursor/result-window semantics — the seek-anchor model, HMAC-signed catalog
  cursors — are covered from the partitioning angle in
  [14_Partitioning §4](14_Partitioning.md#4-keyset--cursor-pagination--partitioning-a-result-set);
  here the point is simply that the `(sort DESC, id DESC)` composite index makes
  the seek an index range-scan rather than a sort.
- **Delayed-join deep-offset** — `delayedJoinPage(...)` handles genuine
  random-access deep pages (admin "jump to page 1000"): an inner query walks
  *only* the covering index returning `(id, sort)` with `LIMIT ? OFFSET ?`, then an
  outer query joins those page keys back to the base table
  (`JOIN (inner) page_keys ON t.id = page_keys.id`). The expensive offset walk is
  index-only; the clustered lookup happens for just the ~`limit` rows on the page.
- **Covering DDL** — `coveringIndexDdl(...)` emits the matching
  `CREATE INDEX` with column order `equality-filters → sort → id → extra covered
  columns`, so a covering index (when justified by evidence) is generated the same
  way the queries expect it.

The helper methods above are the authoritative API for these three access patterns;
their unit tests exercise placeholder/bind parity, keyset seek semantics, and the
delayed-join shape.

## 4. Indexes beyond the catalog — the outbox and saga workload

`V2__create_event_outbox_and_sagas.sql` indexes the transactional-outbox and saga
tables around their access patterns rather than their identity columns:
`idx_outbox_claim (status, next_attempt_at, created_at)` for the dispatcher's
"claim the next due batch" scan, `idx_outbox_lease (status, lease_expires_at)` for
reclaiming expired leases, `idx_outbox_reconcile (destination, status, created_at,
broker_acknowledged_at)` for the reconciliation sweep, `idx_outbox_aggregate
(aggregate_type, aggregate_id, created_at)` for per-aggregate history, and
`idx_saga_correlation (correlation_id, created_at)` for saga lookup. Each leads with
the equality/selectivity column (`status`, `destination`, `aggregate_type`) and
trails with a time column so the index serves both the filter and the order. How
these tables are used is covered in the
[Eventual Consistency investigation](15_Eventual_Consistency.md); `V3` adds JSON
progress columns only, no indexes.

## 5. Other index types (for context)

Relational B-trees are one of several "indexes" in the system:

- **LSH ANN vector index** —
  [`EmbeddingLSH`](../../src/main/java/com/recsys/infrastructure/vectordb/EmbeddingLSH.java)
  (random-hyperplane buckets with Hamming-1 probing) and the exact/flat fallback
  both implement `VectorIndex`;
  [`CandidateGenerator`](../../src/main/java/com/recsys/infrastructure/vectordb/CandidateGenerator.java)
  picks `lsh`/`ann` vs `exact`/`flat` for embedding recall. This indexes vectors by
  approximate cosine neighborhood, not by a sort key.
- **Redis ZSET-as-index** — `ShardedRecordStore` maintains a per-device sorted-set
  index (`ZADD NX` / `ZADD XX GT` keyed by sequence number) so per-device reads
  page in order — a Redis-side ordered index, discussed in
  [14_Partitioning §1](14_Partitioning.md#1-consistent-hash-record-sharding).
- **In-memory inverted index** —
  [`DataManager`](../../src/main/java/com/recsys/infrastructure/dataloading/DataManager.java)
  holds `moviesByGenre` (`Map<String, List<Movie>>`), an in-memory genre index for
  the non-SQL serving path.

## 6. Testing the indexes

- **Static contracts** — `MySqlIndexContractTest` (FORCE INDEX + exact column
  order per query; the 7-index allowlist), `MySqlIndexContractAssertions` (the
  assertion engine), `MovieCatalogRepositoryTest` (exact plan SQL incl. the
  `FORCE INDEX (...)` clause and bind values).
- **Real optimizer** — `MovieCatalogMySqlIntegrationTest` (`@Tag("docker")`,
  Testcontainers `mysql:8.4`, `EXPLAIN` asserts the chosen `key`).
- **Access patterns** — `MillionScalePaginationSqlTest` pins covering-DDL column
  order, keyset-not-offset, placeholder/bind parity, delayed-join offset-inside-
  subquery, `countWithCoveringIndex`, and reversed `cursorPageBefore` ordering.
- **Execution** — `MySqlClientTest` (query execution over the FORCE-INDEX plans).

## Sharp edges — notes

1. **Covering is deferred by policy, not oversight.** The catalog indexes omit
   payload columns on purpose; adding them is gated behind `EXPLAIN ANALYZE` /
   slow-query / benchmark evidence, not added speculatively.
2. **`FORCE INDEX` is a guarantee that must be proven.** A hint alone can be
   silently ignored by the engine, so the static contract test is always paired
   with the Docker `EXPLAIN` assertion — never one without the other.
3. **New queries can't skip the discipline.** The allowlist test fails if a
   migration adds an index without the matching contract, and a query without a
   `FORCE INDEX`/`EXPLAIN` pair has no home in the pattern — the three-part
   requirement is enforced by tests, not convention.
4. **Deep `OFFSET` is only for true random access.** Keyset paging is the default
   (constant cost per page); `delayedJoinPage` exists for admin "jump to page N"
   and still pays an index-only offset walk — it bounds the cost, it does not
   erase it.
5. **No MySQL table partitioning yet.** Indexing scales the single `movies` table;
   native table partitioning / sharding is deferred — see
   [14_Partitioning](14_Partitioning.md) sharp edges.
