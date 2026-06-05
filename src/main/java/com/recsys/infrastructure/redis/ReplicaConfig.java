package com.recsys.infrastructure.redis;

/**
 * Endpoint descriptor for one Redis read replica, optionally tagged with its
 * Availability Zone so the router can prefer same-AZ connections.
 *
 * Accepted spec formats (as used in {@code REDIS_REPLICA_NODES}):
 * <ul>
 *   <li>{@code host:port@az}  — e.g. {@code redis-b.internal:6379@us-east-1b}</li>
 *   <li>{@code host:port}     — AZ defaults to {@code "unknown"}</li>
 *   <li>{@code host}          — port defaults to 6379, AZ to {@code "unknown"}</li>
 * </ul>
 */
public record ReplicaConfig(String host, int port, String az) {

    public static ReplicaConfig parse(String spec) {
        if (spec == null || spec.isBlank()) throw new IllegalArgumentException("empty replica spec");
        String[] atParts = spec.strip().split("@", 2);
        String az = atParts.length == 2 ? atParts[1].strip() : "unknown";
        String[] hostPort = atParts[0].strip().split(":", 2);
        String host = hostPort[0].strip();
        int port = hostPort.length == 2 ? Integer.parseInt(hostPort[1].strip()) : 6379;
        if (host.isEmpty()) throw new IllegalArgumentException("empty host in replica spec: " + spec);
        return new ReplicaConfig(host, port, az);
    }
}
