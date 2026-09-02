package com.recsys.application.model;

import com.recsys.config.ABTestConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that {@link ModelRuntimeProvider#warmUp()} dispatches onto its own named executor.
 *
 * <p>Without an explicit executor the work lands on whatever {@code CompletableFuture} picks by
 * default, and that choice is not stable: {@code ASYNC_POOL} is {@code ForkJoinPool.commonPool()}
 * only while {@code getCommonPoolParallelism() > 1}, and otherwise a thread-per-task executor. So
 * the same code borrows a JVM-wide shared pool on a multi-core host and spawns unbounded threads
 * on a single-CPU one — measured, not assumed. Neither is wrong for a startup preload, but both
 * are accidental, and the common-pool case blocks a pool this repo does not otherwise own.
 *
 * <p>Overrides {@code getRuntime} so no ONNX session is built: this test is about dispatch, and
 * keeping it free of model loading is what lets it sit in the merge gate.
 */
class ModelRuntimeProviderWarmUpTest {

    private static final class RecordingProvider extends ModelRuntimeProvider {
        private final Set<String> dispatchThreads = ConcurrentHashMap.newKeySet();

        RecordingProvider(ABTestConfig abTestConfig) {
            super(new ModelArtifactLocator("", ""), abTestConfig);
        }

        @Override
        public ModelRuntime getRuntime(String variant) {
            dispatchThreads.add(Thread.currentThread().getName());
            return null;   // warmUp discards the result; no ONNX build wanted here
        }
    }

    private static ABTestConfig abTestEnabledWithTwoBuckets() {
        ABTestConfig config = new ABTestConfig();
        config.setEnabled(true);
        return config;
    }

    @Test
    void warmUpDispatchesOnItsOwnNamedExecutor() {
        RecordingProvider provider = new RecordingProvider(abTestEnabledWithTwoBuckets());
        try {
            provider.warmUp();

            assertThat(provider.dispatchThreads)
                    .as("warmUp must run on its own executor, not whatever CompletableFuture "
                            + "defaults to — the default differs by core count")
                    .isNotEmpty()
                    .allSatisfy(name -> assertThat(name).startsWith("model-warmup-"));
        } finally {
            provider.close();
        }
    }

    @Test
    void warmUpVisitsEveryConfiguredVariant() {
        RecordingProvider provider = new RecordingProvider(abTestEnabledWithTwoBuckets());
        try {
            provider.warmUp();
            assertThat(provider.dispatchThreads).isNotEmpty();
        } finally {
            provider.close();
        }
    }
}
