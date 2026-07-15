package com.recsys.api.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.application.catalog.MovieCatalogService;
import com.recsys.domain.catalog.CatalogMovie;
import com.recsys.domain.catalog.CatalogPage;

import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP boundary for the optional MySQL-backed movie catalog. */
public final class MovieCatalogApiService extends BaseApiService {
    private final MovieCatalogService catalog;
    private final boolean available;

    public MovieCatalogApiService(MovieCatalogService catalog, boolean available) {
        this.catalog = catalog;
        this.available = available;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        if (!available || catalog == null) {
            return writeNoStoreError(HttpStatus.SERVICE_UNAVAILABLE, "movie catalog unavailable");
        }

        final Integer limit;
        try {
            limit = optionalBoundedIntParam(ctx, "limit", null, 1, 100);
        } catch (BadRequestException failure) {
            return writeNoStoreError(HttpStatus.BAD_REQUEST, "invalid request");
        }
        String genre = ctx.queryParam("genre");
        String cursor = ctx.queryParam("cursor");
        return HttpResponse.from(CompletableFuture.supplyAsync(() -> {
            try {
                return writeNoStoreJson(HttpStatus.OK, responsePayload(catalog.list(genre, limit, cursor)));
            } catch (MovieCatalogService.InvalidCatalogRequestException failure) {
                return writeNoStoreError(HttpStatus.BAD_REQUEST, "invalid request");
            } catch (SQLTimeoutException failure) {
                log.warn("Movie catalog query timed out");
                return writeNoStoreError(HttpStatus.GATEWAY_TIMEOUT, "movie catalog timed out");
            } catch (SQLTransientConnectionException | SQLNonTransientConnectionException failure) {
                log.warn("Movie catalog database unavailable: {}", failure.getClass().getSimpleName());
                return writeNoStoreError(HttpStatus.SERVICE_UNAVAILABLE, "movie catalog unavailable");
            } catch (SQLException | RuntimeException failure) {
                log.error("Unexpected movie catalog failure", failure);
                return writeNoStoreError(HttpStatus.INTERNAL_SERVER_ERROR, "movie catalog request failed");
            }
        }, ctx.blockingTaskExecutor()));
    }

    private static Map<String, Object> responsePayload(CatalogPage page) {
        List<Map<String, Object>> items = page.items().stream().map(MovieCatalogApiService::moviePayload).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        payload.put("nextCursor", page.nextCursor());
        payload.put("hasMore", page.hasMore());
        return payload;
    }

    private static Map<String, Object> moviePayload(CatalogMovie movie) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", movie.id());
        payload.put("title", movie.title());
        payload.put("year", movie.year());
        payload.put("genre", movie.genre());
        payload.put("popularityScore", movie.popularityScore());
        payload.put("updatedAt", movie.updatedAt().toString());
        return payload;
    }
}
