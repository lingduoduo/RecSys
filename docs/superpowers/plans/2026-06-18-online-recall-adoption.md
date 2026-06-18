# Online Serving Recall Adoption Implementation Plan (Sub-project 2 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace port 7010's bespoke `OnlineRecommendationService` recall with the shared `MultiChannelRecallService` (5-channel set incl. a new `OnlineRecentHistoryChannel`, `QuotaPolicy.defaultOnline()`), moving `OnlineLearner` to a post-recall re-rank and keeping a per-request trending snapshot.

**Architecture:** `OnlineRecommendationService.recommend()` becomes recall → re-rank → snapshot. Recall runs the shared service (cold detection via the existing `userEmbCache`); re-rank applies `OnlineLearner.scoreAdjustment` and excludes recent; the response keeps `recentMovies` + per-`window` `trendingMovies`. 7010's recommendation output changes (intended).

**Tech Stack:** Java 17, Maven, JUnit 5, Mockito, AssertJ/JUnit assertions, Armeria.

## Global Constraints

- Java 17, Maven. `mvn test -Dtest=<Class>` runs one class (Surefire sets `-Xshare:off`). This branch stacks on `feat/shared-recall-core` (sub-project 1: `QuotaPolicy`, `RecallConfig`, `MultiChannelRecallService.from`).
- Window: **hybrid** — recall channels use fixed windows `["last_hour","last_day"]`; the response's `trendingMovies` uses the per-request `window`. No change to `RecommendationQuery`.
- Channel set (registration/quota-fill order): `EmbeddingChannel`, `OnlineRecentHistoryChannel`, `TrendingChannel(fixed)`, `PopularityChannel`, `ColdStartChannel`.
- `QuotaPolicy.defaultOnline()`: warm `{embedding 0.50, online_recent_history 0.25, trending 0.15}` residual `popularity`; cold `{cold_start 0.50, trending 0.20, popularity 0.20}` residual `online_recent_history`.
- `OnlineRecentHistoryChannel` emits **rank-based** scores `1.0/(rank+1)` (matches `EmbeddingChannel`'s scale); recency-boost only orders within the channel. Name = `"online_recent_history"`.
- Re-rank: `score' = candidate.score() + onlineLearner.scoreAdjustment(movieId)`; exclude recent; sort desc, tie-break by movieId asc; top `k`. `recallLimit = max(k*4, 12)`.
- `strategy = "multichannel"`. Empty recall → fall back to the trending snapshot. `channelTimeoutMs = 200`. Window normalization preserved (`last_hour`/`last_day`/`last_month`, default `last_hour`, invalid → `IllegalArgumentException`).
- `OnlineRecommendationEngine` stays in the tree (off the path); retirement is sub-project 3.

---

### Task 1: `QuotaPolicy.defaultOnline()`

**Files:**
- Modify: `src/main/java/com/recsys/service/retrieval/coldstart/QuotaPolicy.java`
- Test: `src/test/java/com/recsys/service/retrieval/coldstart/QuotaPolicyTest.java` (extend)

**Interfaces:**
- Consumes: existing `QuotaPolicy` record + private `compute` helper + `QuotaSpec`.
- Produces: `public static QuotaPolicy defaultOnline()`.

- [ ] **Step 1: Write the failing tests**

Append to `QuotaPolicyTest.java` (inside the class):

```java
    @Test
    void defaultOnlineWarm_slotsForLimit10() {
        QuotaSpec q = QuotaPolicy.defaultOnline().warm(10);
        assertThat(q.slotsFor("embedding")).isEqualTo(5);
        assertThat(q.slotsFor("online_recent_history")).isEqualTo(2); // round(2.5)=3? -> see note
        assertThat(q.slotsFor("trending")).isEqualTo(2);             // residual-aware
        assertThat(q.slotsFor("popularity")).isEqualTo(0);           // residual gets remainder
        assertThat(q.slotsFor("cold_start")).isEqualTo(0);          // absent from warm
        int total = q.slotsFor("embedding") + q.slotsFor("online_recent_history")
                + q.slotsFor("trending") + q.slotsFor("popularity");
        assertThat(total).isEqualTo(10);
    }

    @Test
    void defaultOnlineCold_slotsForLimit10() {
        QuotaSpec q = QuotaPolicy.defaultOnline().cold(10);
        assertThat(q.slotsFor("cold_start")).isEqualTo(5);
        assertThat(q.slotsFor("trending")).isEqualTo(2);
        assertThat(q.slotsFor("popularity")).isEqualTo(2);
        assertThat(q.slotsFor("online_recent_history")).isEqualTo(1); // residual
        assertThat(q.slotsFor("embedding")).isEqualTo(0);            // absent from cold
        int total = q.slotsFor("cold_start") + q.slotsFor("trending")
                + q.slotsFor("popularity") + q.slotsFor("online_recent_history");
        assertThat(total).isEqualTo(10);
    }
```

**IMPORTANT — compute the warm slots before asserting.** Apply the helper (`slot=clamp(round(frac*limit),0,remaining)`, residual=remainder) to `limit=10`:
`embedding=round(5.0)=5` (rem 5); `online_recent_history=round(2.5)=3` (rem 2); `trending=round(1.5)=2` (rem 0); `popularity`(residual)=0.
So the correct warm expectations for limit 10 are **embedding=5, online_recent_history=3, trending=2, popularity=0**. Fix the `online_recent_history` assertion to `isEqualTo(3)` and `trending` to `isEqualTo(2)` before running (the inline "note" above is a reminder, not final). Verify cold by the same method: `cold_start=round(5)=5` (rem5); `trending=round(2)=2` (rem3); `popularity=round(2)=2` (rem1); `online_recent_history`(residual)=1 — matches the cold test as written.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=QuotaPolicyTest`
Expected: COMPILATION FAILURE — `defaultOnline()` does not exist.

- [ ] **Step 3: Add the factory**

In `QuotaPolicy.java`, add next to `defaultMovie()`:

```java
    /** Port-7010 quota: embedding + online-recent-history led when warm; cold-start led when cold. */
    public static QuotaPolicy defaultOnline() {
        Map<String, Double> warm = new LinkedHashMap<>();
        warm.put("embedding", 0.50);
        warm.put("online_recent_history", 0.25);
        warm.put("trending", 0.15);
        Map<String, Double> cold = new LinkedHashMap<>();
        cold.put("cold_start", 0.50);
        cold.put("trending", 0.20);
        cold.put("popularity", 0.20);
        return new QuotaPolicy(warm, "popularity", cold, "online_recent_history");
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=QuotaPolicyTest`
Expected: PASS (existing equivalence/validation tests + the 2 new `defaultOnline` tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/coldstart/QuotaPolicy.java \
        src/test/java/com/recsys/service/retrieval/coldstart/QuotaPolicyTest.java
git commit -m "feat: add QuotaPolicy.defaultOnline() for port 7010 channel set

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `OnlineRecentHistoryChannel`

**Files:**
- Create: `src/main/java/com/recsys/service/retrieval/channels/OnlineRecentHistoryChannel.java`
- Test: `src/test/java/com/recsys/service/retrieval/channels/OnlineRecentHistoryChannelTest.java`

**Interfaces:**
- Consumes: `RecallChannel`, `RecommendationQuery`, `MovieCandidate`, `RecentHistoryStore.getRecentMovieIds(int,int)`, `DataManager.getSimilarMovies(int)`, `Movie`.
- Produces: `public OnlineRecentHistoryChannel(RecentHistoryStore, DataManager)`; `name()="online_recent_history"`; `recall(...)` returns rank-based `MovieCandidate`s.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/service/retrieval/channels/OnlineRecentHistoryChannelTest.java`:

```java
package com.recsys.service.retrieval.channels;

import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.infrastructure.DataManager;
import com.recsys.online.store.RecentHistoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineRecentHistoryChannelTest {

    private static Movie movie(int id) { return new Movie(id, "M" + id, 2020, List.of("Drama")); }

    private static RecommendationQuery query(String userId) {
        return new RecommendationQuery(userId, 10, Set.of(), null);
    }

    @Test
    void blendsSimilarMoviesAcrossRecentSeeds_rankBasedScores() {
        RecentHistoryStore recent = mock(RecentHistoryStore.class);
        DataManager dm = mock(DataManager.class);
        when(recent.getRecentMovieIds(eq(7), eq(3))).thenReturn(List.of(10, 20));
        when(dm.getSimilarMovies(10)).thenReturn(List.of(movie(1), movie(2), movie(3)));
        when(dm.getSimilarMovies(20)).thenReturn(List.of(movie(2), movie(4)));

        OnlineRecentHistoryChannel channel = new OnlineRecentHistoryChannel(recent, dm);
        List<MovieCandidate> out = channel.recall(query("7"), 10);

        assertThat(out).isNotEmpty();
        assertThat(out).extracting(MovieCandidate::channel).containsOnly("online_recent_history");
        // movie 2 appears under both seeds -> highest blended order -> rank-0 -> score 1.0
        assertThat(out.get(0).itemId()).isEqualTo("2");
        assertThat(out.get(0).score()).isEqualTo(1.0);
        // scores are strictly rank-based and descending
        assertThat(out.get(1).score()).isEqualTo(0.5);
    }

    @Test
    void emptyWhenNoRecentHistory() {
        RecentHistoryStore recent = mock(RecentHistoryStore.class);
        DataManager dm = mock(DataManager.class);
        when(recent.getRecentMovieIds(eq(7), eq(3))).thenReturn(List.of());

        OnlineRecentHistoryChannel channel = new OnlineRecentHistoryChannel(recent, dm);
        assertThat(channel.recall(query("7"), 10)).isEmpty();
    }

    @Test
    void emptyWhenUserIdNotNumeric() {
        OnlineRecentHistoryChannel channel =
                new OnlineRecentHistoryChannel(mock(RecentHistoryStore.class), mock(DataManager.class));
        assertThat(channel.recall(query("user_7"), 10)).isEmpty();
    }

    @Test
    void respectsLimit() {
        RecentHistoryStore recent = mock(RecentHistoryStore.class);
        DataManager dm = mock(DataManager.class);
        when(recent.getRecentMovieIds(eq(7), eq(3))).thenReturn(List.of(10));
        when(dm.getSimilarMovies(10)).thenReturn(List.of(movie(1), movie(2), movie(3), movie(4)));

        OnlineRecentHistoryChannel channel = new OnlineRecentHistoryChannel(recent, dm);
        assertThat(channel.recall(query("7"), 2)).hasSize(2);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=OnlineRecentHistoryChannelTest`
Expected: COMPILATION FAILURE — `OnlineRecentHistoryChannel` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/recsys/service/retrieval/channels/OnlineRecentHistoryChannel.java`:

```java
package com.recsys.service.retrieval.channels;

import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.infrastructure.DataManager;
import com.recsys.online.store.RecentHistoryStore;
import com.recsys.service.retrieval.RecallChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Port-7010 behavioral recall: blends movies similar to the user's most recent watches, with a
 * recency boost favoring the latest seed. Emits rank-based scores ({@code 1/(rank+1)}) so its
 * scale matches the other channels for the quota merge's gap fill — the recency boost only
 * determines intra-channel order. Analog of 6010's GenreHistoryChannel.
 */
public class OnlineRecentHistoryChannel implements RecallChannel {

    private static final int RECENT_SEED_LIMIT = 3;
    private static final int SIMILAR_PER_SEED = 12;

    private final RecentHistoryStore recentHistoryStore;
    private final DataManager dataManager;

    public OnlineRecentHistoryChannel(RecentHistoryStore recentHistoryStore, DataManager dataManager) {
        this.recentHistoryStore = Objects.requireNonNull(recentHistoryStore, "recentHistoryStore");
        this.dataManager = Objects.requireNonNull(dataManager, "dataManager");
    }

    @Override
    public String name() {
        return "online_recent_history";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        int userId;
        try {
            userId = Integer.parseInt(query.userId());
        } catch (NumberFormatException e) {
            return List.of();
        }

        List<Integer> recentIds = recentHistoryStore.getRecentMovieIds(userId, RECENT_SEED_LIMIT);
        if (recentIds.isEmpty()) return List.of();

        Map<Integer, Double> blended = new LinkedHashMap<>();
        for (int i = 0; i < recentIds.size(); i++) {
            double recencyBoost = 30.0 + (i * 8.0);
            List<Movie> similar = dataManager.getSimilarMovies(recentIds.get(i));
            int cap = Math.min(similar.size(), SIMILAR_PER_SEED);
            for (int rank = 0; rank < cap; rank++) {
                Movie m = similar.get(rank);
                blended.merge(m.id(), recencyBoost - rank, Double::sum);
            }
        }

        List<Integer> ranked = blended.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .limit(limit)
                .toList();

        List<MovieCandidate> out = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            out.add(new MovieCandidate(String.valueOf(ranked.get(i)), 1.0 / (i + 1.0), name(), Map.of()));
        }
        return out;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=OnlineRecentHistoryChannelTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/channels/OnlineRecentHistoryChannel.java \
        src/test/java/com/recsys/service/retrieval/channels/OnlineRecentHistoryChannelTest.java
git commit -m "feat: add OnlineRecentHistoryChannel (rank-based recent-history recall)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Rework `OnlineRecommendationService` (recall → re-rank → snapshot)

**Files:**
- Modify (full rewrite): `src/main/java/com/recsys/online/serving/OnlineRecommendationService.java`
- Test (rework): `src/test/java/com/recsys/online/serving/OnlineRecommendationServiceTest.java`

**Interfaces:**
- Consumes: `MultiChannelRecallService.recall(RecommendationQuery,int)`, `RecentHistoryStore`, `TrendingStore.getTopKIds(String,int)`, `DataManager.getUserById/getMovieById`, `OnlineLearner.scoreAdjustment(int)`, `MovieCandidate`, `RecommendationQuery`.
- Produces: new constructor `OnlineRecommendationService(DataManager, MultiChannelRecallService, RecentHistoryStore, TrendingStore, OnlineLearner)`; `recommend(OnlineRecommendationRequest) -> OnlineRecommendationResult` with `strategy="multichannel"`. `UnknownUserException` unchanged.

- [ ] **Step 1: Rewrite the test**

Replace the entire contents of `src/test/java/com/recsys/online/serving/OnlineRecommendationServiceTest.java`:

```java
package com.recsys.online.serving;

import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.User;
import com.recsys.infrastructure.DataManager;
import com.recsys.online.event.ExperienceCollector;
import com.recsys.online.learner.OnlineLearner;
import com.recsys.online.store.RecentHistoryStore;
import com.recsys.online.store.TrendingStore;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnlineRecommendationServiceTest {

    private static final User USER = new User(123, "Alice");

    private DataManager dataManager;
    private MultiChannelRecallService recallService;
    private RecentHistoryStore recentHistoryStore;
    private TrendingStore topkStore;
    private OnlineLearner onlineLearner;
    private OnlineRecommendationService service;

    private static Movie movie(int id) { return new Movie(id, "M" + id, 2020, List.of("Drama")); }
    private static MovieCandidate cand(int id, double score) {
        return new MovieCandidate(String.valueOf(id), score, "embedding", Map.of());
    }

    @BeforeEach
    void setUp() {
        dataManager = mock(DataManager.class);
        recallService = mock(MultiChannelRecallService.class);
        recentHistoryStore = mock(RecentHistoryStore.class);
        topkStore = mock(TrendingStore.class);
        onlineLearner = new OnlineLearner();
        service = new OnlineRecommendationService(
                dataManager, recallService, recentHistoryStore, topkStore, onlineLearner);

        when(dataManager.getUserById(USER.userId())).thenReturn(USER);
        when(dataManager.getUserById(999)).thenReturn(null);
        // identity movie lookups for ids used below
        for (int id : new int[]{3, 4, 5, 6}) when(dataManager.getMovieById(id)).thenReturn(movie(id));
        when(recentHistoryStore.getRecentMovieIds(eq(USER.userId()), anyInt())).thenReturn(List.of());
        when(topkStore.getTopKIds(eq("last_hour"), anyInt())).thenReturn(List.of());
    }

    @Test
    void recommendationsComeFromRecallServiceInScoreOrder() {
        when(recallService.recall(any(RecommendationQuery.class), anyInt()))
                .thenReturn(List.of(cand(5, 0.9), cand(4, 0.7), cand(3, 0.5)));

        OnlineRecommendationResult result =
                service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 3));

        assertEquals("multichannel", result.strategy());
        assertEquals(List.of(5, 4, 3),
                result.recommendations().stream().map(Movie::id).toList());
    }

    @Test
    void recallLimitHasHeadroomForSmallK() {
        when(recallService.recall(any(RecommendationQuery.class), anyInt())).thenReturn(List.of());
        service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 1));
        // recallLimit = max(k*4, 12) = 12
        verify(recallService).recall(any(RecommendationQuery.class), eq(12));
    }

    @Test
    void excludesRecentlyWatched() {
        when(recentHistoryStore.getRecentMovieIds(eq(USER.userId()), anyInt())).thenReturn(List.of(3));
        when(recallService.recall(any(RecommendationQuery.class), anyInt()))
                .thenReturn(List.of(cand(3, 0.9), cand(4, 0.5)));

        OnlineRecommendationResult result =
                service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 5));

        assertFalse(result.recommendations().stream().anyMatch(m -> m.id() == 3),
                "recently-watched movie must be excluded");
    }

    @Test
    void onlineLearnerReweightsRanking() {
        onlineLearner = new OnlineLearner(2.0, 0.0, 2.0);
        service = new OnlineRecommendationService(
                dataManager, recallService, recentHistoryStore, topkStore, onlineLearner);
        when(recallService.recall(any(RecommendationQuery.class), anyInt()))
                .thenReturn(List.of(cand(4, 0.9), cand(3, 0.8)));
        onlineLearner.learn(new ExperienceCollector.RecommendationExperience(
                "req-1", USER.userId(), 100L, 3,
                List.of(new ExperienceCollector.ItemFeedback(3, 1, 3, "order", Map.of()))));

        OnlineRecommendationResult result =
                service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 2));

        assertEquals(3, result.recommendations().get(0).id(),
                "learner boost on movie 3 should lift it above movie 4");
    }

    @Test
    void emptyRecallFallsBackToTrendingSnapshot() {
        when(recallService.recall(any(RecommendationQuery.class), anyInt())).thenReturn(List.of());
        when(topkStore.getTopKIds(eq("last_hour"), anyInt())).thenReturn(List.of("5", "6"));

        OnlineRecommendationResult result =
                service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 2));

        assertEquals("multichannel", result.strategy());
        assertEquals(List.of(5, 6), result.recommendations().stream().map(Movie::id).toList());
    }

    @Test
    void responseCarriesRecentAndTrendingSnapshot() {
        when(recentHistoryStore.getRecentMovieIds(eq(USER.userId()), anyInt())).thenReturn(List.of(3));
        when(topkStore.getTopKIds(eq("last_day"), anyInt())).thenReturn(List.of("4", "5"));
        when(recallService.recall(any(RecommendationQuery.class), anyInt())).thenReturn(List.of(cand(6, 0.9)));

        OnlineRecommendationResult result =
                service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_day", 5));

        assertEquals("last_day", result.window());
        assertEquals(List.of(3), result.recentMovies().stream().map(Movie::id).toList());
        assertEquals(List.of(4, 5), result.trendingMovies().stream().map(Movie::id).toList());
    }

    @Test
    void throwsUnknownUserExceptionForMissingUser() {
        OnlineRecommendationService.UnknownUserException ex = assertThrows(
                OnlineRecommendationService.UnknownUserException.class,
                () -> service.recommend(new OnlineRecommendationRequest(999, "last_hour", 5)));
        assertEquals(999, ex.userId());
    }

    @Test
    void rejectsInvalidWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recommend(new OnlineRecommendationRequest(USER.userId(), "bad_window", 5)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=OnlineRecommendationServiceTest`
Expected: COMPILATION FAILURE — the new 5-arg constructor does not exist.

- [ ] **Step 3: Rewrite the service**

Replace the entire contents of `src/main/java/com/recsys/online/serving/OnlineRecommendationService.java`:

```java
package com.recsys.online.serving;

import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.User;
import com.recsys.infrastructure.DataManager;
import com.recsys.online.learner.OnlineLearner;
import com.recsys.online.store.RecentHistoryStore;
import com.recsys.online.store.TrendingStore;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Online recommendation = recall (shared MultiChannelRecallService) -> re-rank (OnlineLearner) ->
 * response snapshot (recent history + per-request trending window). Cold-start detection is handled
 * inside the recall service via the injected user-embedding store.
 */
public final class OnlineRecommendationService {

    private static final Set<String> ALLOWED_WINDOWS = Set.of("last_hour", "last_day", "last_month");
    private static final int RECENT_HISTORY_LIMIT = 3;

    private final DataManager dataManager;
    private final MultiChannelRecallService recallService;
    private final RecentHistoryStore recentHistoryStore;
    private final TrendingStore topkStore;
    private final OnlineLearner onlineLearner;

    public OnlineRecommendationService(DataManager dataManager,
                                       MultiChannelRecallService recallService,
                                       RecentHistoryStore recentHistoryStore,
                                       TrendingStore topkStore,
                                       OnlineLearner onlineLearner) {
        this.dataManager = Objects.requireNonNull(dataManager, "dataManager");
        this.recallService = Objects.requireNonNull(recallService, "recallService");
        this.recentHistoryStore = Objects.requireNonNull(recentHistoryStore, "recentHistoryStore");
        this.topkStore = Objects.requireNonNull(topkStore, "topkStore");
        this.onlineLearner = onlineLearner == null ? new OnlineLearner() : onlineLearner;
    }

    public OnlineRecommendationResult recommend(OnlineRecommendationRequest request) {
        User user = requireUser(request.userId());
        int k = Math.max(1, request.k());
        int recallLimit = Math.max(k * 4, 12);
        String window = normalizeWindow(request.window());

        List<Integer> recentIds = recentHistoryStore.getRecentMovieIds(request.userId(), RECENT_HISTORY_LIMIT);
        Set<String> excluded = new LinkedHashSet<>();
        for (int id : recentIds) excluded.add(String.valueOf(id));

        RecommendationQuery query =
                new RecommendationQuery(String.valueOf(request.userId()), recallLimit, excluded, null);
        List<MovieCandidate> candidates = recallService.recall(query, recallLimit);

        List<Movie> recentMovies = mapMovies(recentIds);
        List<Movie> trendingMovies = mapMovies(parseIds(topkStore.getTopKIds(window, k)));

        List<Movie> recommendations = rerank(candidates, excluded, k);
        if (recommendations.isEmpty()) {
            recommendations = trendingMovies.stream().limit(k).toList();
        }

        return new OnlineRecommendationResult(
                user, window, "multichannel", recentMovies, trendingMovies, recommendations);
    }

    private List<Movie> rerank(List<MovieCandidate> candidates, Set<String> excluded, int k) {
        record Scored(int movieId, double score) {}
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (MovieCandidate c : candidates) {
            if (excluded.contains(c.itemId())) continue;
            int movieId;
            try {
                movieId = Integer.parseInt(c.itemId());
            } catch (NumberFormatException e) {
                continue;
            }
            scored.add(new Scored(movieId, c.score() + onlineLearner.scoreAdjustment(movieId)));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparingInt(Scored::movieId))
                .map(s -> dataManager.getMovieById(s.movieId()))
                .filter(Objects::nonNull)
                .limit(k)
                .toList();
    }

    private List<Movie> mapMovies(List<Integer> ids) {
        List<Movie> movies = new ArrayList<>(ids.size());
        for (int id : ids) {
            Movie m = dataManager.getMovieById(id);
            if (m != null) movies.add(m);
        }
        return List.copyOf(movies);
    }

    private static List<Integer> parseIds(List<String> raw) {
        List<Integer> ids = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                ids.add(Integer.parseInt(s));
            } catch (NumberFormatException ignore) {
                // skip malformed ids from Redis
            }
        }
        return ids;
    }

    private static String normalizeWindow(String window) {
        String normalized = (window == null || window.isBlank()) ? "last_hour" : window.trim();
        if (!ALLOWED_WINDOWS.contains(normalized)) {
            throw new IllegalArgumentException("invalid window: " + normalized);
        }
        return normalized;
    }

    private User requireUser(int userId) {
        User user = dataManager.getUserById(userId);
        if (user == null) throw new UnknownUserException(userId);
        return user;
    }

    public static final class UnknownUserException extends RuntimeException {
        private final int userId;

        public UnknownUserException(int userId) {
            super("user not found");
            this.userId = userId;
        }

        public int userId() { return userId; }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=OnlineRecommendationServiceTest`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/online/serving/OnlineRecommendationService.java \
        src/test/java/com/recsys/online/serving/OnlineRecommendationServiceTest.java
git commit -m "refactor: 7010 OnlineRecommendationService recall via shared MultiChannelRecallService

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Wire 7010 recall service + update integration/regression tests

**Files:**
- Modify: `src/main/java/com/recsys/online/serving/OnlinePredictionServer.java`
- Test (update expectations): `src/test/java/com/recsys/online/serving/OnlinePredictionServerIntegrationTest.java`, `src/test/java/com/recsys/online/serving/OnlinePredictionRegressionTest.java`

**Interfaces:**
- Consumes: `QuotaPolicy.defaultOnline()` (Task 1), `OnlineRecentHistoryChannel` (Task 2), `OnlineRecommendationService` 5-arg constructor (Task 3), `RecallConfig`, `MultiChannelRecallService.from`, `GlobalPopularityStore`, `EmbeddingChannel`, `TrendingChannel`, `PopularityChannel`, `ColdStartChannel`, `ChannelHealthMonitor`, `FaultInjector`.
- Produces: 7010 serving wired onto the shared recall service.

- [ ] **Step 1: Apply the wiring**

In `OnlinePredictionServer.java`, add imports (near the other `com.recsys` imports):

```java
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.online.ops.FaultInjector;
import com.recsys.service.retrieval.channels.EmbeddingChannel;
import com.recsys.service.retrieval.channels.OnlineRecentHistoryChannel;
import com.recsys.service.retrieval.channels.PopularityChannel;
import com.recsys.service.retrieval.channels.TrendingChannel;
import com.recsys.service.retrieval.coldstart.ColdStartChannel;
import com.recsys.service.retrieval.coldstart.QuotaPolicy;
import com.recsys.service.retrieval.multichannel.ChannelHealthMonitor;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.service.retrieval.multichannel.RecallConfig;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
```

Replace the engine + service construction with the block below. Concretely: **delete** the `OnlineRecommendationEngine engine = new OnlineRecommendationEngine(...)` line; **keep** the existing `OnlineLearner onlineLearner = new OnlineLearner();` line as-is (the `LearnerFlushScheduler` below depends on it — do NOT re-declare it); and **replace** the `OnlineRecommendationService recommendationService = new OnlineRecommendationService(dataManager, engine, candidateGenerator, onlineLearner);` line. Insert this between the existing `onlineLearner` declaration and the `learnerFlushScheduler` construction:

```java
            GlobalPopularityStore globalPopStore = new GlobalPopularityStore(jedisPool);
            ExecutorService recallExecutor = Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors() * 2,
                    r -> new Thread(r, "online-recall-channel"));
            MultiChannelRecallService recallService = MultiChannelRecallService.from(
                    RecallConfig.builder()
                            .channels(List.of(
                                    new EmbeddingChannel(candidateGenerator),
                                    new OnlineRecentHistoryChannel(onlineFeatureStore, dataManager),
                                    new TrendingChannel(topkStore, List.of("last_hour", "last_day")),
                                    new PopularityChannel(dataManager, globalPopStore),
                                    new ColdStartChannel(topkStore, globalPopStore)))
                            .quotaPolicy(QuotaPolicy.defaultOnline())
                            .healthMonitor(new ChannelHealthMonitor())
                            .executor(recallExecutor)
                            .channelTimeoutMs(200L)
                            .faultInjector(FaultInjector.NOOP)
                            .userEmbeddingStore(userEmbCache)
                            .build());
            OnlineRecommendationService recommendationService = new OnlineRecommendationService(
                    dataManager, recallService, onlineFeatureStore, topkStore, onlineLearner);
```

Notes:
- Remove the now-unused `OnlineRecommendationEngine engine = ...` line. Leave the `OnlineRecommendationEngine` import only if still referenced elsewhere; otherwise remove it to avoid an unused-import warning.
- `onlineFeatureStore` (an `OnlineFeatureStore`) is passed where a `RecentHistoryStore` is expected (it implements that interface). `topkStore` is the existing `ShardedTopKStore` (a `TrendingStore`). `userEmbCache` is the existing `LogicalExpiryEmbeddingCache`.
- The block above intentionally does NOT declare `onlineLearner` — it reuses the existing declaration. `learnerFlushScheduler` (constructed just below) keeps using that same `onlineLearner`.

Add `recallExecutor.shutdownNow();` to the shutdown-hook thread (alongside `jedisPool.close()` etc.). `recallExecutor` must be effectively final or captured for the lambda; declare it before the hook and reference it directly.

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run the integration + regression tests and update expectations**

Run: `mvn test -Dtest=OnlinePredictionServerIntegrationTest,OnlinePredictionRegressionTest`

These tests pin 7010's previous recommendation output and `strategy` values, which have changed by design. For each failing assertion:
- Replace `strategy` expectations of `"online"` / `"online+model"` with `"multichannel"`.
- For recommendation-content assertions, do NOT blindly snapshot the new output. Verify the new behavior is correct against these invariants, then update the expected values to match what a correct run produces:
  - the response is non-empty for a known seeded user (recall or trending-snapshot fallback),
  - recently-watched movies do not appear in `recommendations`,
  - `recentMovies` and `trendingMovies` are still populated for the requested window,
  - unknown user → 404, invalid window → 400 (unchanged).
- If an assertion checked an exact ordered id list that depended on the old blend formula, replace it with the new correct ordered list observed from the run (after confirming the ordering is sensible per the invariants), or relax it to the invariant (e.g. "contains expected id", "excludes recent") where an exact order is not contractually meaningful.

Document in the commit message which assertions changed and why.

- [ ] **Step 4: Verify the updated tests pass**

Run: `mvn test -Dtest=OnlinePredictionServerIntegrationTest,OnlinePredictionRegressionTest`
Expected: PASS, with assertions reflecting the multichannel behavior.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/online/serving/OnlinePredictionServer.java \
        src/test/java/com/recsys/online/serving/OnlinePredictionServerIntegrationTest.java \
        src/test/java/com/recsys/online/serving/OnlinePredictionRegressionTest.java
git commit -m "feat: wire 7010 onto shared MultiChannelRecallService (5-channel set)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Full-suite + load-test guard

**Files:** none (verification only).

- [ ] **Step 1: Full build + test suite**

Run: `mvn test`
Expected: BUILD SUCCESS — all tests green, including the reworked 7010 tests, `OnlineRecentHistoryChannelTest`, `QuotaPolicyTest`, shared-core tests, and `OnlineRecommendationEngineTest` (engine unchanged).

- [ ] **Step 2: Online prediction load test (opt-in)**

Run: `mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OnlinePredictionLoadTest`
Expected: PASS — recall now runs channels in parallel with a 200 ms per-channel budget; confirm no latency/throughput regression vs the old inline blend.

- [ ] **Step 3: No commit** (verification only).

---

## Self-Review

**Spec coverage:**
- Spec §4.1 `OnlineRecentHistoryChannel` → Task 2 (rank-based scores, recency boost, empty/non-numeric guards).
- Spec §4.2 `QuotaPolicy.defaultOnline()` → Task 1.
- Spec §4.3 `OnlineRecommendationService` (recall→re-rank→snapshot, `OnlineLearner` post-recall, `strategy="multichannel"`, empty fallback, window normalization) → Task 3.
- Spec §4.4 `OnlinePredictionServer` wiring (GlobalPopularityStore, executor, 5-channel RecallConfig, shutdown hook, engine removed) → Task 4.
- Spec §5 budget (200 ms channel timeout, cold detection via userEmbCache) → Task 4 wiring.
- Spec §6 error handling (cold quota, channel failure, empty fallback, 404/400) → Tasks 3 (fallback, window) + 4 (integration).
- Spec §7 testing → Tasks 1–3 (new/reworked unit tests), Task 4 (integration/regression updates), Task 5 (full + load).

**Placeholder scan:** none — Tasks 1–3 carry full code. Task 4's test-update step is an observe-and-update-against-invariants step (the only correct approach for tests pinning legitimately-changed runtime output); it names the exact assertions to change and the invariants that govern, not "figure it out."

**Type consistency:**
- `OnlineRecommendationService(DataManager, MultiChannelRecallService, RecentHistoryStore, TrendingStore, OnlineLearner)` — defined Task 3, constructed Task 4.
- `OnlineRecentHistoryChannel(RecentHistoryStore, DataManager)` — defined Task 2, constructed Task 4.
- `QuotaPolicy.defaultOnline()` — defined Task 1, used Task 4.
- `MovieCandidate(String,double,String channel,Map)` accessor `channel()`; `RecommendationQuery(String,int,Set,String)`; `OnlineRecommendationResult(User,String,String,List,List,List)`; `RecentHistoryStore.getRecentMovieIds(int,int)`; `TrendingStore.getTopKIds(String,int)`; `DataManager.getSimilarMovies(int)`/`getMovieById(int)`/`getUserById(int)` — all match the verified source signatures.
- `MultiChannelRecallService.from(RecallConfig)` and `RecallConfig.builder()` — from sub-project 1 (this branch is stacked on it).
