# Spec: Consolidate `serving/` Service Classes

## Objective

The offline RecSys serving layer (`com.recsys.serving`) has grown to 11 files,
several of which are near-duplicates that differ only in an entity type or a
parameter name. This spec consolidates them by **domain** so related endpoints
live in one file, reducing surface area and removing copy-paste drift — **with
zero change to runtime behavior, routes, or wire formats**.

This is a pure internal refactor. No new features, no endpoint changes.

### Who benefits
Maintainers of the offline serving API (`RecSysServer`, port 6010). Fewer files,
single source of truth for the lookup/embedding-write patterns.

### Success looks like
- `serving/` drops from 11 files to ~6.
- Every existing route still resolves to the same handler with identical
  request parsing, response JSON, and status codes.
- `mvn test` passes (after the 2 integration tests are updated to the new
  constructor names).

## Tech Stack

- Java 17, Armeria HTTP server (`AbstractHttpService`)
- Jackson for JSON, SLF4J for logging
- JUnit 5 + Mockito for tests
- Build: Maven

## Commands

```bash
# Build (skip tests)
mvn package -DskipTests

# Run the serving-layer tests touched by this change
mvn test -Dtest='RecSysServerIntegrationTest,RecSysServerRegressionTest,RecSysV2RecommendIntegrationTest'

# Full test suite
mvn test
```

## Current vs. Target Structure

### Current (11 files)
```
serving/
  BaseApiService.java            shared base (helpers)        KEEP AS-IS
  RecSysServer.java              server wiring                EDIT (wiring only)
  MovieService.java              GET /item,/movie         ─┐
  UserService.java               GET /getuser,/user        ┴─ duplicate lookup
  SetEmbeddingService.java       POST /setembedding       ─┐
  SetUserEmbeddingService.java   POST /setuserembedding    ┴─ duplicate vec-write
  RecommendationService.java     GET /getrecommendation   ─┐
  RecommendV2Service.java        POST /v2/recommend        ┤
  SimilarMovieService.java       GET /similar              ┤  recommendation family
  HealthService.java             GET /health              ─┘
  PredictionService.java         POST /v1/models/...      standalone
```

### Target (~6 files)
```
serving/
  BaseApiService.java            (unchanged)
  RecSysServer.java              (wiring updated to nested classes)
  CatalogService.java            CatalogService.Movies  → GET /item,/movie
                                 CatalogService.Users   → GET /getuser,/user
  EmbeddingService.java          EmbeddingService.SetMovie → POST /setembedding
                                 EmbeddingService.SetUser  → POST /setuserembedding
                                 (shared private vec-parse helper)
  RecommendationService.java     RecommendationService.V1      → GET /getrecommendation
                                 RecommendationService.V2      → POST /v2/recommend
                                 RecommendationService.Similar → GET /similar
                                 RecommendationService.Health  → GET /health
  PredictionService.java         (unchanged — already standalone & cohesive)
```

`streaming/flink/` and `training/rulebased/` are excluded from the Maven compile —
not touched here.

## Mechanism: nested static services

Each consolidated file is a non-instantiable container holding one `public static
final` inner class per route. Each inner class still `extends BaseApiService`, so
`RecSysServer` binds one instance per route exactly as today — no branching on
path, no parameterized dispatch.

## Code Style

One real example — the container + nested-service pattern:

```java
public final class CatalogService {

    private CatalogService() {}   // container only; never instantiated

    /** GET /item, /movie — fetch a movie by numeric id. */
    public static final class Movies extends BaseApiService {
        private final DataManager dataManager;

        public Movies(DataManager dataManager) {
            this.dataManager = dataManager;
        }

        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
                try {
                    int movieId = requiredIntParam(ctx, "id");
                    Movie movie = dataManager.getMovieById(movieId);
                    if (movie == null)
                        return writeError(HttpStatus.NOT_FOUND, "movie not found", "id", movieId);
                    return writeJson(HttpStatus.OK, movie);
                } catch (BadRequestException e) {
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (Exception e) {
                    log.error("Unexpected error in CatalogService.Movies", e);
                    return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
                }
            }, ctx.blockingTaskExecutor()));
        }
    }

    /** GET /getuser, /user — fetch a user by numeric id. */
    public static final class Users extends BaseApiService { /* same shape, userId */ }
}
```

Conventions (all preserved from the existing code):
- Inner classes named for the resource (`Movies`, `Users`, `SetMovie`, `SetUser`,
  `V1`, `V2`, `Similar`, `Health`).
- Async via `CompletableFuture.supplyAsync(..., ctx.blockingTaskExecutor())` for
  GET, `req.aggregate().thenApplyAsync(...)` for POST — unchanged per endpoint.
- Error-handling ladder unchanged: `BadRequestException` → 400, unexpected → 500
  with `log.error(...)`.
- Log messages updated to the new qualified name (e.g.
  `"Unexpected error in CatalogService.Movies"`).
- Records that belonged to a service move with it (e.g. `SimilarMovieService`'s
  `ScoredMovie` / `SimilarMoviesResult` become
  `RecommendationService.Similar.ScoredMovie` etc., or nested in `Similar`).
- De-duplicate the embedding-write body parsing (read `vec` query param, else
  aggregated body, blank-check, `VectorMath.parseVector`) into one private static
  helper in `EmbeddingService` used by both `SetMovie` and `SetUser`.

## Routes — MUST stay byte-identical (hard constraint)

The API gateway strips prefixes and forwards these exact suffixes. Do not rename,
add, or drop any route.

| Route | Today | After |
|---|---|---|
| `/item`, `/movie` | `MovieService` | `CatalogService.Movies` |
| `/getuser`, `/user` | `UserService` | `CatalogService.Users` |
| `/similar` | `SimilarMovieService` | `RecommendationService.Similar` |
| `/getrecommendation`, `/recommendation` | `RecommendationService` | `RecommendationService.V1` |
| `/v2/recommend` | `RecommendV2Service` | `RecommendationService.V2` |
| `/setembedding` | `SetEmbeddingService` | `EmbeddingService.SetMovie` |
| `/setuserembedding` | `SetUserEmbeddingService` | `EmbeddingService.SetUser` |
| `/health` | `HealthService` | `RecommendationService.Health` |
| `/v1/models/recmodel:predict` | `PredictionService` | `PredictionService` (unchanged) |

## Testing Strategy

Framework: JUnit 5 + Mockito. No new tests are required — existing integration
and regression tests already pin the HTTP behavior; they are the safety net.

Two test files reference the old constructors and must be updated to the nested
names (behavioral assertions stay the same):

- `src/test/java/com/recsys/serving/RecSysServerIntegrationTest.java`
  - `new MovieService(mockData)` → `new CatalogService.Movies(mockData)`
  - `new UserService(mockData)` → `new CatalogService.Users(mockData)`
  - `new RecommendationService(mockData, recallService)` → `…RecommendationService.V1(…)`
  - `new SimilarMovieService(mockEmb, mockData)` → `new RecommendationService.Similar(mockEmb, mockData)`
  - `new SetEmbeddingService(mockEmb, cg)` → `new EmbeddingService.SetMovie(mockEmb, cg)`
  - `new SetUserEmbeddingService(mockUserEmb)` → `new EmbeddingService.SetUser(mockUserEmb)`
  - `new HealthService()` → `new RecommendationService.Health()`
  - `new PredictionService(mockPrediction)` → unchanged
- `src/test/java/com/recsys/serving/RecSysServerRegressionTest.java`
  - `new RecommendationService(mockData, mockRecall)` → `new RecommendationService.V1(…)`
- `RecSysV2RecommendIntegrationTest.java` uses `new RecommendV2Service(mockPipeline)`
  → `new RecommendationService.V2(mockPipeline)`

Verification: the three serving tests above pass, then the full `mvn test` suite
passes.

## Boundaries

- **Always:**
  - Preserve every route path and the exact request-parsing / response-JSON / status-code behavior.
  - Keep `BaseApiService` and `PredictionService` functionally unchanged.
  - Update the touched tests in the same change and run them.
- **Ask first:**
  - Any change to a route path, response shape, or `BaseApiService` API.
  - Splitting work across more or fewer files than the target above.
  - Touching anything outside `com.recsys.serving` + the 3 named test files.
- **Never:**
  - Add new endpoints or features.
  - Change wire formats (JSON keys, error bodies, `Retry-After`, etc.).
  - Delete a test to make the build pass.

## Success Criteria

1. `serving/` contains exactly: `BaseApiService`, `RecSysServer`, `CatalogService`,
   `EmbeddingService`, `RecommendationService`, `PredictionService` (6 files).
2. `MovieService`, `UserService`, `SetEmbeddingService`, `SetUserEmbeddingService`,
   `SimilarMovieService`, `RecommendV2Service`, `HealthService` are deleted.
3. All 9 routes resolve to the mapped nested handlers; `git diff` on `RecSysServer`
   shows only handler-construction changes, no route-string changes.
4. `mvn test` is green.
5. No production code outside `com.recsys.serving` is modified.

## Open Questions

1. **Health placement.** The chosen "group by domain" option bundles `Health`
   into `RecommendationService`, which is slightly off-domain (health is a
   liveness probe, not a recommendation). Acceptable trade-off for file-count
   reduction, but the trivially clean alternative is to keep a standalone 15-line
   `HealthService.java` (→ 7 files). **Default: follow the chosen grouping
   (Health nested in RecommendationService).** Flag if you'd rather keep it standalone.
2. **Similar records location.** `ScoredMovie` / `SimilarMoviesResult` will nest
   inside `RecommendationService.Similar`. If any external code references
   `SimilarMovieService.ScoredMovie` it must move too — grep confirms only the
   serving layer uses them, so this is internal.
