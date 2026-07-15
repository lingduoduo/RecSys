package com.recsys.api.serving;

import com.recsys.infrastructure.persistence.CatalogDatabaseBootstrap;
import com.recsys.infrastructure.persistence.MySqlClient;
import com.recsys.infrastructure.persistence.MySqlConnectionSettings;
import com.linecorp.armeria.server.ServerBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RecSysServerCatalogWiringTest {
    @Test void disabledStartupBuildsUnavailableRouteWithoutMigrationOrClientCreation() {
        CatalogDatabaseBootstrap bootstrap = mock(CatalogDatabaseBootstrap.class);
        Function<MySqlConnectionSettings, MySqlClient> clients = mock(Function.class);
        CatalogComponent component = CatalogComponent.create(MySqlConnectionSettings.disabled(), bootstrap, clients);
        assertThat(component.service()).isNotNull();
        ServerBuilder builder = mock(ServerBuilder.class);
        RecSysServer.registerCatalogRoute(builder, component);
        verify(builder).service("/v1/catalog/movies", component.service());
        verify(bootstrap, never()).migrate(org.mockito.ArgumentMatchers.any());
        verify(clients, never()).apply(org.mockito.ArgumentMatchers.any());
    }

    @Test void enabledStartupMigratesBeforeConstructingClient() {
        MySqlConnectionSettings settings = enabledSettings();
        CatalogDatabaseBootstrap bootstrap = mock(CatalogDatabaseBootstrap.class);
        Function<MySqlConnectionSettings, MySqlClient> clients = mock(Function.class);
        MySqlClient client = mock(MySqlClient.class);
        org.mockito.Mockito.when(clients.apply(settings)).thenReturn(client);
        CatalogComponent.create(settings, bootstrap, clients);
        InOrder order = inOrder(bootstrap, clients);
        order.verify(bootstrap).migrate(settings);
        order.verify(clients).apply(settings);
    }

    @Test void shutdownClosesOwnedClient() {
        MySqlConnectionSettings settings = enabledSettings();
        MySqlClient client = mock(MySqlClient.class);
        CatalogComponent component = CatalogComponent.create(settings, mock(CatalogDatabaseBootstrap.class), ignored -> client);
        component.close();
        verify(client).close();
    }

    private static MySqlConnectionSettings enabledSettings() {
        return new MySqlConnectionSettings(true, "jdbc:mysql://localhost/recsys", "u", "p", 2, 1, 0,
                "01234567890123456789012345678901");
    }
}
