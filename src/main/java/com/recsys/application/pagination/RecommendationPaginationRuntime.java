package com.recsys.application.pagination;

import com.recsys.config.EnvVars;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;

/** Shared pagination construction for every recommendation serving runtime. */
public record RecommendationPaginationRuntime(
        RecommendationPaginationCoordinator coordinator,
        int maxCandidates
) {
    public static RecommendationPaginationRuntime fromEnvironment(
            MeterRegistry registry,
            Clock clock
    ) {
        return fromEnvironment(System::getenv, registry, clock);
    }

    public static RecommendationPaginationRuntime fromEnvironment(
            EnvVars.EnvReader env,
            MeterRegistry registry,
            Clock clock
    ) {
        RecommendationPaginationConfig config =
                RecommendationPaginationConfig.fromEnvironment(env);
        RecommendationPaginationMetrics metrics =
                new RecommendationPaginationMetrics(registry);
        RecommendationCursorCodec codec =
                new RecommendationCursorCodec(config, clock);
        RecommendationPaginationCoordinator coordinator =
                new RecommendationPaginationCoordinator(
                        codec, new CursorPaginationService(), metrics);
        return new RecommendationPaginationRuntime(
                coordinator, config.maxCandidates());
    }
}
