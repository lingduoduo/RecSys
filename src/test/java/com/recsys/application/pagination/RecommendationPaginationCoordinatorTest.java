package com.recsys.application.pagination;

import com.recsys.application.pagination.RecommendationPaginationCoordinator.DecodedRequest;
import com.recsys.application.pagination.RecommendationPaginationCoordinator.RecommendationPage;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.AbstractList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationPaginationCoordinatorTest {
    private static final String ACTIVE_KEY = "a".repeat(32);
    private static final String PREVIOUS_KEY = "b".repeat(32);
    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RecommendationPaginationMetrics metrics =
            new RecommendationPaginationMetrics(registry);
    private final RecommendationPaginationConfig config =
            new RecommendationPaginationConfig(
                    ACTIVE_KEY, PREVIOUS_KEY, Duration.ofMinutes(15), true, 500);
    private final RecommendationCursorCodec codec =
            new RecommendationCursorCodec(config, Clock.fixed(NOW, ZoneOffset.UTC));
    private final RecommendationPaginationCoordinator coordinator =
            new RecommendationPaginationCoordinator(
                    codec, new CursorPaginationService(), metrics);

    @Test
    void resultRequiresHasMoreAndCursorToAgree() {
        assertThatThrownBy(() -> new RecommendationResult(
                "u1", List.of(), null, true, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hasMore and nextCursor");
    }

    @Test
    void resultNormalizesCursorBeforeCheckingInvariant() {
        RecommendationResult terminal =
                new RecommendationResult("u1", null, " ", false, null);
        RecommendationResult continuing =
                new RecommendationResult("u1", null, " next ", true, null);

        assertThat(terminal.items()).isEmpty();
        assertThat(terminal.nextCursor()).isNull();
        assertThat(terminal.trace()).isEmpty();
        assertThat(continuing.nextCursor()).isEqualTo("next");
    }

    @Test
    void upgradesLegacyCursorAndReturnsExactTerminalPage() {
        String legacy = legacyCursor(0.8, "b");
        RecommendationQuery query =
                new RecommendationQuery("u1", 2, Set.of(), legacy);

        RecommendationPage page = coordinator.page(query,
                List.of(m("a", 0.9), m("b", 0.8), m("c", 0.7), m("d", 0.6)),
                false);

        assertThat(page.items())
                .extracting(RankedMovie::itemId)
                .containsExactly("c", "d");
        assertThat(page.legacyCursor()).isTrue();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.budgetExhausted()).isFalse();
        assertThat(registry.get("recsys.pagination.cursor.legacy.accepted")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void rejectsTamperedCursorBeforeTouchingRankedItems() {
        RecommendationQuery unsignedQuery =
                new RecommendationQuery("u1", 2, Set.of(), null);
        String signed = codec.encode(unsignedQuery, new RankedListCursor(0.8, "b"));
        int signatureStart = signed.indexOf('.') + 1;
        char replacement = signed.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = signed.substring(0, signatureStart) + replacement
                + signed.substring(signatureStart + 1);
        RecommendationQuery query =
                new RecommendationQuery("u1", 2, Set.of(), tampered);

        assertThatThrownBy(() -> coordinator.page(query, unreadableList(), false))
                .isInstanceOf(RecommendationCursorCodec.InvalidCursorException.class)
                .hasMessage("Invalid recommendation cursor");
        assertThat(registry.get("recsys.pagination.cursor.rejected")
                .tag("reason", "signature").counter().count()).isEqualTo(1.0);
    }

    @Test
    void splitDecodeAndPageRoundTripsSignedCursor() {
        List<RankedMovie> ranked =
                List.of(m("a", 0.9), m("b", 0.8), m("c", 0.7), m("d", 0.6));
        RecommendationQuery firstQuery =
                new RecommendationQuery("u1", 2, Set.of(), null);

        DecodedRequest decoded = coordinator.decode(firstQuery);
        RecommendationPage first = coordinator.page(decoded, ranked, false);
        RecommendationPage second = coordinator.page(
                new RecommendationQuery("u1", 2, Set.of(), first.nextCursor()),
                ranked,
                false);

        assertThat(first.items()).extracting(RankedMovie::itemId)
                .containsExactly("a", "b");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.legacyCursor()).isFalse();
        assertThat(second.items()).extracting(RankedMovie::itemId)
                .containsExactly("c", "d");
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
        assertThat(registry.get("recsys.pagination.page.returned")
                .tag("terminal", "false").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("recsys.pagination.page.returned")
                .tag("terminal", "true").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsPreviousKeyVerification() {
        RecommendationQuery unsignedQuery =
                new RecommendationQuery("u1", 2, Set.of(), null);
        RecommendationCursorCodec previousWriter = new RecommendationCursorCodec(
                new RecommendationPaginationConfig(
                        PREVIOUS_KEY, null, Duration.ofMinutes(15), true, 500),
                Clock.fixed(NOW, ZoneOffset.UTC));
        String previousToken =
                previousWriter.encode(unsignedQuery, new RankedListCursor(0.8, "b"));

        coordinator.page(new RecommendationQuery("u1", 2, Set.of(), previousToken),
                List.of(m("a", 0.9), m("b", 0.8), m("c", 0.7)),
                false);

        assertThat(registry.get("recsys.pagination.cursor.previous_key.verified")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsBudgetExhaustionOnlyForTruncatedTerminalPage() {
        RecommendationQuery query =
                new RecommendationQuery("u1", 2, Set.of(), null);

        RecommendationPage exhausted = coordinator.page(
                query, List.of(m("a", 0.9), m("b", 0.8)), true);
        RecommendationPage notExhausted = coordinator.page(
                query, List.of(m("a", 0.9), m("b", 0.8), m("c", 0.7)), true);

        assertThat(exhausted.budgetExhausted()).isTrue();
        assertThat(exhausted.hasMore()).isFalse();
        assertThat(notExhausted.budgetExhausted()).isFalse();
        assertThat(notExhausted.hasMore()).isTrue();
        assertThat(registry.get("recsys.pagination.budget.exhausted")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void doesNotClassifyRankedListFailuresAsCursorRejections() {
        RecommendationQuery query =
                new RecommendationQuery("u1", 2, Set.of(), null);

        assertThatThrownBy(() -> coordinator.page(
                query, List.of(m("b", 0.8), m("a", 0.9)), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly ordered");
        assertThat(registry.getMeters().stream()
                .filter(meter -> meter.getId().getName()
                        .equals("recsys.pagination.cursor.rejected"))
                .mapToDouble(meter -> meter.measure().iterator().next().getValue())
                .sum()).isZero();
    }

    @Test
    void recommendationPageRequiresHasMoreAndCursorToAgree() {
        assertThatThrownBy(() -> new RecommendationPage(
                List.of(), null, true, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hasMore and nextCursor");
    }

    private static RankedMovie m(String itemId, double score) {
        return new RankedMovie(itemId, score, 1, Map.of());
    }

    private static String legacyCursor(double score, String itemId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v2:" + score + ":" + itemId).getBytes(StandardCharsets.UTF_8));
    }

    private static List<RankedMovie> unreadableList() {
        return new AbstractList<>() {
            @Override
            public RankedMovie get(int index) {
                throw new AssertionError("ranked items were accessed before cursor validation");
            }

            @Override
            public int size() {
                throw new AssertionError("ranked items were accessed before cursor validation");
            }

            @Override
            public Iterator<RankedMovie> iterator() {
                throw new AssertionError("ranked items were accessed before cursor validation");
            }
        };
    }
}
