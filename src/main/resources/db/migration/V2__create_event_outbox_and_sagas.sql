CREATE TABLE event_outbox (
    event_id BINARY(16) NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    destination ENUM('KAFKA_ONLINE', 'SQS_SAGA') NOT NULL,
    partition_key VARCHAR(255) NULL,
    payload JSON NOT NULL,
    status ENUM('PENDING', 'IN_FLIGHT', 'DELIVERED', 'DEAD') NOT NULL
        DEFAULT 'PENDING',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_owner VARCHAR(255) NULL,
    lease_expires_at TIMESTAMP(6) NULL,
    broker_acknowledged_at TIMESTAMP(6) NULL,
    last_error TEXT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6)
);

CREATE INDEX idx_outbox_claim
    ON event_outbox(status, next_attempt_at, created_at);
CREATE INDEX idx_outbox_lease
    ON event_outbox(status, lease_expires_at);
CREATE INDEX idx_outbox_reconcile
    ON event_outbox(destination, status, created_at, broker_acknowledged_at);
CREATE INDEX idx_outbox_aggregate
    ON event_outbox(aggregate_type, aggregate_id, created_at);

CREATE TABLE saga_instance (
    saga_id VARCHAR(255) NOT NULL PRIMARY KEY,
    saga_type VARCHAR(150) NOT NULL,
    correlation_id VARCHAR(255) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(50) NOT NULL,
    current_step VARCHAR(255) NULL,
    failure_reason TEXT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6)
);

CREATE INDEX idx_saga_correlation
    ON saga_instance(correlation_id, created_at);
