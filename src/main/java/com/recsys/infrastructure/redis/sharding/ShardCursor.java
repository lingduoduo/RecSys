package com.recsys.infrastructure.redis.sharding;

import java.util.Objects;

/**
 * A pagination cursor for one of two <em>mutually incompatible</em> cursor spaces.
 *
 * <ul>
 *   <li>{@link Kind#SEQ} — a device ZSet score, emitted and consumed by
 *       {@code readDevice}. Wire form: a bare integer, e.g. {@code "42"}.</li>
 *   <li>{@link Kind#STREAM} — a Redis stream message ID, emitted and consumed by
 *       {@code readShard}. Wire form: {@code "<millis>-<seq>"}, e.g.
 *       {@code "1690000000000-0"}.</li>
 *   <li>{@link Kind#START} — the shared "from the beginning" sentinel, valid in both.</li>
 * </ul>
 *
 * <p>Both HTTP handlers accept an opaque {@code cursor} query parameter, so a cursor
 * paged out of one endpoint can be handed to the other. That used to reach Redis and
 * surface as a 500 — {@code Double.parseDouble("1690000000000-0")} on the device path,
 * an invalid stream ID on the shard path. Deriving the kind at construction and asserting
 * it in each read path turns that into a 400 that names the expected cursor space.
 *
 * <p>The two spaces are distinguishable without ambiguity: a stream ID always contains
 * {@code '-'}, a sequence number never does. So the kind is inferred from the value and
 * <strong>the wire format is unchanged</strong> — cursors clients already hold keep working.
 */
public record ShardCursor(String value, Kind kind) {

    /** Which cursor space a value belongs to. */
    public enum Kind { SEQ, STREAM, START }

    private static final String START_VALUE = "0-0";
    private static final ShardCursor START = new ShardCursor(START_VALUE, Kind.START);

    public ShardCursor {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(kind, "kind");
    }

    public static ShardCursor start() { return START; }

    /** A device-read cursor from a ZSet score. */
    public static ShardCursor seq(long seqNum) {
        return new ShardCursor(Long.toString(seqNum), Kind.SEQ);
    }

    /** A shard-read cursor from a Redis stream message ID. */
    public static ShardCursor stream(String messageId) {
        return new ShardCursor(messageId, Kind.STREAM);
    }

    /**
     * Parses a client-supplied cursor, inferring its kind from shape.
     *
     * @throws IllegalArgumentException if the value belongs to neither cursor space
     */
    public static ShardCursor of(String value) {
        Objects.requireNonNull(value, "value");
        if (START_VALUE.equals(value)) return START;
        if (isStreamId(value)) return new ShardCursor(value, Kind.STREAM);
        if (isSeqNum(value)) return new ShardCursor(value, Kind.SEQ);
        throw new IllegalArgumentException(
                "malformed cursor '" + value + "' — expected a sequence number (e.g. 42) "
                        + "or a stream ID (e.g. 1690000000000-0)");
    }

    /**
     * Asserts this cursor belongs to {@code required}. {@link Kind#START} satisfies any
     * requirement, since "from the beginning" is meaningful in both spaces.
     *
     * @throws IllegalArgumentException naming the expected space, for the caller to map to 400
     */
    public void requireKind(Kind required) {
        if (kind == Kind.START || kind == required) return;
        throw new IllegalArgumentException(switch (required) {
            case SEQ -> "cursor '" + value + "' is a stream ID, but this endpoint pages by "
                    + "sequence number — did it come from GET /shards/shard?";
            case STREAM -> "cursor '" + value + "' is a sequence number, but this endpoint pages "
                    + "by stream ID — did it come from GET /shards/device?";
            case START -> "cursor '" + value + "' is not the start sentinel";
        });
    }

    public boolean isStart() { return kind == Kind.START; }

    // A stream ID is "<digits>-<digits>". Anything containing '-' is intended as one.
    private static boolean isStreamId(String v) {
        int dash = v.indexOf('-');
        if (dash <= 0 || dash == v.length() - 1) return false;
        return isDigits(v, 0, dash) && isDigits(v, dash + 1, v.length());
    }

    private static boolean isSeqNum(String v) {
        return !v.isEmpty() && isDigits(v, 0, v.length());
    }

    private static boolean isDigits(String v, int from, int to) {
        for (int i = from; i < to; i++) {
            if (v.charAt(i) < '0' || v.charAt(i) > '9') return false;
        }
        return true;
    }
}
