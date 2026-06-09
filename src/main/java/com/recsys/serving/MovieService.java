package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.DataManager;
import com.recsys.domain.Movie;

import java.util.concurrent.CompletableFuture;

public class MovieService extends BaseApiService {

    private final DataManager dataManager;

    public MovieService(DataManager dataManager) {
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
                log.error("Unexpected error in MovieService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
