package com.recsys.infrastructure.redis.sharding;

import java.util.Objects;

/**
 * The single owner of the sharded record store's key scheme.
 *
 * <p>A key is {@code {prefix}{generation}{kind}:{shardToken}:{suffix}}. The shard token is
 * the bare shard index under {@link #FORMAT_UNTAGGED} and a Redis Cluster hash tag —
 * {@code {0}} — under {@link #FORMAT_TAGGED}. The tag co-locates every key belonging to one
 * shard in a single Cluster slot, which is what allows a multi-key script to run over them
 * atomically.
 *
 * <p>The format travels with the topology generation rather than with the deployment, so a
 * generation written under one format keeps that format for its whole life and the existing
 * dual-read window can span a format change.
 */
public final class ShardKeys {

    /** Original scheme: bare shard index, no Cluster slot guarantee. */
    public static final int FORMAT_UNTAGGED = 1;
    /** Shard index wrapped in a Redis Cluster hash tag, co-locating a shard's keys. */
    public static final int FORMAT_TAGGED = 2;

    private final String prefix;
    private final int version;
    private final int keyFormat;

    public ShardKeys(String prefix, int version, int keyFormat) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        if (keyFormat != FORMAT_UNTAGGED && keyFormat != FORMAT_TAGGED) {
            throw new IllegalArgumentException("unknown key format: " + keyFormat);
        }
        this.version = version;
        this.keyFormat = keyFormat;
    }

    public static ShardKeys of(String prefix, ShardTopology topology) {
        Objects.requireNonNull(topology, "topology");
        return new ShardKeys(prefix, topology.version(), topology.keyFormat());
    }

    public int version()   { return version; }
    public int keyFormat() { return keyFormat; }

    public String rec(int shardIndex, long seqNum)     { return recPrefix(shardIndex) + seqNum; }
    public String dev(int shardIndex, String deviceId) { return base("dev", shardIndex) + ":" + deviceId; }
    public String stream(int shardIndex)               { return base("stream", shardIndex); }
    public String seq(int shardIndex)                  { return base("seq", shardIndex); }

    /** Everything before the sequence number, so a Lua script can build the record key itself. */
    public String recPrefix(int shardIndex) { return base("rec", shardIndex) + ":"; }

    /**
     * Glob for every device key in this shard. Braces are literal in Redis glob patterns —
     * only {@code *}, {@code ?}, {@code [...]} and {@code \} are special — so a tagged
     * pattern matches tagged keys exactly.
     */
    public String devScanPattern(int shardIndex) { return base("dev", shardIndex) + ":*"; }

    private String base(String kind, int shardIndex) {
        return prefix + Generations.keyPrefix(version) + kind + ":" + shardToken(shardIndex);
    }

    private String shardToken(int shardIndex) {
        return keyFormat == FORMAT_TAGGED ? "{" + shardIndex + "}" : Integer.toString(shardIndex);
    }
}
