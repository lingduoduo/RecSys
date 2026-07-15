package com.recsys.infrastructure.persistence;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

/** Classifies JDBC failures conservatively for read-only retry handling. */
public final class MySqlExceptionClassifier {
    private MySqlExceptionClassifier() {}

    public static boolean isRetryableRead(SQLException exception) {
        if (isTimeout(exception)) {
            return false;
        }
        return any(exception, failure -> failure instanceof SQLTransientConnectionException
                || hasSqlStateClass(failure, "08"));
    }

    public static boolean isTimeout(SQLException exception) {
        return any(exception, failure -> failure instanceof SQLTimeoutException);
    }

    private static boolean hasSqlStateClass(SQLException exception, String stateClass) {
        String sqlState = exception.getSQLState();
        return sqlState != null && sqlState.startsWith(stateClass);
    }

    private static boolean any(SQLException root, Predicate<SQLException> predicate) {
        if (root == null) {
            return false;
        }
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLException sqlException) {
                if (predicate.test(sqlException)) {
                    return true;
                }
                SQLException next = sqlException.getNextException();
                if (next != null) {
                    pending.addLast(next);
                }
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                pending.addLast(cause);
            }
        }
        return false;
    }
}
