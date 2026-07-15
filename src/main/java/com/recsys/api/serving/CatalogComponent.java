package com.recsys.api.serving;

import com.recsys.application.catalog.CatalogCursorCodec;
import com.recsys.application.catalog.MovieCatalogService;
import com.recsys.infrastructure.persistence.CatalogDatabaseBootstrap;
import com.recsys.infrastructure.persistence.MovieCatalogRepository;
import com.recsys.infrastructure.persistence.MySqlClient;
import com.recsys.infrastructure.persistence.MySqlConnectionSettings;

import java.util.Objects;
import java.util.function.Function;

/** Owns creation and shutdown of the optional catalog stack. */
final class CatalogComponent implements AutoCloseable {
    private final MovieCatalogApiService service;
    private final MySqlClient client;

    private CatalogComponent(MovieCatalogApiService service, MySqlClient client) {
        this.service = service;
        this.client = client;
    }

    static CatalogComponent fromEnvironment() {
        return create(MySqlConnectionSettings.fromEnv(), new CatalogDatabaseBootstrap(), MySqlClient::new);
    }

    static CatalogComponent create(MySqlConnectionSettings settings,
                                   CatalogDatabaseBootstrap bootstrap,
                                   Function<MySqlConnectionSettings, MySqlClient> clientFactory) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(bootstrap, "bootstrap");
        Objects.requireNonNull(clientFactory, "clientFactory");
        if (!settings.enabled()) {
            return new CatalogComponent(new MovieCatalogApiService(null, false), null);
        }
        bootstrap.migrate(settings);
        MySqlClient client = clientFactory.apply(settings);
        try {
            MovieCatalogService catalog = new MovieCatalogService(
                    new MovieCatalogRepository(client), new CatalogCursorCodec(settings.cursorSigningKey()));
            return new CatalogComponent(new MovieCatalogApiService(catalog, true), client);
        } catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    MovieCatalogApiService service() { return service; }

    @Override public void close() {
        if (client != null) client.close();
    }
}
