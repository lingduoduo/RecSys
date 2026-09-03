package com.recsys.application.experiment;
import com.recsys.application.experiment.VariantRuntimeResolver;
import com.recsys.application.model.ModelRuntimeProvider;
import com.recsys.application.model.ModelRuntime;

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
    void concurrentCallersDoNotStampedeTheBrokenBuild() throws Exception {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        java.util.concurrent.CountDownLatch inBuild = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(provider.getRuntime("test")).thenAnswer(inv -> {
            inBuild.countDown();
            release.await();                       // hold the single in-flight attempt open
            throw new IllegalStateException("missing");
        });
        when(provider.getRuntime("training")).thenReturn(controlRuntime);
        VariantRuntimeResolver r = resolver(provider, new AtomicLong(0));

        Thread t1 = new Thread(() -> r.resolve("test", "training"));
        t1.start();
        assertThat(inBuild.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();  // t1 is mid-build
        VariantRuntimeResolver.Resolved t2 = r.resolve("test", "training");            // concurrent caller
        assertThat(t2.servedVariant()).isEqualTo("training");
        assertThat(t2.fellBack()).isTrue();
        release.countDown();
        t1.join(2_000);
        verify(provider, times(1)).getRuntime("test");   // only t1 attempted the broken build
    }

    @Test
    void constructionHasNoSideEffectOnTheProvider_registrationIsExplicit() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        VariantRuntimeResolver r = resolver(provider, new AtomicLong(0));
        verify(provider, never()).setLoadFailureListener(org.mockito.ArgumentMatchers.any());

        r.listenForWarmUpFailures();   // Spring calls this via @PostConstruct

        verify(provider, times(1)).setLoadFailureListener(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void registeredListenerStartsTheCooldownWithoutCounting() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("training")).thenReturn(controlRuntime);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VariantRuntimeResolver r = new VariantRuntimeResolver(provider, registry, 60_000L, () -> 0L);
        r.listenForWarmUpFailures();
        org.mockito.ArgumentCaptor<com.recsys.application.model.VariantLoadFailureListener> listener =
                org.mockito.ArgumentCaptor.forClass(com.recsys.application.model.VariantLoadFailureListener.class);
        verify(provider).setLoadFailureListener(listener.capture());

        listener.getValue().onLoadFailure("test", new IllegalStateException("bad"), "warmup");

        assertThat(r.resolve("test", "training").fellBack()).isTrue();
        verify(provider, never()).getRuntime("test");
        assertThat(registry.find("recsys.model.runtime_load_failures").counter())
                .as("the provider owns the warmup count; the listener must not add a second one").isNull();
    }

    @Test
    void recordedWarmupFailureStartsCooldownWithoutABuildAttempt() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("training")).thenReturn(controlRuntime);
        AtomicLong clock = new AtomicLong(0);
        VariantRuntimeResolver r = resolver(provider, clock);

        r.recordLoadFailure("test", new IllegalStateException("bad model"), "warmup");

        VariantRuntimeResolver.Resolved resolved = r.resolve("test", "training");
        assertThat(resolved.fellBack()).isTrue();
        assertThat(resolved.servedVariant()).isEqualTo("training");
        verify(provider, never()).getRuntime("test");

        // After the cooldown the assigned variant is retried, so a redeployed artifact recovers.
        when(provider.getRuntime("test")).thenReturn(testRuntime);
        clock.set(60_001L);
        assertThat(r.resolve("test", "training").servedVariant()).isEqualTo("test");
    }

    @Test
    void requestPathLoadFailureIsCountedWithItsPhase() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("test")).thenThrow(new IllegalStateException("missing"));
        when(provider.getRuntime("training")).thenReturn(controlRuntime);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VariantRuntimeResolver r = new VariantRuntimeResolver(provider, registry, 60_000L, () -> 0L);

        r.resolve("test", "training");

        assertThat(registry.get("recsys.model.runtime_load_failures")
                .tags("variant", "test", "phase", "request").counter().count()).isEqualTo(1.0);
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
