package com.recsys.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlExceptionClassifierTest {

    @Test
    void classifiesConnectionFailuresAsRetryableReads() {
        assertThat(MySqlExceptionClassifier.isRetryableRead(new SQLException("lost", "08006"))).isTrue();
        assertThat(MySqlExceptionClassifier.isRetryableRead(
                new SQLTransientConnectionException("transient", "HY000"))).isTrue();
    }

    @Test
    void timeoutWinsOverConnectionFailureAnywhereInTheChain() {
        SQLException connectionFailure = new SQLException("lost", "08006");
        connectionFailure.setNextException(new SQLTimeoutException("deadline"));

        assertThat(MySqlExceptionClassifier.isTimeout(connectionFailure)).isTrue();
        assertThat(MySqlExceptionClassifier.isRetryableRead(connectionFailure)).isFalse();
    }

    @Test
    void authorizationAndSyntaxFailuresWinOverConnectionFailuresAnywhereInTheChain() {
        for (String nonRetryableState : new String[] {"28000", "42000"}) {
            SQLException root = new SQLException("lost", "08006");
            SQLException nonRetryable = new SQLException("invalid request", nonRetryableState);
            nonRetryable.initCause(new SQLException("nested connection failure", "08001"));
            root.setNextException(nonRetryable);

            assertThat(MySqlExceptionClassifier.isRetryableRead(root)).isFalse();
        }
    }

    @Test
    void rejectsTimeoutSyntaxAuthorizationAndMappingFailures() {
        assertThat(MySqlExceptionClassifier.isRetryableRead(new SQLTimeoutException("deadline"))).isFalse();
        assertThat(MySqlExceptionClassifier.isRetryableRead(new SQLException("syntax", "42000"))).isFalse();
        assertThat(MySqlExceptionClassifier.isRetryableRead(new SQLException("denied", "28000"))).isFalse();
        assertThat(MySqlExceptionClassifier.isRetryableRead(new SQLException("mapping"))).isFalse();
    }

    @Test
    void followsCausesAndNextExceptions() {
        SQLException caused = new SQLException("outer", new SQLException("lost", "08001"));
        SQLException linked = new SQLException("outer");
        linked.setNextException(new SQLTransientConnectionException("lost"));

        assertThat(MySqlExceptionClassifier.isRetryableRead(caused)).isTrue();
        assertThat(MySqlExceptionClassifier.isRetryableRead(linked)).isTrue();
    }
}
