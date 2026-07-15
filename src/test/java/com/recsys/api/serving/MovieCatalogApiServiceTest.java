package com.recsys.api.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.application.catalog.MovieCatalogService;
import com.recsys.domain.catalog.CatalogMovie;
import com.recsys.domain.catalog.CatalogPage;
import com.recsys.infrastructure.persistence.MySqlPoolUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

class MovieCatalogApiServiceTest {
    private static final MovieCatalogService catalog = mock(MovieCatalogService.class);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override protected void configure(ServerBuilder sb) {
            sb.service("/catalog", new MovieCatalogApiService(catalog, true));
            sb.service("/disabled", new MovieCatalogApiService(null, false));
        }
    };

    @BeforeEach void resetCatalog() { reset(catalog); }

    @Test void returnsPageAsNoStoreJson() throws Exception {
        when(catalog.list("Drama", 2, null)).thenReturn(new CatalogPage(List.of(
                new CatalogMovie(7, "Seven", 2026, "Drama", new BigDecimal("9.5"), Instant.EPOCH)),
                "next", true));
        AggregatedHttpResponse response = get("/catalog?genre=Drama&limit=2");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.contentUtf8()).contains("\"id\":7", "\"nextCursor\":\"next\"", "\"hasMore\":true");
    }

    @Test void rejectsOutOfRangeAndNonNumericLimits() {
        for (String limit : List.of("0", "101", "wat")) {
            assertThat(get("/catalog?limit=" + limit).status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test void mapsInvalidCursorToBadRequestWithoutLeakingDetails() throws Exception {
        when(catalog.list(null, null, "bad")).thenThrow(
                new MovieCatalogService.InvalidCatalogRequestException("signed secret detail"));
        AggregatedHttpResponse response = get("/catalog?cursor=bad");
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).doesNotContain("signed secret detail");
    }

    @Test void disabledCatalogReturnsServiceUnavailable() {
        assertThat(get("/disabled").status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void mapsConnectionFailureToServiceUnavailable() throws Exception {
        assertFailure(new SQLNonTransientConnectionException("jdbc:mysql://secret"), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void mapsTimeoutToGatewayTimeout() throws Exception {
        assertFailure(new SQLTimeoutException("slow sql"), HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test void mapsWrappedTimeoutToGatewayTimeout() throws Exception {
        assertFailure(new SQLException("outer", new SQLTimeoutException("slow sql")), HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test void mapsGenericConnectionSqlStateToServiceUnavailable() throws Exception {
        assertFailure(new SQLException("connection lost", "08006"), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void mapsTypedPoolFailureToServiceUnavailable() throws Exception {
        assertRuntimeFailure(new MySqlPoolUnavailableException(new IllegalStateException("pool init")),
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void mapsUnexpectedRuntimeToInternalServerError() throws Exception {
        assertRuntimeFailure(new IllegalStateException("bug"), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test void mapsUnexpectedSqlFailureToInternalServerError() throws Exception {
        assertFailure(new SQLException("SELECT password FROM secret"), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static void assertFailure(SQLException failure, HttpStatus expected) throws Exception {
        when(catalog.list(any(), any(), any())).thenThrow(failure);
        AggregatedHttpResponse response = get("/catalog");
        assertThat(response.status()).isEqualTo(expected);
        assertThat(response.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.contentUtf8()).doesNotContain(failure.getMessage());
    }

    private static void assertRuntimeFailure(RuntimeException failure, HttpStatus expected) throws Exception {
        when(catalog.list(any(), any(), any())).thenThrow(failure);
        AggregatedHttpResponse response = get("/catalog");
        assertThat(response.status()).isEqualTo(expected);
        assertThat(response.contentUtf8()).doesNotContain(failure.getMessage());
    }

    private static AggregatedHttpResponse get(String path) {
        return server.blockingWebClient().get(path);
    }
}
