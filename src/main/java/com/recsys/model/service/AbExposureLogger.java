package com.recsys.model.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.config.ABTestConfig;
import com.recsys.online.event.AsyncEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Emits one A/B exposure event per served recommendation request to the async event pipeline,
 * so the offline pipeline can join exposures to outcomes for lift analysis. Non-blocking and
 * best-effort: a full/failing publisher never breaks the request. No-op when A/B is disabled.
 */
@Service
public class AbExposureLogger {

    // Canonical Kafka topic for A/B exposure events. The injected AsyncEventPublisher.publish(String)
    // is transport-agnostic (see ModelEventConfig); a deployment-specific transport subclass routes
    // events to this topic. Kept as the single source of truth for that topic name.
    static final String TOPIC = "ab_exposures";
    private static final Logger log = LoggerFactory.getLogger(AbExposureLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AsyncEventPublisher publisher;
    private final ABTestConfig config;
    private final Supplier<String> idGenerator;
    private final LongSupplier clock;

    @Autowired
    public AbExposureLogger(@Qualifier("abExposurePublisher") AsyncEventPublisher publisher, ABTestConfig config) {
        this(publisher, config, () -> UUID.randomUUID().toString(), System::currentTimeMillis);
    }

    AbExposureLogger(AsyncEventPublisher publisher, ABTestConfig config,
                     Supplier<String> idGenerator, LongSupplier clock) {
        this.publisher = publisher;
        this.config = config;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public void log(String userId, ABTestService.Assignment assignment,
                    String servedVariant, boolean fellBack, String modelVersion) {
        if (!config.isEnabled()) {
            return;
        }
        ExposureEvent event = new ExposureEvent(
                userId,
                assignment.variant(),
                servedVariant,
                fellBack ? assignment.variant() : null,
                assignment.layerName(),
                assignment.slot(),
                assignment.inExperiment(),
                modelVersion,
                idGenerator.get(),
                clock.getAsLong());
        try {
            publisher.publish(MAPPER.writeValueAsString(event));
        } catch (Exception e) {   // serialization OR a misbehaving publisher — never break the request
            log.warn("failed to publish A/B exposure event for user {}", userId, e);
        }
    }

    public record ExposureEvent(String userId, String assignedVariant, String servedVariant,
                                String fellBackFrom, String layer, int slot, boolean inExperiment,
                                String modelVersion, String eventId, long timestampMs) {}
}
