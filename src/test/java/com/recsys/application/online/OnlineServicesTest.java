package com.recsys.application.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.application.consistency.ConsistencyTokenCodec;
import com.recsys.application.outbox.DurableEventPublisher;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.domain.user.User;
import com.recsys.infrastructure.outbox.OutboxConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

class OnlineServicesTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-07-18T12:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("82fb7ddf-9e77-4cd9-91e4-8a34f13cc738");
    private static final DurableEventPublisher publisher = mock(DurableEventPublisher.class);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override protected void configure(ServerBuilder sb) {
            OnlineRecommendationService recommendations = mock(OnlineRecommendationService.class);
            when(recommendations.recommend(any())).thenReturn(new OnlineRecommendationResult(
                    new User(42, "Alice"), "last_hour", "trending", List.of(), List.of(), List.of()));
            var codec = new ConsistencyTokenCodec("0123456789abcdef0123456789abcdef",
                    Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC));
            sb.service("/features", new OnlineServices.Features(recommendations, publisher, codec));
        }
    };

    @BeforeEach void clearPublisherStubs() { reset(publisher); }

    @Test void durableCommitPrecedesSuccessAndReturnsSubjectBoundToken() {
        when(publisher.publishOnline(any(), any(Integer.class), any(), any()))
                .thenReturn(new DurableEventPublisher.Acceptance(EVENT_ID, ACCEPTED_AT));

        AggregatedHttpResponse response = server.blockingWebClient()
                .get("/features?userId=42&eventId=" + EVENT_ID);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(ConsistencyTokenCodec.HEADER_NAME)).isNotBlank();
        AggregatedHttpResponse duplicate = server.blockingWebClient()
                .get("/features?userId=42&eventId=" + EVENT_ID);
        assertThat(duplicate.headers().get(ConsistencyTokenCodec.HEADER_NAME))
                .isEqualTo(response.headers().get(ConsistencyTokenCodec.HEADER_NAME));
    }

    @Test void repositoryFailureReturns503WithoutToken() {
        when(publisher.publishOnline(any(), any(Integer.class), any(), any()))
                .thenThrow(new DurableEventPublisher.PersistenceException(new RuntimeException("db down")));

        AggregatedHttpResponse response = server.blockingWebClient()
                .get("/features?userId=42&eventId=" + EVENT_ID);

        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.headers().get(ConsistencyTokenCodec.HEADER_NAME)).isNull();
    }

    @Test void conflictingEventContentReturns409WithoutToken() {
        when(publisher.publishOnline(any(), any(Integer.class), any(), any()))
                .thenThrow(new OutboxConflictException("conflict"));

        AggregatedHttpResponse response = server.blockingWebClient()
                .get("/features?userId=42&eventId=" + EVENT_ID);

        assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.headers().get(ConsistencyTokenCodec.HEADER_NAME)).isNull();
    }
}
