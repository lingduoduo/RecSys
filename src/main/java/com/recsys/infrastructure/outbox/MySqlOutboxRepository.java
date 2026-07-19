package com.recsys.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.domain.outbox.OutboxDestination;
import com.recsys.domain.outbox.OutboxEvent;
import com.recsys.domain.outbox.OutboxStatus;
import com.recsys.application.outbox.OutboxRepository;
import com.recsys.infrastructure.persistence.TransactionalMySql;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MySqlOutboxRepository implements OutboxRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ERROR_LENGTH = 2_000;
    private static final String COLUMNS = "event_id, aggregate_type, aggregate_id, event_type, destination, "
            + "partition_key, payload, status, attempt_count, next_attempt_at, lease_owner, lease_expires_at, "
            + "broker_acknowledged_at, last_error, version, created_at";
    private final TransactionalMySql mysql;

    public MySqlOutboxRepository(TransactionalMySql mysql) {
        this.mysql = Objects.requireNonNull(mysql, "mysql");
    }

    public OutboxEvent enqueue(OutboxEvent event) {
        return mysql.inTransaction(connection -> enqueue(connection, event));
    }

    public OutboxEvent enqueue(Connection connection, OutboxEvent event) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(event, "event");
        String sql = "INSERT IGNORE INTO event_outbox (event_id, aggregate_type, aggregate_id, event_type, "
                + "destination, partition_key, payload, status, attempt_count, next_attempt_at, version, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, bytes(event.eventId()));
            statement.setString(2, event.aggregateType());
            statement.setString(3, event.aggregateId());
            statement.setString(4, event.eventType());
            statement.setString(5, event.destination().name());
            statement.setString(6, event.partitionKey());
            statement.setString(7, event.payload());
            statement.setString(8, event.status().name());
            statement.setInt(9, event.attemptCount());
            statement.setTimestamp(10, timestamp(event.nextAttemptAt()));
            statement.setLong(11, event.version());
            statement.setTimestamp(12, timestamp(event.createdAt()));
            statement.executeUpdate();
        }
        OutboxEvent stored = find(connection, event.eventId()).orElseThrow();
        if (!sameImmutableContent(stored, event)) {
            throw new OutboxConflictException("event ID already exists with different content: " + event.eventId());
        }
        return stored;
    }

    public Optional<OutboxEvent> find(UUID eventId) {
        return mysql.inTransaction(connection -> find(connection, eventId));
    }

    public List<OutboxEvent> claimBatch(String worker, Instant now, int limit, Duration leaseDuration) {
        requireText(worker, "worker");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return mysql.inTransaction(connection -> {
            List<OutboxEvent> candidates = new ArrayList<>();
            String select = "SELECT " + COLUMNS + " FROM event_outbox WHERE next_attempt_at <= ? AND "
                    + "(status = 'PENDING' OR (status = 'IN_FLIGHT' AND lease_expires_at < ?)) "
                    + "ORDER BY next_attempt_at, created_at LIMIT ? FOR UPDATE SKIP LOCKED";
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                statement.setTimestamp(1, timestamp(now));
                statement.setTimestamp(2, timestamp(now));
                statement.setInt(3, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) candidates.add(map(rows));
                }
            }
            List<OutboxEvent> claimed = new ArrayList<>(candidates.size());
            String update = "UPDATE event_outbox SET status = 'IN_FLIGHT', attempt_count = attempt_count + 1, "
                    + "lease_owner = ?, lease_expires_at = ?, version = version + 1 WHERE event_id = ? AND version = ? "
                    + "AND (status = 'PENDING' OR (status = 'IN_FLIGHT' AND lease_expires_at < ?))";
            for (OutboxEvent candidate : candidates) {
                try (PreparedStatement statement = connection.prepareStatement(update)) {
                    statement.setString(1, worker);
                    statement.setTimestamp(2, timestamp(now.plus(leaseDuration)));
                    statement.setBytes(3, bytes(candidate.eventId()));
                    statement.setLong(4, candidate.version());
                    statement.setTimestamp(5, timestamp(now));
                    if (statement.executeUpdate() == 1) {
                        claimed.add(new OutboxEvent(candidate.eventId(), candidate.aggregateType(), candidate.aggregateId(),
                                candidate.eventType(), candidate.destination(), candidate.partitionKey(), candidate.payload(),
                                OutboxStatus.IN_FLIGHT, candidate.attemptCount() + 1, candidate.nextAttemptAt(), worker,
                                now.plus(leaseDuration), candidate.brokerAcknowledgedAt(), candidate.lastError(),
                                candidate.version() + 1, candidate.createdAt()));
                    }
                }
            }
            return List.copyOf(claimed);
        });
    }

    @Override public long countRetryableBacklog(Instant now) {
        Objects.requireNonNull(now, "now");
        return mysql.inTransaction(connection -> {
            String sql = "SELECT COUNT(*) FROM event_outbox WHERE status = 'PENDING' "
                    + "OR (status = 'IN_FLIGHT' AND lease_expires_at < ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, timestamp(now));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SQLException("outbox backlog count returned no row");
                    return rows.getLong(1);
                }
            }
        });
    }

    @Override public boolean claimReconciliationLease(UUID eventId, String worker, Instant now, Duration leaseDuration) {
        Objects.requireNonNull(eventId, "eventId");
        requireText(worker, "worker");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return mysql.inTransaction(connection -> {
            String sql = "UPDATE event_outbox SET lease_owner = ?, lease_expires_at = ? "
                    + "WHERE event_id = ? AND status = 'DELIVERED' "
                    + "AND (lease_owner IS NULL OR lease_expires_at < ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, worker);
                statement.setTimestamp(2, timestamp(now.plus(leaseDuration)));
                statement.setBytes(3, bytes(eventId));
                statement.setTimestamp(4, timestamp(now));
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override public boolean releaseReconciliationLease(UUID eventId, String worker) {
        Objects.requireNonNull(eventId, "eventId");
        requireText(worker, "worker");
        return mysql.inTransaction(connection -> {
            String sql = "UPDATE event_outbox SET lease_owner = NULL, lease_expires_at = NULL "
                    + "WHERE event_id = ? AND status = 'DELIVERED' AND lease_owner = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, bytes(eventId));
                statement.setString(2, worker);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public boolean markDelivered(UUID eventId, long version, String leaseOwner, Instant acknowledgedAt) {
        requireText(leaseOwner, "leaseOwner");
        Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
        return terminalUpdate("UPDATE event_outbox SET status = 'DELIVERED', broker_acknowledged_at = ?, "
                + "lease_owner = NULL, lease_expires_at = NULL, last_error = NULL, version = version + 1 "
                + "WHERE event_id = ? AND version = ? AND status = 'IN_FLIGHT' AND lease_owner = ?",
                eventId, version, leaseOwner, acknowledgedAt, null, false);
    }

    public boolean reschedule(UUID eventId, long version, String leaseOwner, Instant nextAttemptAt, String error) {
        requireText(leaseOwner, "leaseOwner");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        return terminalUpdate("UPDATE event_outbox SET status = 'PENDING', next_attempt_at = ?, last_error = ?, "
                + "lease_owner = NULL, lease_expires_at = NULL, version = version + 1 "
                + "WHERE event_id = ? AND version = ? AND status = 'IN_FLIGHT' AND lease_owner = ?",
                eventId, version, leaseOwner, nextAttemptAt, truncate(error), true);
    }

    public boolean markDead(UUID eventId, long version, String leaseOwner, String error) {
        requireText(leaseOwner, "leaseOwner");
        return mysql.inTransaction(connection -> {
            String sql = "UPDATE event_outbox SET status = 'DEAD', last_error = ?, lease_owner = NULL, "
                    + "lease_expires_at = NULL, version = version + 1 WHERE event_id = ? AND version = ? "
                    + "AND status = 'IN_FLIGHT' AND lease_owner = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, truncate(error));
                statement.setBytes(2, bytes(Objects.requireNonNull(eventId, "eventId")));
                statement.setLong(3, version);
                statement.setString(4, leaseOwner);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public List<OutboxEvent> scanDelivered(Instant from, Instant to, int limit) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return mysql.inTransaction(connection -> {
            String sql = "SELECT " + COLUMNS + " FROM event_outbox WHERE status = 'DELIVERED' "
                    + "AND broker_acknowledged_at >= ? AND broker_acknowledged_at < ? "
                    + "ORDER BY broker_acknowledged_at, event_id LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, timestamp(from));
                statement.setTimestamp(2, timestamp(to));
                statement.setInt(3, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<OutboxEvent> events = new ArrayList<>();
                    while (rows.next()) events.add(map(rows));
                    return List.copyOf(events);
                }
            }
        });
    }

    private boolean terminalUpdate(
            String sql, UUID eventId, long version, String leaseOwner, Instant instant, String error, boolean bindError) {
        return mysql.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, timestamp(instant));
                int offset = 1;
                if (bindError) statement.setString(++offset, error);
                statement.setBytes(++offset, bytes(Objects.requireNonNull(eventId, "eventId")));
                statement.setLong(++offset, version);
                statement.setString(++offset, leaseOwner);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private static Optional<OutboxEvent> find(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM event_outbox WHERE event_id = ?")) {
            statement.setBytes(1, bytes(Objects.requireNonNull(eventId, "eventId")));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        }
    }

    private static OutboxEvent map(ResultSet row) throws SQLException {
        return new OutboxEvent(uuid(row.getBytes("event_id")), row.getString("aggregate_type"),
                row.getString("aggregate_id"), row.getString("event_type"),
                OutboxDestination.valueOf(row.getString("destination")), row.getString("partition_key"),
                row.getString("payload"), OutboxStatus.valueOf(row.getString("status")),
                row.getInt("attempt_count"), instant(row, "next_attempt_at"), row.getString("lease_owner"),
                instant(row, "lease_expires_at"), instant(row, "broker_acknowledged_at"),
                row.getString("last_error"), row.getLong("version"), instant(row, "created_at"));
    }

    private static boolean sameImmutableContent(OutboxEvent left, OutboxEvent right) {
        return left.eventId().equals(right.eventId()) && left.aggregateType().equals(right.aggregateType())
                && left.aggregateId().equals(right.aggregateId()) && left.eventType().equals(right.eventType())
                && left.destination() == right.destination() && Objects.equals(left.partitionKey(), right.partitionKey())
                && semanticallyEqualJson(left.payload(), right.payload());
    }

    private static boolean semanticallyEqualJson(String left, String right) {
        try {
            return JSON.readTree(left).equals(JSON.readTree(right));
        } catch (JsonProcessingException invalidJson) {
            return left.equals(right);
        }
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    // Truncate to microseconds so persisted values match MySQL DATETIME(6) deterministically,
    // rather than relying on the driver's round-half-up of sub-microsecond nanos.
    private static Timestamp timestamp(Instant instant) { return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS)); }
    private static Instant instant(ResultSet row, String name) throws SQLException {
        Timestamp value = row.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
