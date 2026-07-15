# Robust MySQL Catalog Querying and Indexing Design

## Goal

Turn the existing optional MySQL query utilities into a production-ready, read-only catalog path while preserving the Redis/ONNX recommendation hot paths. The new endpoint is `GET /v1/catalog/movies` on the port-6010 catalog service and is also reachable through the API gateway.

## Scope

This change has two stages delivered together:

1. Harden the reusable MySQL query and pagination layer.
2. Add a Flyway-managed movie catalog schema, repository, service, HTTP endpoint, and gateway route.

The model service on port 8080 does not expose the catalog API. Existing recommendation endpoints, Redis data paths, ONNX inference, authentication, and rate limiting remain unchanged except that the gateway gains the catalog route.

## API Contract

### Request

```http
GET /v1/catalog/movies?genre=Sci-Fi&limit=20&cursor=<opaque-token>
```

- `genre` is optional. A missing or blank value means all genres. Non-blank values are trimmed and used as exact matches.
- `limit` defaults to `20`, must be a positive integer, and cannot exceed `100`.
- `cursor` is optional and omitted on the first page.

### Success response

```json
{
  "items": [
    {
      "id": 42,
      "title": "Example",
      "year": 2026,
      "genre": "Sci-Fi",
      "popularityScore": "98.125000",
      "updatedAt": "2026-07-15T12:00:00Z"
    }
  ],
  "nextCursor": "<opaque-token-or-null>",
  "hasMore": true
}
```

Rows are ordered by `popularity_score DESC, id DESC`. The ID is the deterministic tie-breaker.

### Errors

| Condition | Status |
|---|---:|
| Invalid limit, genre, malformed cursor, tampered cursor, or cursor/filter mismatch | 400 |
| MySQL disabled or connection pool unavailable | 503 |
| Query deadline exceeded | 504 |
| Unexpected SQL or mapping failure | 500 |

Errors use the service's existing JSON error response conventions and do not expose SQL, credentials, cursor signing keys, or driver internals.

## Schema and Index

Flyway owns the catalog schema. Migration startup is conditional on MySQL being enabled, so the default Redis-only deployment starts without contacting MySQL.

```sql
CREATE TABLE movies (
  id BIGINT NOT NULL PRIMARY KEY,
  title VARCHAR(255) NOT NULL,
  year INT NULL,
  genre VARCHAR(64) NULL,
  popularity_score DECIMAL(12,6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
      ON UPDATE CURRENT_TIMESTAMP(6),
  INDEX idx_movies_genre_popularity_id
      (genre, popularity_score DESC, id DESC)
);
```

The initial index deliberately omits projected text columns. Appending `title` and other payload fields would increase index size and write amplification; it is allowed only after MySQL integration benchmarks demonstrate a material benefit. The unfiltered query can still scan the `(genre, popularity_score, id)` index inefficiently because `genre` is the leading column, so the migration also adds:

```sql
CREATE INDEX idx_movies_popularity_id
    ON movies (popularity_score DESC, id DESC);
```

The repository chooses `idx_movies_genre_popularity_id` for filtered queries and `idx_movies_popularity_id` for unfiltered queries. Tests verify both choices with `EXPLAIN`.

## Hardened SQL Layer

### Deadlines

Every `MySqlClient` query receives a positive timeout. The environment setting `MYSQL_QUERY_TIMEOUT_SECONDS` defaults to `2` and must be between `1` and `30`. Query overloads that previously meant “driver default” delegate to the configured timeout instead. Explicit test-only connection overloads may accept a timeout but reject non-positive values.

JDBC URL connect/socket timeouts and Hikari connection acquisition timeouts remain independent outer bounds.

### Retry policy

Read operations make at most two total attempts. Only SQL exceptions classified as transient connection failures are retried, using SQLState class `08` or `SQLTransientConnectionException`. The retry reacquires a connection from Hikari and waits a small bounded backoff. It does not retry:

- `SQLTimeoutException`;
- authentication or authorization failures;
- syntax and missing-schema/index failures;
- mapping failures;
- any write operation (the client remains read-only).

Interrupted backoff restores the thread interrupt flag and propagates the original failure with the interruption attached.

### Filter safety

Request data never becomes SQL text. The catalog repository chooses between two fixed query shapes—genre-filtered and unfiltered—and binds all values. The general-purpose `wherePredicates` interface remains restricted to trusted, compile-time fragments and is documented as internal-only; the new endpoint does not accept or construct arbitrary predicate fragments.

Generated plans validate that the number of bind placeholders equals the number of bind values, accounting only for ordinary `?` placeholders supported by these fixed plans. This catches developer mistakes before JDBC execution.

### Accurate pagination

Repository queries request `limit + 1` rows. The service:

1. Sets `hasMore` when the extra row exists.
2. Removes the extra row from the response.
3. Creates `nextCursor` from the last returned row only when `hasMore` is true.

This eliminates the false-positive next page produced when the final page contains exactly `limit` rows.

## Cursor Security

Catalog cursors are independent of existing recommendation cursors. Their payload contains:

- schema version (`1`);
- normalized genre filter or an explicit no-filter marker;
- exact decimal popularity score string;
- movie ID.

The payload is Base64 URL encoded and authenticated with HMAC-SHA256 using `MYSQL_CURSOR_SIGNING_KEY`. The key is required whenever MySQL catalog serving is enabled and must contain at least 32 UTF-8 bytes. The signature is compared in constant time.

Decoding enforces a maximum encoded length of 2 KiB before allocation, validates the version and field count, parses numeric values strictly, and reports a catalog-domain invalid-cursor exception. A cursor signed for one genre cannot be replayed against another genre or against an unfiltered query.

Cursor confidentiality is not required: the token is opaque and tamper-evident, but its position fields are not encrypted.

## Components

### Persistence configuration

`MySqlConnectionSettings` gains validated query timeout, retry count/backoff, and cursor signing-key settings. Environment variables remain the single source used by the non-Spring port-6010 service. The existing `application.yml` entries are aligned with these names for documentation and Spring compatibility.

### Catalog repository

`MovieCatalogRepository` owns the two fixed SQL plans, row mapping, index selection, and read retry invocation. It returns an internal slice containing at most `limit + 1` rows; it does not know HTTP types.

### Catalog service

`MovieCatalogService` validates and normalizes input, decodes and checks cursors, calls the repository, trims the lookahead row, and encodes the next cursor. Its output is a transport-neutral page record.

### HTTP service

`MovieCatalogApiService` parses query parameters, invokes the catalog service, serializes JSON, and maps domain/persistence failures to the documented status codes. It is registered at `/v1/catalog/movies` by `RecSysServer` even when MySQL is disabled; disabled access returns 503 rather than making startup fail.

### Gateway

The gateway routes `/api/catalog/v1/catalog/movies` to the port-6010 service using its existing catalog route group. No new retry, authentication, or circuit-breaker policy is introduced at the gateway.

### Flyway lifecycle

A small catalog database bootstrap component runs Flyway before the port-6010 server accepts requests only when MySQL is enabled. Migration failure prevents startup because serving against a partially migrated schema is unsafe. The bootstrap closes its migration data source after completion; the query pool remains owned by `MySqlClient`.

## Observability and Operations

- MySQL health remains separately reportable as disabled, reachable, timed out, or failed.
- Catalog failures are logged with exception class, SQLState, attempt count, and elapsed time, but never SQL bind values or credentials.
- Retry count and query timeout are environment-configurable within validated bounds.
- `MYSQL_ENABLED=false` remains the default.
- Required enabled-mode variables are `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD`, and `MYSQL_CURSOR_SIGNING_KEY`.

## Testing

### Unit tests

- settings validation and defaults;
- mandatory query timeout application;
- transient retry and non-retry classification;
- interrupted retry behavior;
- placeholder/bind-count validation;
- cursor round trip, tamper detection, filter mismatch, version rejection, malformed/oversized input;
- fixed filtered/unfiltered query generation and index selection;
- `limit + 1` trimming and exact `hasMore` behavior;
- HTTP parameter parsing and 400/503/504 mappings;
- disabled MySQL startup behavior.

All behavior changes follow red-green-refactor: a focused test must fail for the expected reason before production code is changed.

### MySQL integration tests

A `@Tag("docker")` Testcontainers test starts MySQL, runs Flyway, inserts rows including tied popularity scores, and verifies:

- first and subsequent pages have no duplicates or omissions;
- filtered and unfiltered ordering is stable;
- an exact final page returns `hasMore=false`;
- `EXPLAIN` reports `idx_movies_genre_popularity_id` for genre-filtered lookup;
- `EXPLAIN` reports `idx_movies_popularity_id` for unfiltered lookup.

Docker tests remain excluded from the default Maven test run under the repository's existing tagging conventions and are documented with an explicit command.

### Regression verification

Run the focused SQL/catalog tests, the Docker-tagged MySQL integration test when Docker is available, and the complete `mvn test` suite. Existing recommendation API contract tests must remain unchanged and green.

## Rollout

1. Deploy with `MYSQL_ENABLED=false` to verify no change to existing paths.
2. Apply the Flyway migration in a non-production MySQL environment and seed catalog data.
3. Enable MySQL and the signing key on one port-6010 instance.
4. Exercise direct and gateway catalog paging while monitoring timeout/retry/error metrics.
5. Roll out normally after query plans and index use are confirmed.

Rollback disables MySQL catalog serving. Migrations are forward-only; disabling the feature does not drop the table or indexes.

## Non-Goals

- Catalog writes or administration APIs.
- Full-text search, fuzzy title search, or arbitrary sort fields.
- Exposing the catalog through port 8080.
- Replacing Redis recall or ONNX ranking with SQL.
- Encrypting cursor contents.
- Generic dynamic SQL or ORM adoption.
