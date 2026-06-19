package com.recsys.model.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VariantRuntimeResolverTest {

    private final ModelRuntime testRuntime = mock(ModelRuntime.class);
    private final ModelRuntime controlRuntime = mock(ModelRuntime.class);

    private VariantRuntimeResolver resolver(ModelRuntimeProvider provider, AtomicLong clock) {
        return new VariantRuntimeResolver(provider, new SimpleMeterRegistry(), 60_000L, clock::get);
    }

    @Test
    void healthyVariant_servesItself() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("test")).thenReturn(testRuntime);
        VariantRuntimeResolver r = resolver(provider, new AtomicLong(0));

        VariantRuntimeResolver.Resolved resolved = r.resolve("test", "training");

        assertThat(resolved.runtime()).isSameAs(testRuntime);
        assertThat(resolved.servedVariant()).isEqualTo("test");
        assertThat(resolved.fellBack()).isFalse();
    }

    @Test
    void assignedEqualsDefault_noFallback() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("training")).thenReturn(controlRuntime);
        VariantRuntimeResolver r = resolver(provider, new AtomicLong(0));

        VariantRuntimeResolver.Resolved resolved = r.resolve("training", "training");

        assertThat(resolved.servedVariant()).isEqualTo("training");
        assertThat(resolved.fellBack()).isFalse();
    }

    @Test
    void brokenVariant_servesControl_andCooldownSkipsRebuild() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("test")).thenThrow(new IllegalStateException("artifacts missing"));
        when(provider.getRuntime("training")).thenReturn(controlRuntime);
        AtomicLong clock = new AtomicLong(0);
        VariantRuntimeResolver r = resolver(provider, clock);

        VariantRuntimeResolver.Resolved first = r.resolve("test", "training");
        assertThat(first.runtime()).isSameAs(controlRuntime);
        assertThat(first.servedVariant()).isEqualTo("training");
        assertThat(first.fellBack()).isTrue();

        // Within cooldown: must NOT attempt to rebuild "test" again.
        clock.set(30_000L);
        VariantRuntimeResolver.Resolved second = r.resolve("test", "training");
        assertThat(second.fellBack()).isTrue();
        verify(provider, times(1)).getRuntime("test");   // only the first attempt
    }

    @Test
    void afterCooldown_retriesAssignedVariant() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("test"))
                .thenThrow(new IllegalStateException("missing"))
                .thenReturn(testRuntime);                 // fixed on the retry
        when(provider.getRuntime("training")).thenReturn(controlRuntime);
        AtomicLong clock = new AtomicLong(0);
        VariantRuntimeResolver r = resolver(provider, clock);

        assertThat(r.resolve("test", "training").fellBack()).isTrue();
        clock.set(60_001L);                                // cooldown expired
        VariantRuntimeResolver.Resolved retry = r.resolve("test", "training");
        assertThat(retry.servedVariant()).isEqualTo("test");
        assertThat(retry.fellBack()).isFalse();
        verify(provider, times(2)).getRuntime("test");
    }

    @Test
    void brokenControl_propagates() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("test")).thenThrow(new IllegalStateException("missing"));
        when(provider.getRuntime("training")).thenThrow(new IllegalStateException("control missing too"));
        VariantRuntimeResolver r = resolver(provider, new AtomicLong(0));

        assertThatThrownBy(() -> r.resolve("test", "training"))
                .isInstanceOf(IllegalStateException.class);
    }
}
