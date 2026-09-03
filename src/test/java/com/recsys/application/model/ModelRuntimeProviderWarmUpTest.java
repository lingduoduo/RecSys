package com.recsys.application.model;

import com.recsys.application.experiment.VariantRuntimeResolver;
import com.recsys.config.ABTestConfig;
import com.recsys.config.ModelServingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link ModelRuntimeProvider#warmUp()}'s two contracts: control loads first and
 * synchronously, and its failure is fatal; every other configured variant loads on the
 * provider's own named executor, and a failure there degrades to control instead of taking
 * the pod down.
 *
 * <p>Without an explicit executor the treatment work would land on whatever
 * {@code CompletableFuture} picks by default, and that choice is not stable: {@code ASYNC_POOL}
 * is {@code ForkJoinPool.commonPool()} only while {@code getCommonPoolParallelism() > 1}, and
 * otherwise a thread-per-task executor — measured, not assumed.
 *
 * <p>Overrides {@code getRuntime} so no ONNX session is built: these tests are about dispatch
 * and failure isolation, and keeping them free of model loading is what lets them sit in the
 * merge gate.
 */
class ModelRuntimeProviderWarmUpTest {

    private static final class ScriptedProvider extends ModelRuntimeProvider {
        final Map<String, AtomicInteger> builds = new ConcurrentHashMap<>();
        final Map<String, String> buildThreads = new ConcurrentHashMap<>();
        private final Set<String> failing;

        ScriptedProvider(ABTestConfig abTestConfig, SimpleMeterRegistry registry, Set<String> failing) {
            super(new ModelArtifactLocator("", ""), abTestConfig, "dssm_model.onnx", "classpath", "i2vEmb",
                    new ModelServingProperties(), registry);
            this.failing = failing;
        }

        @Override
        public ModelRuntime getRuntime(String variant) {
            builds.computeIfAbsent(variant, k -> new AtomicInteger()).incrementAndGet();
            buildThreads.put(variant, Thread.currentThread().getName());
            if (failing.contains(variant)) {
                throw new IllegalStateException("artifacts missing for " + variant);
            }
            return null;   // warmUp discards the result; no ONNX build wanted here
        }
    }

    private static ABTestConfig abTestEnabledWithTwoBuckets() {
        ABTestConfig config = new ABTestConfig();
        config.setEnabled(true);   // buckets: test + training, default training
        return config;
    }

    @Test
    void warmUpLoadsControlSynchronouslyAndTreatmentsOnItsOwnNamedExecutor() {
        ScriptedProvider provider = new ScriptedProvider(
                abTestEnabledWithTwoBuckets(), new SimpleMeterRegistry(), Set.of());
        try {
            provider.warmUp();

            assertThat(provider.buildThreads.get("training"))
                    .as("control must load on the caller's thread, before any treatment")
                    .isEqualTo(Thread.currentThread().getName());
            assertThat(provider.buildThreads.get("test"))
                    .as("treatments must run on the provider's own executor, not whatever "
                            + "CompletableFuture defaults to — the default differs by core count")
                    .startsWith("model-warmup-");
        } finally {
            provider.close();
        }
    }

    @Test
    void warmUpVisitsEveryConfiguredVariantExactlyOnce() {
        ScriptedProvider provider = new ScriptedProvider(
                abTestEnabledWithTwoBuckets(), new SimpleMeterRegistry(), Set.of());
        try {
            provider.warmUp();
            assertThat(provider.builds).containsOnlyKeys("training", "test");
            assertThat(provider.builds.get("training").get()).isEqualTo(1);
            assertThat(provider.builds.get("test").get()).isEqualTo(1);
        } finally {
            provider.close();
        }
    }

    @Test
    void warmUpKeepsServingWhenTreatmentFailsButControlLoads() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ScriptedProvider provider = new ScriptedProvider(
                abTestEnabledWithTwoBuckets(), registry, Set.of("test"));
        try {
            provider.warmUp();   // must not throw

            assertThat(provider.builds.get("training").get()).isEqualTo(1);
            assertThat(provider.builds.get("test").get()).isEqualTo(1);
            assertThat(registry.get("recsys.model.runtime_load_failures")
                    .tags("variant", "test", "phase", "warmup").counter().count())
                    .isEqualTo(1.0);
        } finally {
            provider.close();
        }
    }

    @Test
    void warmUpPropagatesControlFailure() {
        ScriptedProvider provider = new ScriptedProvider(
                abTestEnabledWithTwoBuckets(), new SimpleMeterRegistry(), Set.of("training"));
        try {
            assertThatThrownBy(provider::warmUp)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("training");
            assertThat(provider.builds).as("no treatment is attempted once control has failed")
                    .containsOnlyKeys("training");
        } finally {
            provider.close();
        }
    }

    @Test
    void warmUpRecordsTreatmentCooldownSoFirstRequestDoesNotRetry() {
        // ONE registry for both, as in Spring: the provider counts the warm-up failure and the
        // resolver starts the cooldown. A separate registry per class hid a doubled counter once.
        SimpleMeterRegistry shared = new SimpleMeterRegistry();
        ScriptedProvider provider = new ScriptedProvider(abTestEnabledWithTwoBuckets(), shared, Set.of("test"));
        VariantRuntimeResolver resolver = new VariantRuntimeResolver(provider, shared);
        resolver.listenForWarmUpFailures();   // what @PostConstruct does in Spring
        try {
            provider.warmUp();

            VariantRuntimeResolver.Resolved resolved = resolver.resolve("test", "training");

            assertThat(resolved.fellBack()).isTrue();
            assertThat(resolved.servedVariant()).isEqualTo("training");
            assertThat(provider.builds.get("test").get())
                    .as("the warm-up failure starts the cooldown; the first request must not re-pay the build")
                    .isEqualTo(1);
            assertThat(shared.get("recsys.model.runtime_load_failures")
                    .tags("variant", "test", "phase", "warmup").counter().count())
                    .as("counted exactly once even though provider and resolver share the registry")
                    .isEqualTo(1.0);
        } finally {
            provider.close();
        }
    }
}
