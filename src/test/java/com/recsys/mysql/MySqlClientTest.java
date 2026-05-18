package com.recsys.mysql;

import com.recsys.pagination.MillionScalePaginationSql;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlClientTest {

    @Test
    void disabledClient_doesNotOpenConnections() {
        MySqlClient client = new MySqlClient(MySqlConnectionSettings.disabled());

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.healthCheck()).isEqualTo(new MySqlClient.HealthCheck(false, false, "disabled"));
        assertThatThrownBy(client::openConnection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL is disabled");
    }

    @Test
    void query_bindsPlanValuesAndMapsRows() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT id, name FROM movies WHERE user_id = ? LIMIT ?"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getInt("id")).thenReturn(1, 2);
        when(resultSet.getString("name")).thenReturn("Toy Story", "Heat");

        MySqlClient client = new MySqlClient(MySqlConnectionSettings.disabled());
        var plan = new MillionScalePaginationSql.SqlPlan(
                "SELECT id, name FROM movies WHERE user_id = ? LIMIT ?",
                List.of(7, 2)
        );

        List<String> rows = client.query(connection, plan,
                rs -> rs.getInt("id") + ":" + rs.getString("name"));

        assertThat(rows).containsExactly("1:Toy Story", "2:Heat");
        var inOrder = inOrder(statement, resultSet);
        inOrder.verify(statement).setObject(1, 7);
        inOrder.verify(statement).setObject(2, 2);
        inOrder.verify(statement).executeQuery();
        verify(resultSet).close();
        verify(statement).close();
    }
}
