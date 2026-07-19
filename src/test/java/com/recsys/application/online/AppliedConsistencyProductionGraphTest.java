package com.recsys.application.online;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.application.consistency.ConsistencyToken;
import com.recsys.application.consistency.ConsistencyTokenCodec;
import com.recsys.application.consistency.ConsistencyWaiter;
import com.recsys.application.retrieval.channels.Channels;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.domain.online.OnlineRecommendationRequest;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.infrastructure.store.RecentHistoryStore;
import com.recsys.infrastructure.store.TrendingStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class AppliedConsistencyProductionGraphTest {
    private static final int USER_ID = 123;
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("5ba697ac-52ad-4092-b1e7-bd850d6481d4");
    private static final ConsistencyTokenCodec CODEC = new ConsistencyTokenCodec(
            "0123456789abcdef0123456789abcdef", Clock.fixed(NOW, ZoneOffset.UTC));

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            DataManager dataManager = DataManager.getInstance();
            MultiChannelRecallService recall = new MultiChannelRecallService(
                    List.of(new Channels.UserSimilarity(dataManager)));
            RecentHistoryStore recent = new RecentHistoryStore() {
                @Override public List<Integer> getRecentMovieIds(int userId, int limit) { return List.of(); }
                @Override public List<Integer> getRecentMovieIdsPrimary(int userId, int limit) { return List.of(); }
            };
            TrendingStore trending = new TrendingStore() {
                @Override public List<String> getTopKIds(String window, int k) { return List.of(); }
                @Override public List<String> getTopKIdsPrimary(String window, int k) { return List.of(); }
            };
            OnlineRecommendationService recommendations = new OnlineRecommendationService(
                    dataManager, recall, recent, trending, new OnlineLearner());
            ConsistencyWaiter applied = new ConsistencyWaiter((eventId, userId, remaining) -> true);
            sb.service("/recommend", new OnlineServices.Prediction(recommendations, CODEC, applied));
        }
    };

    @Test
    void appliedReadSucceedsThroughRealRecallGraphWithImmutableChannel() {
        assertThat(get("last_hour").status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void appliedReadWithInvalidWindowRemainsBadRequest() {
        assertThat(get("invalid").status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static AggregatedHttpResponse get(String window) {
        String token = CODEC.encode(new ConsistencyToken(
                EVENT_ID, USER_ID, NOW, NOW.plus(Duration.ofHours(24))));
        return server.blockingWebClient().prepare()
                .get("/recommend?userId=" + USER_ID + "&window=" + window)
                .header(ConsistencyTokenCodec.HEADER_NAME, token)
                .execute();
    }
}
