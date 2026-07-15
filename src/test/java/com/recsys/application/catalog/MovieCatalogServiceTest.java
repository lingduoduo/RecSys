package com.recsys.application.catalog;

import com.recsys.domain.catalog.CatalogMovie;
import com.recsys.infrastructure.persistence.MovieCatalogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MovieCatalogServiceTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Test
    void normalizesBlankGenreAndUsesDefaultLimitPlusLookahead() throws Exception {
        MovieCatalogRepository repository = mock(MovieCatalogRepository.class);
        when(repository.fetch(isNull(), isNull(), anyInt())).thenReturn(List.of());

        new MovieCatalogService(repository, new CatalogCursorCodec(KEY)).list("  ", null, null);

        verify(repository).fetch(null, null, 21);
    }

    @Test
    void trimsLookaheadAndEncodesNextCursorFromLastReturnedMovie() throws Exception {
        MovieCatalogRepository repository = mock(MovieCatalogRepository.class);
        CatalogMovie first = movie(3, "9.00");
        CatalogMovie lastReturned = movie(2, "8.00");
        CatalogMovie lookahead = movie(1, "7.00");
        when(repository.fetch("Drama", null, 3)).thenReturn(List.of(first, lastReturned, lookahead));
        CatalogCursorCodec codec = new CatalogCursorCodec(KEY);

        var page = new MovieCatalogService(repository, codec).list(" Drama ", 2, null);

        assertThat(page.items()).containsExactly(first, lastReturned);
        assertThat(page.hasMore()).isTrue();
        assertThat(codec.decode(page.nextCursor(), "Drama")).isEqualTo(
                new CatalogCursorCodec.Position("Drama", lastReturned.popularityScore(), lastReturned.id()));
    }

    @Test
    void exactFullFinalPageHasNoNextCursor() throws Exception {
        MovieCatalogRepository repository = mock(MovieCatalogRepository.class);
        when(repository.fetch(null, null, 3)).thenReturn(List.of(movie(2, "8.00"), movie(1, "7.00")));

        var page = new MovieCatalogService(repository, new CatalogCursorCodec(KEY)).list(null, 2, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void decodedCursorIsPassedAsExactDecimalPosition() throws Exception {
        MovieCatalogRepository repository = mock(MovieCatalogRepository.class);
        when(repository.fetch(anyString(), any(), anyInt())).thenReturn(List.of());
        CatalogCursorCodec codec = new CatalogCursorCodec(KEY);
        var expected = new CatalogCursorCodec.Position("Sci-Fi", new BigDecimal("98.120000"), 42L);

        new MovieCatalogService(repository, codec).list("Sci-Fi", 5, codec.encode(expected));

        ArgumentCaptor<CatalogCursorCodec.Position> position =
                ArgumentCaptor.forClass(CatalogCursorCodec.Position.class);
        verify(repository).fetch(org.mockito.ArgumentMatchers.eq("Sci-Fi"), position.capture(),
                org.mockito.ArgumentMatchers.eq(6));
        assertThat(position.getValue().popularityScore()).isEqualByComparingTo("98.120000");
        assertThat(position.getValue()).isEqualTo(expected);
    }

    @Test
    void rejectsLimitsOutsideOneToOneHundred() {
        MovieCatalogService service = new MovieCatalogService(mock(MovieCatalogRepository.class),
                new CatalogCursorCodec(KEY));

        assertThatThrownBy(() -> service.list(null, 0, null))
                .isInstanceOf(MovieCatalogService.InvalidCatalogRequestException.class);
        assertThatThrownBy(() -> service.list(null, 101, null))
                .isInstanceOf(MovieCatalogService.InvalidCatalogRequestException.class);
    }

    @Test
    void rejectsCursorWhoseNormalizedGenreDoesNotMatchRequest() {
        CatalogCursorCodec codec = new CatalogCursorCodec(KEY);
        String cursor = codec.encode(new CatalogCursorCodec.Position("Drama", BigDecimal.ONE, 1));
        MovieCatalogService service = new MovieCatalogService(mock(MovieCatalogRepository.class), codec);

        assertThatThrownBy(() -> service.list("Sci-Fi", 20, cursor))
                .isInstanceOf(MovieCatalogService.InvalidCatalogRequestException.class);
    }

    @Test
    void mapsMalformedCursorToInvalidCatalogRequest() {
        MovieCatalogService service = new MovieCatalogService(mock(MovieCatalogRepository.class),
                new CatalogCursorCodec(KEY));

        assertThatThrownBy(() -> service.list(null, 20, "not-a-cursor"))
                .isInstanceOf(MovieCatalogService.InvalidCatalogRequestException.class);
    }

    private static CatalogMovie movie(long id, String score) {
        return new CatalogMovie(id, "Movie " + id, 2026, "Drama", new BigDecimal(score), Instant.EPOCH);
    }
}
