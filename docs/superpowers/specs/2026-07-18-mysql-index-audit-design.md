# MySQL Index Audit and Contract Design

## Goal

Optimize and protect the repository's MySQL indexes using the production query shapes present in the codebase. The work must prevent missing, redundant, or speculative indexes without changing the Redis and ONNX recommendation paths.

## Evidence and Scope

This audit is based on repository queries and expected access patterns rather than production slow-query logs or table statistics. The only production MySQL workload currently implemented is movie catalog pagination in `MovieCatalogRepository` over the Flyway-managed `movies` table.

The audit covers:

- production JDBC query shapes;
- Flyway-owned MySQL indexes;
- static query-to-index contract validation;
- Docker-tagged MySQL `EXPLAIN` validation; and
- documentation of the index inventory and rules for future queries.

It excludes Redis key design, ONNX serving, schema changes unrelated to query performance, production telemetry collection, and speculative indexes for unimplemented query shapes.

## Query-to-Index Contract

The filtered catalog query has equality filtering on `genre`, then seek pagination and ordering on `popularity_score DESC, id DESC`. It uses:

```sql
INDEX idx_movies_genre_popularity_id
    (genre, popularity_score DESC, id DESC)
```

The unfiltered catalog query seeks and orders directly on `popularity_score DESC, id DESC`. It uses:

```sql
INDEX idx_movies_popularity_id
    (popularity_score DESC, id DESC)
```

Both indexes are necessary. The genre-leading index cannot efficiently serve the global ordering because MySQL's B-tree leftmost prefix begins with `genre`. Conversely, the unfiltered index cannot efficiently combine exact genre filtering with the required ordering.

No additional production MySQL query shape was found. The audit therefore introduces no new runtime index migration and does not modify `V1__create_movies_catalog.sql`. An empty or cosmetic migration would create operational noise without changing an execution plan.

## Rejected Alternatives

### Cover all projected columns

Appending `title`, `year`, `genre`, and `updated_at` could avoid some clustered-row lookups. Repository-only evidence does not show that row lookup is the bottleneck, while wider secondary indexes unconditionally increase storage, buffer-pool pressure, and write amplification. Covering payload columns require benchmark or production-plan evidence before adoption.

### Consolidate to one secondary index

Keeping only one of the two indexes would reduce storage and mutation cost but would degrade either filtered or unfiltered pagination. This conflicts with the two implemented query contracts.

### Add indexes for anticipated filters

Indexes for title, year, updated time, or arbitrary combinations are not justified because no production query uses those access paths. Future features must introduce their query and index contract together.

## Validation Components

### Static index contract test

A fast test will enumerate every production catalog query shape and assert:

- the SQL selects the intended named index;
- equality-filter columns form the leading index prefix;
- seek and ordering columns follow in matching order;
- the deterministic `id` tie-breaker remains present; and
- the Flyway migration defines each expected index exactly once.

The test will fail if a production query references an undeclared index, an expected index disappears, duplicate index definitions are introduced, or query ordering drifts away from the index contract.

The test is intentionally scoped to production MySQL queries, not illustrative SQL in tests or documentation.

### MySQL execution-plan test

The Docker-tagged integration test remains the authoritative optimizer check. After Flyway migration and representative seeded rows, it runs `EXPLAIN` for both repository plans and verifies that MySQL reports:

- `idx_movies_genre_popularity_id` for genre-filtered pagination; and
- `idx_movies_popularity_id` for unfiltered pagination.

It also retains pagination correctness checks for tied scores, page boundaries, omissions, and duplicates. Static tests validate intent when Docker is unavailable; only the MySQL test validates the actual optimizer plan.

## Future Index Policy

A new production MySQL query must declare its access pattern in terms of equality predicates, range or seek predicates, ordering, and stable tie-breakers. Its implementation must include:

1. a Flyway migration when no existing index satisfies that pattern;
2. a static query-to-index contract assertion;
3. a Docker-tagged `EXPLAIN` assertion; and
4. a short write/storage trade-off rationale for any new or widened index.

An index may be removed only after all production query contracts are checked and MySQL plan tests demonstrate that no supported path depends on it.

## Verification

Run the focused unit tests for the migration and repository contract, then the Docker-tagged MySQL integration test when Docker is available, followed by the complete Maven test suite. A successful result means every current production MySQL query has one justified index path, both catalog variants use their intended plans, and no unsupported index is added.

## Rollout and Risk

This work changes tests and documentation only; it does not alter runtime DDL or request behavior. It can roll out with the normal application build. The primary risk is a brittle SQL-text assertion, so contract checks should normalize whitespace and inspect only the fixed production query definitions and migration statements needed to establish the index mapping.
