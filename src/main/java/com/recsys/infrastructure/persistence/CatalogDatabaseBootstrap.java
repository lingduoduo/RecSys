package com.recsys.infrastructure.persistence;

import org.flywaydb.core.Flyway;

import java.util.Objects;

/** Applies the catalog schema when MySQL-backed catalog access is enabled. */
public final class CatalogDatabaseBootstrap {
    private final MigrationRunnerFactory runnerFactory;

    public CatalogDatabaseBootstrap() {
        this(CatalogDatabaseBootstrap::createFlywayRunner);
    }

    CatalogDatabaseBootstrap(MigrationRunnerFactory runnerFactory) {
        this.runnerFactory = Objects.requireNonNull(runnerFactory, "runnerFactory");
    }

    public void migrate(MySqlConnectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (!settings.enabled()) {
            return;
        }
        runnerFactory.create(settings).migrate();
    }

    private static MigrationRunner createFlywayRunner(MySqlConnectionSettings settings) {
        Flyway flyway = Flyway.configure()
                .dataSource(settings.url(), settings.username(), settings.password())
                .locations("classpath:db/migration")
                .load();
        return flyway::migrate;
    }

    @FunctionalInterface
    interface MigrationRunnerFactory {
        MigrationRunner create(MySqlConnectionSettings settings);
    }

    @FunctionalInterface
    interface MigrationRunner {
        void migrate();
    }
}
