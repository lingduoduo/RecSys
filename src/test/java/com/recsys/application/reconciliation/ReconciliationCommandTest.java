package com.recsys.application.reconciliation;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationCommandTest {
    @Test void defaultsToPreviousTwentyFourHours() {
        OutboxReconciler reconciler = mock(OutboxReconciler.class);
        Instant now = Instant.parse("2026-07-18T12:00:00Z");
        int defaultBatch = 500;
        when(reconciler.reconcile(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(new ReconciliationResult(0, 0, 0, 0, 0));
        ReconciliationCommand command = new ReconciliationCommand(reconciler,
                Clock.fixed(now, ZoneOffset.UTC), 24, defaultBatch, false);

        command.run(new String[0]);

        verify(reconciler).reconcile(now.minus(Duration.ofHours(24)), now, defaultBatch, false);
    }
}
