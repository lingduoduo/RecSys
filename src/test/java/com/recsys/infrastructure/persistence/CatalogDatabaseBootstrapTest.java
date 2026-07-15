package com.recsys.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogDatabaseBootstrapTest {

    @Test
    void disabledSettingsDoNotConstructMigrationRunner() {
        AtomicInteger factoryCalls = new AtomicInteger();
        CatalogDatabaseBootstrap bootstrap = new CatalogDatabaseBootstrap(settings -> {
            factoryCalls.incrementAndGet();
            return () -> { };
        });

        bootstrap.migrate(MySqlConnectionSettings.disabled());

        assertThat(factoryCalls).hasValue(0);
    }
}
