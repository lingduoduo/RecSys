package com.recsys.application.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.application.consistency.ConsistencyTokenCodec;
import com.recsys.application.consistency.ConsistencyToken;
import com.recsys.application.consistency.ConsistencyWaiter;
import com.recsys.application.outbox.DurableEventPublisher;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.domain.user.User;
import com.recsys.infrastructure.outbox.OutboxConflictException;
import java.time.Clock;
import java.time.Duration;
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
    private static final OnlineRecommendationService recommendations = mock(OnlineRecommendationService.class);
    private static final ConsistencyWaiter waiter = mock(ConsistencyWaiter.class);
    private static final ConsistencyTokenCodec codec = new ConsistencyTokenCodec(
            "0123456789abcdef0123456789abcdef", Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC));

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override protected void configure(ServerBuilder sb) {
            when(recommendations.recommend(any())).thenReturn(new OnlineRecommendationResult(
                    new User(42, "Alice"), "last_hour", "trending", List.of(), List.of(), List.of()));
            when(recommendations.recommendPrimary(any())).thenReturn(new OnlineRecommendationResult(
                    new User(42, "Alice"), "last_hour", "trending", List.of(), List.of(), List.of()));
            sb.service("/features", new OnlineServices.Features(recommendations, publisher, codec));
            sb.service("/recommend", new OnlineServices.Prediction(recommendations, codec, waiter));
        }
    };

    @BeforeEach void clearPublisherStubs() {
        reset(publisher, recommendations, waiter);
        when(recommendations.recommend(any())).thenReturn(result());
        when(recommendations.recommendPrimary(any())).thenReturn(result());
    }

    @Test void invalidConsistencyTokenIsRejectedBeforeRecommendation() {
        AggregatedHttpResponse response = server.blockingWebClient().prepare()
                .get("/recommend?userId=42").header(ConsistencyTokenCodec.HEADER_NAME, "broken").execute();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(recommendations, waiter);
    }

    @Test void expiredConsistencyTokenReturnsConflictBeforeRecommendation() {
        String token = codec.encode(new ConsistencyToken(EVENT_ID, 42,
                ACCEPTED_AT.minus(Duration.ofHours(25)), ACCEPTED_AT.minus(Duration.ofHours(1))));
        AggregatedHttpResponse response = getRecommendation(42, token);
        assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(recommendations, waiter);
    }

    @Test void subjectMismatchReturnsForbiddenBeforeWaitingOrRecommendation() {
        AggregatedHttpResponse response = getRecommendation(7, validToken());
        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(recommendations, waiter);
    }

    @Test void pendingConsistencyReturnsAcceptedWithRetryAfter() {
        when(waiter.await(EVENT_ID, 42, Duration.ofSeconds(2)))
                .thenReturn(ConsistencyWaiter.WaitResult.PENDING);
        AggregatedHttpResponse response = getRecommendation(42, validToken());
        assertThat(response.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.headers().get("Retry-After")).isEqualTo("1");
        verify(recommendations, never()).recommend(any());
        verify(recommendations, never()).recommendPrimary(any());
    }

    @Test void failedPrimaryTokenReadReturnsRetryableServiceUnavailable() {
        when(waiter.await(EVENT_ID, 42, Duration.ofSeconds(2)))
                .thenThrow(new IllegalStateException("redis down"));
        AggregatedHttpResponse response = getRecommendation(42, validToken());
        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.headers().get("Retry-After")).isEqualTo("1");
        verifyNoInteractions(recommendations);
    }

    @Test void appliedConsistencyForcesPrimaryNoCacheRecommendation() {
        when(waiter.await(EVENT_ID, 42, Duration.ofSeconds(2)))
                .thenReturn(ConsistencyWaiter.WaitResult.APPLIED);
        AggregatedHttpResponse response = getRecommendation(42, validToken());
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        verify(recommendations).recommendPrimary(any());
        verify(recommendations, never()).recommend(any());
    }

    @Test void failedPrimaryRecommendationReturnsRetryableServiceUnavailable() {
        when(waiter.await(EVENT_ID, 42, Duration.ofSeconds(2)))
                .thenReturn(ConsistencyWaiter.WaitResult.APPLIED);
        when(recommendations.recommendPrimary(any())).thenThrow(
                new OnlineRecommendationService.PrimaryReadUnavailableException(
                        "primary read failed", new IllegalStateException("redis down")));
        AggregatedHttpResponse response = getRecommendation(42, validToken());
        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.headers().get("Retry-After")).isEqualTo("1");
        verify(recommendations, never()).recommend(any());
    }

    private static AggregatedHttpResponse getRecommendation(int userId, String token) {
        return server.blockingWebClient().prepare().get("/recommend?userId=" + userId)
                .header(ConsistencyTokenCodec.HEADER_NAME, token).execute();
    }

    private static String validToken() {
        return codec.encode(new ConsistencyToken(EVENT_ID, 42, ACCEPTED_AT, ACCEPTED_AT.plus(Duration.ofHours(24))));
    }

    private static OnlineRecommendationResult result() {
        return new OnlineRecommendationResult(new User(42, "Alice"), "last_hour", "trending",
                List.of(), List.of(), List.of());
    }

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

    @Test void tokenlessFeatureReadDoesNotCreateDurableEventOrReturnToken() {
        AggregatedHttpResponse response = server.blockingWebClient()
                .get("/features?userId=42");

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(ConsistencyTokenCodec.HEADER_NAME)).isNull();
        verifyNoInteractions(publisher);
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
