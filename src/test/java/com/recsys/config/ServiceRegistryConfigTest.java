package com.recsys.config;

import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.infrastructure.registry.ServiceRegistrarLifecycle;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceRegistryConfigTest {

    @Test
    void buildsLifecycleBeanThatIsSafeNoOpWhenRegistryDisabled() {
        // SERVICE_REGISTRY_ENABLED is unset in the test env -> ServiceRegistrar.fromEnvironment is null
        // -> the lifecycle is a safe no-op (start/close do not touch Redis and do not throw).
        RedisExecutor redis = Mockito.mock(RedisExecutor.class);
        ServiceRegistrarLifecycle lifecycle = new ServiceRegistryConfig().serviceRegistrarLifecycle(redis);

        assertThat(lifecycle).isNotNull();
        lifecycle.start();
        lifecycle.close();
        Mockito.verifyNoInteractions(redis); // disabled -> no registration writes
    }
}
