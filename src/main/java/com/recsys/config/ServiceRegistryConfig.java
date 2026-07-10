package com.recsys.config;

import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.infrastructure.registry.ServiceRegistrar;
import com.recsys.infrastructure.registry.ServiceRegistrarLifecycle;
import com.recsys.infrastructure.registry.ServiceRegistryStore;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers this Spring Boot service (the model service) into the Redis service registry when
 * {@code SERVICE_REGISTRY_ENABLED} is set and the advertise env vars are present. The lifecycle bean is
 * started after context refresh and closed on shutdown; when the registry is disabled it is an inert
 * no-op and no Redis registration occurs.
 */
@Configuration
public class ServiceRegistryConfig {

    @Bean(initMethod = "start", destroyMethod = "close")
    public ServiceRegistrarLifecycle serviceRegistrarLifecycle(RedisExecutor redisExecutor) {
        ServiceRegistryStore store =
                new ServiceRegistryStore(redisExecutor, ServiceRegistryStore.DEFAULT_KEY_PREFIX);
        return new ServiceRegistrarLifecycle(ServiceRegistrar.fromEnvironment(store));
    }
}
