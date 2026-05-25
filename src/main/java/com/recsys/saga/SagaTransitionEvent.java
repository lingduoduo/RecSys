package com.recsys.saga;

import java.time.Instant;

public record SagaTransitionEvent(String eventId,
                                  String sagaId,
                                  String sagaType,
                                  String correlationId,
                                  SagaEventType type,
                                  SagaStatus status,
                                  String stepName,
                                  String payloadJson,
                                  Instant occurredAt) {
}
