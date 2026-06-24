package com.recsys.infrastructure.redis.sharding;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.util.Pool;

import java.util.List;

/**
 * Authoritative shard-topology snapshot in Redis (key {@code shard:topology}).
 * One small JSON document; bootstrap is SETNX (first writer wins), reshard is an atomic
 * Lua read-modify-write that bumps the version and records the previous generation +
 * its dual-read expiry.
 */
public final class ShardTopologyStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Reshard: read JSON at KEYS[1], bump version, set prev pointer, write back, return new JSON. */
    private static final String PUBLISH_LUA = """
            local cur = redis.call('GET', KEYS[1])
            if not cur then return redis.error_reply('topology-absent') end
            local t = cjson.decode(cur)
            local newShard = tonumber(ARGV[1])
            local nowMs = tonumber(ARGV[2])
            local windowMs = tonumber(ARGV[3])
            local next = {
              version = t.version + 1,
              shardCount = newShard,
              vnodes = t.vnodes,
              createdAtMs = nowMs,
              prevVersion = t.version,
              prevShardCount = t.shardCount,
              prevExpiresAtMs = nowMs + windowMs
            }
            local encoded = cjson.encode(next)
            redis.call('SET', KEYS[1], encoded)
            return encoded
            """;

    private final Pool<Jedis> pool;
    private final String key;

    public ShardTopologyStore(Pool<Jedis> pool, String key) {
        this.pool = pool;
        this.key = key;
    }

    public Snapshot load() {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(key);
            return json == null ? null : parse(json);
        }
    }

    /** First-writer-wins create of version 1; returns the effective snapshot (existing or new). */
    public Snapshot bootstrap(int shardCount, int vnodes, long nowMs) {
        Snapshot v1 = new Snapshot(1, shardCount, vnodes, nowMs, null, null, null);
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key, write(v1), SetParams.setParams().nx());
        }
        return load();
    }

    /** Atomically bump to version+1 with the new shard count and a prev pointer + expiry. */
    public Snapshot publishReshard(int newShardCount, long nowMs, long dualReadWindowMs) {
        Object raw;
        try (Jedis jedis = pool.getResource()) {
            raw = jedis.eval(PUBLISH_LUA, List.of(key),
                    List.of(Integer.toString(newShardCount),
                            Long.toString(nowMs),
                            Long.toString(dualReadWindowMs)));
        }
        return parse((String) raw);
    }

    private static Snapshot parse(String json) {
        try {
            return MAPPER.readValue(json, Snapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt shard topology JSON: " + json, e);
        }
    }

    private static String write(Snapshot s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize shard topology", e);
        }
    }

    public record Snapshot(
            int version,
            int shardCount,
            int vnodes,
            long createdAtMs,
            Integer prevVersion,
            Integer prevShardCount,
            Long prevExpiresAtMs
    ) {}
}
