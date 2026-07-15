package com.recsys.application.catalog;

import com.recsys.domain.catalog.CatalogMovie;
import com.recsys.domain.catalog.CatalogPage;
import com.recsys.infrastructure.persistence.MovieCatalogRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public class MovieCatalogService {
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final MovieCatalogRepository repository;
    private final CatalogCursorCodec cursorCodec;

    public MovieCatalogService(MovieCatalogRepository repository, CatalogCursorCodec cursorCodec) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
    }

    public CatalogPage list(String genre, Integer requestedLimit, String cursor) throws SQLException {
        String normalizedGenre = normalizeGenre(genre);
        if (normalizedGenre != null && normalizedGenre.codePointCount(0, normalizedGenre.length()) > 64) {
            throw new InvalidCatalogRequestException("genre must contain at most 64 Unicode code points");
        }
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidCatalogRequestException("limit must be between 1 and 100");
        }

        CatalogCursorCodec.Position position = decode(cursor, normalizedGenre);
        if (position != null && !Objects.equals(normalizedGenre, position.genre())) {
            throw new InvalidCatalogRequestException("cursor does not match genre filter");
        }

        List<CatalogMovie> fetched = repository.fetch(normalizedGenre, position, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<CatalogMovie> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = null;
        if (hasMore) {
            CatalogMovie last = items.get(items.size() - 1);
            nextCursor = cursorCodec.encode(new CatalogCursorCodec.Position(
                    normalizedGenre, last.popularityScore(), last.id()));
        }
        return new CatalogPage(items, nextCursor, hasMore);
    }

    private CatalogCursorCodec.Position decode(String cursor, String expectedGenre) {
        if (cursor == null) {
            return null;
        }
        if (cursor.isBlank()) {
            throw new InvalidCatalogRequestException("cursor must not be blank");
        }
        try {
            return cursorCodec.decode(cursor, expectedGenre);
        } catch (CatalogCursorCodec.InvalidCursorException failure) {
            throw new InvalidCatalogRequestException("cursor is invalid", failure);
        }
    }

    private static String normalizeGenre(String genre) {
        if (genre == null) {
            return null;
        }
        String normalized = genre.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static final class InvalidCatalogRequestException extends IllegalArgumentException {
        public InvalidCatalogRequestException(String message) {
            super(message);
        }

        public InvalidCatalogRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
