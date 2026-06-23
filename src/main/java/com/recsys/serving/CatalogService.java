package com.recsys.serving;

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

    /** GET /item, /movie — fetch a movie by numeric {@code id}. */
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
                    if (movie == null) return writeError(HttpStatus.NOT_FOUND, "movie not found", "id", movieId);
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
                    if (user == null) return writeError(HttpStatus.NOT_FOUND, "user not found", "userId", userId);
                    return writeJson(HttpStatus.OK, user);
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
