package com.recsys.infrastructure.persistence;

/** Identifies a Hikari pool initialization/acquisition failure at the JDBC boundary. */
public final class MySqlPoolUnavailableException extends RuntimeException {
    public MySqlPoolUnavailableException(RuntimeException cause) {
        super("MySQL connection pool unavailable", cause);
    }
}
