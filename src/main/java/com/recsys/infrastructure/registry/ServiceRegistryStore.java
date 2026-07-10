package com.recsys.infrastructure.registry;

import com.recsys.infrastructure.redis.RedisExecutor;

import io.lettuce.core.KeyValue;
import io.lettuce.core.SetArgs;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative service-registry entries in Redis. One key per service —
 * {@code <prefix><serviceName>} → the advertised address string — written with a TTL that the
 * producer renews via heartbeat, so a service whose replicas all stop renewing expires automatically.
 */
public final class ServiceRegistryStore {

    public static final String DEFAULT_KEY_PREFIX = "svc:registry:";

    private final RedisExecutor exec;
    private final String keyPrefix;

    public ServiceRegistryStore(RedisExecutor exec, String keyPrefix) {
        this.exec = exec;
        this.keyPrefix = keyPrefix;
    }

    public void register(String serviceName, String address, long ttlMs) {
        String key = keyPrefix + serviceName;
        exec.execute(c -> c.set(key, address, SetArgs.Builder.px(ttlMs)));
    }

    public void deregister(String serviceName) {
        String key = keyPrefix + serviceName;
        exec.execute(c -> c.del(key));
    }

    public Map<String, String> lookup(Collection<String> serviceNames) {
        if (serviceNames.isEmpty()) {
            return Map.of();
        }
        String[] keys = serviceNames.stream().map(n -> keyPrefix + n).toArray(String[]::new);
        List<KeyValue<String, String>> values = exec.executeRead(c -> c.mget(keys));
        Map<String, String> result = new LinkedHashMap<>();
        for (KeyValue<String, String> kv : values) {
            if (kv.hasValue()) {
                result.put(stripPrefix(kv.getKey()), kv.getValue());
            }
        }
        return result;
    }

    private String stripPrefix(String key) {
        return key.startsWith(keyPrefix) ? key.substring(keyPrefix.length()) : key;
    }
}
