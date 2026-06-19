package com.recsys.model.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Resolves the {@link ModelRuntime} for an A/B-assigned variant, falling back to the default/control
 * variant when the assigned variant's artifacts fail to load. A failed variant is held in a cooldown
 * so the failing ONNX build is not re-paid on every request ({@code computeIfAbsent} does not cache the
 * exception). After the cooldown one retry is allowed so a redeployed artifact recovers without a restart.
 */
@Service
public class VariantRuntimeResolver {

    static final long DEFAULT_COOLDOWN_MS = 60_000L;
    private static final Logger log = LoggerFactory.getLogger(VariantRuntimeResolver.class);

    private final ModelRuntimeProvider provider;
    private final MeterRegistry registry;
    private final long cooldownMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> failedUntilMs = new ConcurrentHashMap<>();

    @Autowired
    public VariantRuntimeResolver(ModelRuntimeProvider provider, MeterRegistry registry) {
        this(provider, registry, DEFAULT_COOLDOWN_MS, System::currentTimeMillis);
    }

    VariantRuntimeResolver(ModelRuntimeProvider provider, MeterRegistry registry, long cooldownMs, LongSupplier clock) {
        this.provider = provider;
        this.registry = registry;
        this.cooldownMs = cooldownMs;
        this.clock = clock;
    }

    public Resolved resolve(String assignedVariant, String defaultVariant) {
        if (assignedVariant.equals(defaultVariant)) {
            return new Resolved(provider.getRuntime(defaultVariant), defaultVariant, false);
        }
        long now = clock.getAsLong();
        Long until = failedUntilMs.get(assignedVariant);
        if (until == null || now >= until) {
            failedUntilMs.remove(assignedVariant);
            try {
                return new Resolved(provider.getRuntime(assignedVariant), assignedVariant, false);
            } catch (RuntimeException e) {
                failedUntilMs.put(assignedVariant, now + cooldownMs);
                recordFallback(assignedVariant);
                log.warn("variant '{}' failed to load; serving control '{}'", assignedVariant, defaultVariant, e);
            }
        } else {
            recordFallback(assignedVariant);
        }
        // Control failing here propagates — a broken control is a genuine outage, not masked.
        return new Resolved(provider.getRuntime(defaultVariant), defaultVariant, true);
    }

    private void recordFallback(String variant) {
        registry.counter("recsys.abtest.variant_fallback", "variant", variant).increment();
    }

    public record Resolved(ModelRuntime runtime, String servedVariant, boolean fellBack) {}
}
