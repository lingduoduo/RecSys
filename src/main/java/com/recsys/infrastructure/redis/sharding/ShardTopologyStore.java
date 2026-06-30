package com.recsys.infrastructure.redis.sharding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;

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

    private final RedisExecutor exec;
    private final String key;

    public ShardTopologyStore(RedisExecutor exec, String key) {
        this.exec = exec;
        this.key = key;
    }

    public Snapshot load() {
        String json = exec.execute(c -> c.get(key));
        return json == null ? null : parse(json);
    }

    /** First-writer-wins create of version 1; returns the effective snapshot (existing or new). */
    public Snapshot bootstrap(int shardCount, int vnodes, long nowMs) {
        Snapshot v1 = new Snapshot(1, shardCount, vnodes, nowMs, null, null, null);
        exec.execute(c -> c.set(key, write(v1), SetArgs.Builder.nx()));
        return load();
    }

    /** Atomically bump to version+1 with the new shard count and a prev pointer + expiry. */
    public Snapshot publishReshard(int newShardCount, long nowMs, long dualReadWindowMs) {
        String raw = exec.execute(c -> c.eval(PUBLISH_LUA, ScriptOutputType.VALUE,
                new String[]{key},
                Integer.toString(newShardCount),
                Long.toString(nowMs),
                Long.toString(dualReadWindowMs)));
        return parse(raw);
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
