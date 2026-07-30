package com.recsys.api.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.domain.item.Movie;
import com.recsys.domain.user.User;

import java.util.concurrent.CompletableFuture;

/**
 * Catalog lookups by numeric id. Both endpoints are thin reads over
 * {@link DataManager}; they share the same async + error-handling shape and
 * differ only in entity type and query-parameter name.
 */
public final class CatalogService {

    private CatalogService() {}

    /** GET /item, /movie — fetch a movie by numeric {@code id}. Shared, non-personalized: cacheable. */
    public static final class Movies extends BaseApiService {

        // Catalog metadata is effectively static; serve stale for a day while revalidating.
        private static final String CACHE_CONTROL = HttpCaching.publicCache(3600, 86400);

        private final DataManager dataManager;

        public Movies(DataManager dataManager) {
            this.dataManager = dataManager;
        }

        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
                try {
                    // Cache-key parameter: canonical spellings only, so one edge cache key
                    // maps to one body. requiredIntParam would accept 007/+7/" 7" as aliases.
                    int movieId = cacheKeyIntParam(ctx, "id");
                    Movie movie = dataManager.getMovieById(movieId);
                    // Not cacheable: the movie may be added later, and a pinned 404 at the edge
                    // would outlive the gap.
                    if (movie == null) return writeNoStoreJson(HttpStatus.NOT_FOUND,
                            java.util.Map.of("error", "movie not found", "id", movieId));
                    return writeCacheableJson(HttpStatus.OK, movie, CACHE_CONTROL, req);
                } catch (BadRequestException e) {
                    // Errors are never cacheable: no-store, else CloudFront's default Error
                    // Caching Minimum TTL (10s) would pin a 400 at the edge.
                    return writeNoStoreError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (Exception e) {
                    log.error("Unexpected error in CatalogService.Movies", e);
                    return writeNoStoreError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
                }
            }, ctx.blockingTaskExecutor()));
        }
    }

    /** GET /getuser, /user — fetch a user by numeric {@code userId}. */
    public static final class Users extends BaseApiService {

        private final DataManager dataManager;

        public Users(DataManager dataManager) {
            this.dataManager = dataManager;
        }

        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
                try {
                    int userId = requiredIntParam(ctx, "userId");
                    User user = dataManager.getUserById(userId);
                    if (user == null) return writeNoStoreJson(HttpStatus.NOT_FOUND,
                            java.util.Map.of("error", "user not found", "userId", userId));
                    return writeNoStoreJson(HttpStatus.OK, user);
                } catch (BadRequestException e) {
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (Exception e) {
                    log.error("Unexpected error in CatalogService.Users", e);
                    return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
                }
            }, ctx.blockingTaskExecutor()));
        }
    }
}
