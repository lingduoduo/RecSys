package com.recsys.infrastructure.redis.sharding;

/** Key-prefix scheme per topology generation. v1 reuses the original unversioned keys. */
public final class Generations {
    private Generations() {}

    /** "" for version <= 1 (legacy/original keyspace); "g{version}:" for version >= 2. */
    public static String keyPrefix(int version) {
        return version <= 1 ? "" : "g" + version + ":";
    }
}
