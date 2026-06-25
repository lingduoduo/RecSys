package com.recsys.application.saga;

import com.recsys.domain.saga.SagaEventType;
import com.recsys.domain.saga.SagaInstance;
import com.recsys.domain.saga.SagaStatus;
import com.recsys.domain.saga.SagaStep;
import com.recsys.domain.saga.SagaTransitionEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SagaOrchestratorSqsPublisherTest {

    @Test
    void transitionsArePersistedBeforeEventsArePublished() {
        RecordingStore store = new RecordingStore();
        List<String> order = store.order;
        SagaEventPublisher publisher = event -> order.add("publish:" + event.type());
        Clock clock = Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);
        SagaOrchestrators.Standard orchestrator = new SagaOrchestrators.Standard(store, publisher, clock);

        orchestrator.execute(
                "saga-ordered",
                "req-ordered",
                "{}",
                new SagaDefinition("ordered", List.of(SagaStep.local("reserve"))),
                Map.of("reserve", (saga, step) -> { }),
                Map.of());

        assertSaveBeforePublish(order, SagaStatus.STARTED, SagaEventType.SAGA_STARTED);
        assertSaveBeforePublish(order, SagaStatus.STEP_STARTED, SagaEventType.STEP_STARTED);
        assertSaveBeforePublish(order, SagaStatus.COMPLETED, SagaEventType.SAGA_COMPLETED);
    }

    private static void assertSaveBeforePublish(List<String> order, SagaStatus status, SagaEventType eventType) {
        assertThat(order.indexOf("save:" + status)).isGreaterThanOrEqualTo(0);
        assertThat(order.indexOf("publish:" + eventType)).isGreaterThan(order.indexOf("save:" + status));
    }

    private static final class RecordingStore implements SagaStateStore {
        private final InMemorySagaStateStore delegate = new InMemorySagaStateStore();
        private final List<String> order = new ArrayList<>();

        @Override
        public Optional<SagaInstance> find(String sagaId) {
            return delegate.find(sagaId);
        }

        @Override
        public void save(SagaInstance saga) {
            order.add("save:" + saga.status());
            delegate.save(saga);
        }

        @Override
        public void saveConditionally(SagaInstance saga) {
            order.add("save:" + saga.status());
            delegate.saveConditionally(saga);
        }
    }
}
