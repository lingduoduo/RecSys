# Cold Start & Multi-Channel Recall Improvement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add cold-start detection and quota-aware multi-channel recall to port 6010 (`RecSysServer`), wiring live pipeline Redis data (`global:item_popularity`, `topk:last_day/last_month`) into serving.

**Architecture:** A new `ColdStartChannel` blends Flink topK and Spark popularity signals from Redis. `MultiChannelRecallService` gains a 6-arg constructor that accepts an `EmbeddingStore` for per-request cold-start detection; when a user has no embedding, a `QuotaSpec.cold()` shifts slots from `EmbeddingChannel` to `ColdStartChannel`. `PopularityChannel` and `TrendingChannel` are updated to read live pipeline data instead of static sources.

**Tech Stack:** Java 21, Jedis 3.x, JUnit 5, Mockito, AssertJ, Maven (`mvn test`)

---

## File Map

| Action | Path |
|---|---|
| Create | `src/main/java/com/recsys/infrastructure/redis/GlobalPopularityStore.java` |
| Create | `src/test/java/com/recsys/infrastructure/redis/GlobalPopularityStoreTest.java` |
| Create | `src/main/java/com/recsys/service/retrieval/QuotaSpec.java` |
| Create | `src/test/java/com/recsys/service/retrieval/QuotaSpecTest.java` |
| Create | `src/main/java/com/recsys/service/retrieval/ColdStartChannel.java` |
| Create | `src/test/java/com/recsys/service/retrieval/ColdStartChannelTest.java` |
| Modify | `src/main/java/com/recsys/service/retrieval/TrendingChannel.java` |
| Modify | `src/test/java/com/recsys/service/retrieval/TrendingChannelTest.java` |
| Modify | `src/main/java/com/recsys/service/retrieval/PopularityChannel.java` |
| Modify | `src/test/java/com/recsys/service/retrieval/PopularityChannelTest.java` |
| Modify | `src/main/java/com/recsys/service/retrieval/MultiChannelRecallService.java` |
| Modify | `src/test/java/com/recsys/service/retrieval/MultiChannelRecallServiceTest.java` |
| Modify | `src/main/java/com/recsys/serving/RecSysServer.java` |

---

## Task 1: `GlobalPopularityStore`

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/GlobalPopularityStore.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/GlobalPopularityStoreTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/recsys/infrastructure/redis/GlobalPopularityStoreTest.java`:

```java
package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalPopularityStoreTest {

    @SuppressWarnings("unchecked")
    private static Pool<Jedis> mockPool(Jedis jedis) {
        Pool<Jedis> pool = mock(Pool.class);
        when(pool.getResource()).thenReturn(jedis);
        return pool;
    }

    @Test
    void getTopIds_returnsIdsFromRedisSortedSetInOrder() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of("5", "3", "1")));

        GlobalPopularityStore store = new GlobalPopularityStore(mockPool(jedis));
        List<String> ids = store.getTopIds(3);

        assertThat(ids).containsExactly("5", "3", "1");
    }

    @Test
    void getTopIds_emptyWhenRedisKeyMissing() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>());

        List<String> ids = new GlobalPopularityStore(mockPool(jedis)).getTopIds(10);

        assertThat(ids).isEmpty();
    }

    @Test
    void getTopIds_zeroLimitReturnsEmpty() {
        Jedis jedis = mock(Jedis.class);
        List<String> ids = new GlobalPopularityStore(mockPool(jedis)).getTopIds(0);
        assertThat(ids).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
mvn test -Dtest=GlobalPopularityStoreTest -pl . 2>&1 | tail -20
```

Expected: FAIL — `GlobalPopularityStore` does not exist yet.

- [ ] **Step 3: Implement `GlobalPopularityStore`**

Create `src/main/java/com/recsys/infrastructure/redis/GlobalPopularityStore.java`:

```java
package com.recsys.infrastructure.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.util.ArrayList;
import java.util.List;

public class GlobalPopularityStore {

    public static final String KEY = "global:item_popularity";

    private final Pool<Jedis> pool;

    public GlobalPopularityStore(Pool<Jedis> pool) {
        this.pool = pool;
    }

    public List<String> getTopIds(int limit) {
        if (limit <= 0) return List.of();
        try (Jedis jedis = pool.getResource()) {
            return new ArrayList<>(jedis.zrevrange(KEY, 0, limit - 1));
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=GlobalPopularityStoreTest -pl . 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/GlobalPopularityStore.java \
        src/test/java/com/recsys/infrastructure/redis/GlobalPopularityStoreTest.java
git commit -m "feat: add GlobalPopularityStore wrapping global:item_popularity Redis key"
```

---

## Task 2: `QuotaSpec`

**Files:**
- Create: `src/main/java/com/recsys/service/retrieval/QuotaSpec.java`
- Create: `src/test/java/com/recsys/service/retrieval/QuotaSpecTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/recsys/service/retrieval/QuotaSpecTest.java`:

```java
package com.recsys.service.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaSpecTest {

    @Test
    void warm_embeddingGets60Percent() {
        assertThat(QuotaSpec.warm(10).slotsFor("embedding")).isEqualTo(6);
    }

    @Test
    void warm_trendingGets20Percent() {
        assertThat(QuotaSpec.warm(10).slotsFor("trending")).isEqualTo(2);
    }

    @Test
    void warm_genreHistoryGets15Percent() {
        assertThat(QuotaSpec.warm(10).slotsFor("genre_history")).isEqualTo(2);
    }

    @Test
    void warm_totalSlotsEqualsLimit() {
        int limit = 10;
        QuotaSpec q = QuotaSpec.warm(limit);
        int total = q.slotsFor("embedding") + q.slotsFor("trending")
                + q.slotsFor("genre_history") + q.slotsFor("popularity");
        assertThat(total).isEqualTo(limit);
    }

    @Test
    void cold_coldStartGets50Percent() {
        assertThat(QuotaSpec.cold(10).slotsFor("cold_start")).isEqualTo(5);
    }

    @Test
    void cold_embeddingGetsZero() {
        assertThat(QuotaSpec.cold(10).slotsFor("embedding")).isEqualTo(0);
    }

    @Test
    void cold_totalSlotsEqualsLimit() {
        int limit = 10;
        QuotaSpec q = QuotaSpec.cold(limit);
        int total = q.slotsFor("cold_start") + q.slotsFor("trending")
                + q.slotsFor("popularity") + q.slotsFor("genre_history");
        assertThat(total).isEqualTo(limit);
    }

    @Test
    void slotsFor_unknownChannelReturnsZero() {
        assertThat(QuotaSpec.warm(10).slotsFor("unknown_channel")).isEqualTo(0);
    }

    @Test
    void warm_limit20_slotsProportional() {
        QuotaSpec q = QuotaSpec.warm(20);
        assertThat(q.slotsFor("embedding")).isEqualTo(12);
        assertThat(q.slotsFor("trending")).isEqualTo(4);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=QuotaSpecTest -pl . 2>&1 | tail -20
```

Expected: FAIL — `QuotaSpec` does not exist yet.

- [ ] **Step 3: Implement `QuotaSpec`**

Create `src/main/java/com/recsys/service/retrieval/QuotaSpec.java`:

```java
package com.recsys.service.retrieval;

import java.util.Map;

public record QuotaSpec(Map<String, Integer> slots) {

    public QuotaSpec {
        slots = Map.copyOf(slots);
    }

    public int slotsFor(String channel) {
        return slots.getOrDefault(channel, 0);
    }

    public static QuotaSpec warm(int limit) {
        int emb   = (int) Math.round(limit * 0.60);
        int trend = (int) Math.round(limit * 0.20);
        int genre = (int) Math.round(limit * 0.15);
        int pop   = Math.max(0, limit - emb - trend - genre);
        return new QuotaSpec(Map.of(
                "embedding",     emb,
                "trending",      trend,
                "genre_history", genre,
                "popularity",    pop
        ));
    }

    public static QuotaSpec cold(int limit) {
        int cs    = (int) Math.round(limit * 0.50);
        int trend = (int) Math.round(limit * 0.20);
        int pop   = (int) Math.round(limit * 0.20);
        int genre = Math.max(0, limit - cs - trend - pop);
        return new QuotaSpec(Map.of(
                "cold_start",    cs,
                "trending",      trend,
                "popularity",    pop,
                "genre_history", genre
        ));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=QuotaSpecTest -pl . 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/QuotaSpec.java \
        src/test/java/com/recsys/service/retrieval/QuotaSpecTest.java
git commit -m "feat: add QuotaSpec with warm/cold slot allocations for multi-channel recall"
```

---

## Task 3: `ColdStartChannel`

**Files:**
- Create: `src/main/java/com/recsys/service/retrieval/ColdStartChannel.java`
- Create: `src/test/java/com/recsys/service/retrieval/ColdStartChannelTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/recsys/service/retrieval/ColdStartChannelTest.java`:

```java
package com.recsys.service.retrieval;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.online.store.TrendingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ColdStartChannelTest {

    @Test
    void name_returnsColdStart() {
        assertThat(new ColdStartChannel(mock(TrendingStore.class), mock(GlobalPopularityStore.class))
                .name()).isEqualTo("cold_start");
    }

    @Test
    void recall_blendsSourcesByWeightedRankScore() {
        TrendingStore store = mock(TrendingStore.class);
        GlobalPopularityStore popStore = mock(GlobalPopularityStore.class);

        // last_day weight 0.7: "A" rank0 → 0.7, "B" rank1 → 0.35
        when(store.getTopKIds("last_day", 5)).thenReturn(List.of("A", "B"));
        // last_month weight 0.5: "B" rank0 → 0.5, "C" rank1 → 0.25
        when(store.getTopKIds("last_month", 5)).thenReturn(List.of("B", "C"));
        // global pop weight 0.4: "A" rank0 → 0.4
        when(popStore.getTopIds(5)).thenReturn(List.of("A"));

        ColdStartChannel channel = new ColdStartChannel(store, popStore);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("999", 5, Set.of(), null), 5);

        // "A": 0.7 + 0.4 = 1.1  |  "B": 0.35 + 0.5 = 0.85  |  "C": 0.25
        assertThat(results).extracting(MovieCandidate::itemId).containsExactly("A", "B", "C");
        assertThat(results.get(0).score()).isCloseTo(1.1, within(1e-9));
        assertThat(results.get(1).score()).isCloseTo(0.85, within(1e-9));
        assertThat(results.get(2).score()).isCloseTo(0.25, within(1e-9));
        assertThat(results).extracting(MovieCandidate::channel).containsOnly("cold_start");
    }

    @Test
    void recall_filtersExcludedItems() {
        TrendingStore store = mock(TrendingStore.class);
        GlobalPopularityStore popStore = mock(GlobalPopularityStore.class);
        when(store.getTopKIds("last_day", 5)).thenReturn(List.of("A", "B"));
        when(store.getTopKIds("last_month", 5)).thenReturn(List.of());
        when(popStore.getTopIds(5)).thenReturn(List.of());

        ColdStartChannel channel = new ColdStartChannel(store, popStore);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("999", 5, Set.of("A"), null), 5);

        assertThat(results).extracting(MovieCandidate::itemId).containsOnly("B");
    }

    @Test
    void recall_emptyWhenAllSourcesEmpty() {
        TrendingStore store = mock(TrendingStore.class);
        GlobalPopularityStore popStore = mock(GlobalPopularityStore.class);
        when(store.getTopKIds("last_day", 5)).thenReturn(List.of());
        when(store.getTopKIds("last_month", 5)).thenReturn(List.of());
        when(popStore.getTopIds(5)).thenReturn(List.of());

        List<MovieCandidate> results = new ColdStartChannel(store, popStore).recall(
                new RecommendationQuery("999", 5, Set.of(), null), 5);

        assertThat(results).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=ColdStartChannelTest -pl . 2>&1 | tail -20
```

Expected: FAIL — `ColdStartChannel` does not exist yet.

- [ ] **Step 3: Implement `ColdStartChannel`**

Create `src/main/java/com/recsys/service/retrieval/ColdStartChannel.java`:

```java
package com.recsys.service.retrieval;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.online.store.TrendingStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ColdStartChannel implements RecallChannel {

    private static final Map<String, Double> WINDOW_WEIGHTS = Map.of(
            "last_day",   0.7,
            "last_month", 0.5
    );
    private static final double POPULARITY_WEIGHT = 0.4;

    private final TrendingStore trendingStore;
    private final GlobalPopularityStore globalPopularityStore;

    public ColdStartChannel(TrendingStore trendingStore, GlobalPopularityStore globalPopularityStore) {
        this.trendingStore = trendingStore;
        this.globalPopularityStore = globalPopularityStore;
    }

    @Override
    public String name() {
        return "cold_start";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        Map<String, Double> blended = new LinkedHashMap<>();

        for (Map.Entry<String, Double> entry : WINDOW_WEIGHTS.entrySet()) {
            List<String> ids = trendingStore.getTopKIds(entry.getKey(), limit);
            double weight = entry.getValue();
            for (int i = 0; i < ids.size(); i++) {
                blended.merge(ids.get(i), weight * (1.0 / (i + 1.0)), Double::sum);
            }
        }

        List<String> popIds = globalPopularityStore.getTopIds(limit);
        for (int i = 0; i < popIds.size(); i++) {
            blended.merge(popIds.get(i), POPULARITY_WEIGHT * (1.0 / (i + 1.0)), Double::sum);
        }

        return blended.entrySet().stream()
                .filter(e -> !query.excludedItemIds().contains(e.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new MovieCandidate(e.getKey(), e.getValue(), name(), Map.of()))
                .toList();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=ColdStartChannelTest -pl . 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/ColdStartChannel.java \
        src/test/java/com/recsys/service/retrieval/ColdStartChannelTest.java
git commit -m "feat: add ColdStartChannel blending topk:last_day/last_month and global:item_popularity"
```

---

## Task 4: Update `TrendingChannel` — multi-window with time-decay scoring

**Files:**
- Modify: `src/main/java/com/recsys/service/retrieval/TrendingChannel.java`
- Modify: `src/test/java/com/recsys/service/retrieval/TrendingChannelTest.java`

- [ ] **Step 1: Replace `TrendingChannelTest.java` with updated tests**

Replace the entire content of `src/test/java/com/recsys/service/retrieval/TrendingChannelTest.java`:

```java
package com.recsys.service.retrieval;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.online.store.TrendingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrendingChannelTest {

    @Test
    void name_returnsTrending() {
        assertThat(new TrendingChannel(mock(TrendingStore.class)).name()).isEqualTo("trending");
    }

    @Test
    void recall_singleWindowUsesRankBasedScore() {
        TrendingStore store = mock(TrendingStore.class);
        when(store.getTopKIds("last_hour", 3)).thenReturn(List.of("10", "20", "30"));

        TrendingChannel channel = new TrendingChannel(store);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 3, Set.of(), null), 3);

        assertThat(results).hasSize(3);
        assertThat(results).extracting(MovieCandidate::itemId).containsExactly("10", "20", "30");
        // last_hour weight 1.0: rank0 → 1.0, rank1 → 0.5, rank2 → 0.333
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(1).score()).isEqualTo(0.5);
        assertThat(results.get(2).score()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(results).extracting(MovieCandidate::channel).containsOnly("trending");
    }

    @Test
    void recall_multiWindowBlendsSumsByWeightedRank() {
        TrendingStore store = mock(TrendingStore.class);
        // last_hour weight 1.0: "10" rank0 → 1.0, "20" rank1 → 0.5
        when(store.getTopKIds("last_hour", 5)).thenReturn(List.of("10", "20"));
        // last_day weight 0.6: "10" rank0 → 0.6, "30" rank1 → 0.3
        when(store.getTopKIds("last_day", 5)).thenReturn(List.of("10", "30"));

        TrendingChannel channel = new TrendingChannel(store, List.of("last_hour", "last_day"));
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        // "10": 1.0 + 0.6 = 1.6  |  "20": 0.5  |  "30": 0.3
        assertThat(results).extracting(MovieCandidate::itemId).containsExactly("10", "20", "30");
        assertThat(results.get(0).score()).isCloseTo(1.6, within(1e-9));
        assertThat(results.get(1).score()).isCloseTo(0.5, within(1e-9));
        assertThat(results.get(2).score()).isCloseTo(0.3, within(1e-9));
    }

    @Test
    void recall_emptyWhenStoreReturnsEmpty() {
        TrendingStore store = mock(TrendingStore.class);
        when(store.getTopKIds("last_hour", 5)).thenReturn(List.of());

        List<MovieCandidate> results = new TrendingChannel(store).recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).isEmpty();
    }
}
```

- [ ] **Step 2: Run existing tests to confirm they fail with the old implementation**

```bash
mvn test -Dtest=TrendingChannelTest -pl . 2>&1 | tail -20
```

Expected: FAIL on `recall_singleWindowUsesRankBasedScore` (old impl uses flat score 0.6) and `recall_multiWindowBlendsSumsByWeightedRank` (old impl has no multi-window constructor).

- [ ] **Step 3: Replace `TrendingChannel.java` with multi-window implementation**

Replace the entire content of `src/main/java/com/recsys/service/retrieval/TrendingChannel.java`:

```java
package com.recsys.service.retrieval;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.online.store.TrendingStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TrendingChannel implements RecallChannel {

    private static final Map<String, Double> PREDEFINED_WEIGHTS = Map.of(
            "last_hour",  1.0,
            "last_day",   0.6,
            "last_month", 0.4
    );

    private final TrendingStore trendingStore;
    private final Map<String, Double> windowWeights;

    public TrendingChannel(TrendingStore trendingStore) {
        this(trendingStore, List.of("last_hour"));
    }

    public TrendingChannel(TrendingStore trendingStore, List<String> windows) {
        this.trendingStore = trendingStore;
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String w : windows) {
            weights.put(w, PREDEFINED_WEIGHTS.getOrDefault(w, 1.0));
        }
        this.windowWeights = Map.copyOf(weights);
    }

    @Override
    public String name() {
        return "trending";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        Map<String, Double> blended = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : windowWeights.entrySet()) {
            List<String> ids = trendingStore.getTopKIds(entry.getKey(), limit);
            double weight = entry.getValue();
            for (int i = 0; i < ids.size(); i++) {
                blended.merge(ids.get(i), weight * (1.0 / (i + 1.0)), Double::sum);
            }
        }
        return blended.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new MovieCandidate(e.getKey(), e.getValue(), name(), Map.of()))
                .toList();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=TrendingChannelTest -pl . 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/TrendingChannel.java \
        src/test/java/com/recsys/service/retrieval/TrendingChannelTest.java
git commit -m "feat: update TrendingChannel to multi-window rank-based scoring with time-decay weights"
```

---

## Task 5: Update `PopularityChannel` — Redis-primary with DataManager fallback

**Files:**
- Modify: `src/main/java/com/recsys/service/retrieval/PopularityChannel.java`
- Modify: `src/test/java/com/recsys/service/retrieval/PopularityChannelTest.java`

- [ ] **Step 1: Add new tests to `PopularityChannelTest.java`**

Append these tests to `src/test/java/com/recsys/service/retrieval/PopularityChannelTest.java` (keep all existing tests, add the following inside the class):

```java
    // ── new tests: Redis-primary path ──

    @Test
    void recall_usesRedisWhenGlobalPopStoreNonEmpty() {
        DataManager dm = mock(DataManager.class);
        GlobalPopularityStore popStore = mock(GlobalPopularityStore.class);
        when(popStore.getTopIds(5)).thenReturn(List.of("10", "20", "30"));

        PopularityChannel channel = new PopularityChannel(dm, popStore);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        // rank-based: 1/(0+1)=1.0, 1/(1+1)=0.5, 1/(2+1)=0.333
        assertThat(results).extracting(MovieCandidate::itemId).containsExactly("10", "20", "30");
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(1).score()).isEqualTo(0.5);
        assertThat(results.get(2).score()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(results).extracting(MovieCandidate::channel).containsOnly("popularity");
    }

    @Test
    void recall_fallsBackToDataManagerWhenRedisEmpty() {
        DataManager dm = mock(DataManager.class);
        GlobalPopularityStore popStore = mock(GlobalPopularityStore.class);
        when(popStore.getTopIds(anyInt())).thenReturn(List.of());
        Movie m = new Movie(1, "Top", 2020, List.of("Action"));
        when(dm.getTopRatedMovies(anyInt())).thenReturn(List.of(m));
        when(dm.getLatestMovies(anyInt())).thenReturn(List.of());

        PopularityChannel channel = new PopularityChannel(dm, popStore);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).itemId()).isEqualTo("1");
        assertThat(results.get(0).score()).isEqualTo(0.4); // DataManager fallback uses flat score
    }

    @Test
    void recall_oneArgConstructorStillUsesDataManager() {
        DataManager dm = mock(DataManager.class);
        Movie m = new Movie(99, "Classic", 2019, List.of("Drama"));
        when(dm.getTopRatedMovies(anyInt())).thenReturn(List.of(m));
        when(dm.getLatestMovies(anyInt())).thenReturn(List.of());

        List<MovieCandidate> results = new PopularityChannel(dm).recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(0.4);
    }
```

Also add these imports to `PopularityChannelTest.java` at the top (add alongside existing imports):

```java
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
```

- [ ] **Step 2: Run new tests to confirm they fail**

```bash
mvn test -Dtest=PopularityChannelTest -pl . 2>&1 | tail -20
```

Expected: FAIL on the three new tests — `PopularityChannel` doesn't have a 2-arg constructor yet.

- [ ] **Step 3: Update `PopularityChannel.java`**

Replace the entire content of `src/main/java/com/recsys/service/retrieval/PopularityChannel.java`:

```java
package com.recsys.service.retrieval;

import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PopularityChannel implements RecallChannel {

    static final double FALLBACK_SCORE = 0.4;

    private final DataManager dataManager;
    private final GlobalPopularityStore globalPopularityStore;

    public PopularityChannel(DataManager dataManager) {
        this(dataManager, null);
    }

    public PopularityChannel(DataManager dataManager, GlobalPopularityStore globalPopularityStore) {
        this.dataManager = dataManager;
        this.globalPopularityStore = globalPopularityStore;
    }

    @Override
    public String name() {
        return "popularity";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        if (globalPopularityStore != null) {
            List<String> ids = globalPopularityStore.getTopIds(limit);
            if (!ids.isEmpty()) {
                List<MovieCandidate> candidates = new ArrayList<>(ids.size());
                for (int i = 0; i < ids.size(); i++) {
                    candidates.add(new MovieCandidate(ids.get(i), 1.0 / (i + 1.0), name(), Map.of()));
                }
                return candidates;
            }
        }
        // DataManager fallback
        Map<Integer, Movie> deduped = new LinkedHashMap<>();
        for (Movie m : dataManager.getTopRatedMovies(limit)) deduped.put(m.id(), m);
        for (Movie m : dataManager.getLatestMovies(limit)) deduped.put(m.id(), m);
        return deduped.values().stream()
                .map(m -> new MovieCandidate(String.valueOf(m.id()), FALLBACK_SCORE, name(), Map.of()))
                .toList();
    }
}
```

- [ ] **Step 4: Run all popularity tests to verify they pass**

```bash
mvn test -Dtest=PopularityChannelTest -pl . 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 6 tests pass (3 existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/PopularityChannel.java \
        src/test/java/com/recsys/service/retrieval/PopularityChannelTest.java
git commit -m "feat: update PopularityChannel to read global:item_popularity from Redis with DataManager fallback"
```

---

## Task 6: Update `MultiChannelRecallService` — cold-start detection + quota-aware merge

**Files:**
- Modify: `src/main/java/com/recsys/service/retrieval/MultiChannelRecallService.java`
- Modify: `src/test/java/com/recsys/service/retrieval/MultiChannelRecallServiceTest.java`

- [ ] **Step 1: Add new tests to `MultiChannelRecallServiceTest.java`**

Append these tests inside the class in `src/test/java/com/recsys/service/retrieval/MultiChannelRecallServiceTest.java` (keep all existing tests):

```java
    // ── new tests: cold-start detection and quota-aware merge ──

    @Test
    void coldUser_zeroEmbeddingQuota_coldStartFillsQuota() {
        com.recsys.infrastructure.vectordb.EmbeddingStore userEmb =
                mock(com.recsys.infrastructure.vectordb.EmbeddingStore.class);
        when(userEmb.getEmbedding(1)).thenReturn(null); // no embedding → cold user

        RecallChannel embChannel   = channel("embedding",
                new MovieCandidate("10", 0.9, "embedding",   Map.of()),
                new MovieCandidate("11", 0.8, "embedding",   Map.of()));
        RecallChannel coldCh       = channel("cold_start",
                new MovieCandidate("20", 0.6, "cold_start",  Map.of()),
                new MovieCandidate("21", 0.5, "cold_start",  Map.of()),
                new MovieCandidate("22", 0.4, "cold_start",  Map.of()));
        RecallChannel trendingCh   = channel("trending",
                new MovieCandidate("30", 0.3, "trending",    Map.of()));
        RecallChannel genreCh      = channel("genre_history",
                new MovieCandidate("40", 0.2, "genre_history", Map.of()));
        RecallChannel popCh        = channel("popularity",
                new MovieCandidate("50", 0.1, "popularity",  Map.of()));

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(embChannel, coldCh, trendingCh, genreCh, popCh),
                new ChannelHealthMonitor(),
                java.util.concurrent.ForkJoinPool.commonPool(),
                200L,
                FaultInjector.NOOP,
                userEmb);

        // cold quota limit=10: cold_start=5, trending=2, popularity=2, genre_history=1, embedding=0
        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 10, Set.of(), null), 10);

        // Quota fill: cold_start contributes "20","21","22" (3 of 5 slots)
        //             trending "30", genre "40", popularity "50"
        // Gap fill: "10","11" from embedding (unselected, highest remaining score)
        assertThat(recalled).extracting(MovieCandidate::itemId)
                .containsExactlyInAnyOrder("10", "11", "20", "21", "22", "30", "40", "50");
        assertThat(recalled).filteredOn(c -> "cold_start".equals(c.channel())).hasSize(3);
        assertThat(recalled).filteredOn(c -> "embedding".equals(c.channel())).hasSize(2);
    }

    @Test
    void warmUser_embeddingChannelGets60PercentQuota() {
        com.recsys.infrastructure.vectordb.EmbeddingStore userEmb =
                mock(com.recsys.infrastructure.vectordb.EmbeddingStore.class);
        when(userEmb.getEmbedding(1)).thenReturn(new float[]{0.1f}); // has embedding → warm

        RecallChannel embChannel = channel("embedding",
                new MovieCandidate("10", 0.9, "embedding", Map.of()),
                new MovieCandidate("11", 0.8, "embedding", Map.of()),
                new MovieCandidate("12", 0.7, "embedding", Map.of()),
                new MovieCandidate("13", 0.6, "embedding", Map.of()),
                new MovieCandidate("14", 0.5, "embedding", Map.of()),
                new MovieCandidate("15", 0.4, "embedding", Map.of()));
        RecallChannel trendingCh = channel("trending",
                new MovieCandidate("30", 0.3, "trending", Map.of()),
                new MovieCandidate("31", 0.2, "trending", Map.of()));

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(embChannel, trendingCh),
                new ChannelHealthMonitor(),
                java.util.concurrent.ForkJoinPool.commonPool(),
                200L,
                FaultInjector.NOOP,
                userEmb);

        // warm quota limit=10: embedding=6, trending=2, genre_history=2, popularity=0
        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 10, Set.of(), null), 10);

        assertThat(recalled).filteredOn(c -> "embedding".equals(c.channel())).hasSize(6);
        assertThat(recalled).filteredOn(c -> "trending".equals(c.channel())).hasSize(2);
    }

    @Test
    void nullUserEmbeddingStore_fallsBackToLegacyMaxScoreMerge() {
        // 1-arg constructor passes null userEmbeddingStore → old behavior preserved
        RecallChannel ch1 = channel("ch1",
                new MovieCandidate("A", 0.9, "ch1", Map.of()));
        RecallChannel ch2 = channel("ch2",
                new MovieCandidate("A", 0.5, "ch2", Map.of()),
                new MovieCandidate("B", 0.7, "ch2", Map.of()));

        MultiChannelRecallService service = new MultiChannelRecallService(List.of(ch1, ch2));

        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 10, Set.of(), null), 10);

        // Legacy merge: "A" keeps ch1 score 0.9 (higher), "B" from ch2 0.7
        assertThat(recalled).extracting(MovieCandidate::itemId).containsExactly("A", "B");
        assertThat(recalled.get(0).score()).isEqualTo(0.9);
        assertThat(recalled.get(0).channel()).isEqualTo("ch1");
    }

    @Test
    void quotaMerge_gapFillWhenChannelReturnsFewerThanQuota() {
        com.recsys.infrastructure.vectordb.EmbeddingStore userEmb =
                mock(com.recsys.infrastructure.vectordb.EmbeddingStore.class);
        when(userEmb.getEmbedding(1)).thenReturn(new float[]{0.1f}); // warm

        // embedding has 6 quota slots but only returns 2 candidates
        RecallChannel embChannel = channel("embedding",
                new MovieCandidate("10", 0.9, "embedding", Map.of()),
                new MovieCandidate("11", 0.8, "embedding", Map.of()));
        // trending has 2 quota slots, returns 3 (one spills to gap fill)
        RecallChannel trendingCh = channel("trending",
                new MovieCandidate("30", 0.7, "trending", Map.of()),
                new MovieCandidate("31", 0.6, "trending", Map.of()),
                new MovieCandidate("32", 0.5, "trending", Map.of()));

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(embChannel, trendingCh),
                new ChannelHealthMonitor(),
                java.util.concurrent.ForkJoinPool.commonPool(),
                200L,
                FaultInjector.NOOP,
                userEmb);

        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 10, Set.of(), null), 10);

        // Quota fill: embedding gets "10","11" (2 of 6 slots)
        //             trending gets "30","31" (2 of 2 slots)
        // Gap fill: "32" from trending (leftover)
        assertThat(recalled).extracting(MovieCandidate::itemId)
                .containsExactlyInAnyOrder("10", "11", "30", "31", "32");
    }
```

Also add this import at the top of `MultiChannelRecallServiceTest.java`:

```java
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
```

- [ ] **Step 2: Run new tests to confirm they fail**

```bash
mvn test -Dtest=MultiChannelRecallServiceTest -pl . 2>&1 | tail -20
```

Expected: FAIL on the four new tests — `MultiChannelRecallService` lacks a 6-arg constructor.

- [ ] **Step 3: Replace `MultiChannelRecallService.java`**

Replace the entire content of `src/main/java/com/recsys/service/retrieval/MultiChannelRecallService.java`:

```java
package com.recsys.service.retrieval;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.online.ops.FaultInjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

public class MultiChannelRecallService {
    private static final Logger log = LoggerFactory.getLogger(MultiChannelRecallService.class);
    private static final long DEFAULT_CHANNEL_TIMEOUT_MS = 200L;

    private final List<RecallChannel> channels;
    private final ChannelHealthMonitor healthMonitor;
    private final ExecutorService executor;
    private final long channelTimeoutMs;
    private final FaultInjector faultInjector;
    private final EmbeddingStore userEmbeddingStore;

    public MultiChannelRecallService(List<RecallChannel> channels) {
        this(channels, new ChannelHealthMonitor(), ForkJoinPool.commonPool(),
                DEFAULT_CHANNEL_TIMEOUT_MS, FaultInjector.NOOP, null);
    }

    public MultiChannelRecallService(List<RecallChannel> channels,
                                     ChannelHealthMonitor healthMonitor,
                                     ExecutorService executor,
                                     long channelTimeoutMs,
                                     FaultInjector faultInjector) {
        this(channels, healthMonitor, executor, channelTimeoutMs, faultInjector, null);
    }

    public MultiChannelRecallService(List<RecallChannel> channels,
                                     ChannelHealthMonitor healthMonitor,
                                     ExecutorService executor,
                                     long channelTimeoutMs,
                                     FaultInjector faultInjector,
                                     EmbeddingStore userEmbeddingStore) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("at least one recall channel is required");
        }
        this.channels            = List.copyOf(channels);
        this.healthMonitor       = Objects.requireNonNull(healthMonitor, "healthMonitor");
        this.executor            = Objects.requireNonNull(executor, "executor");
        this.channelTimeoutMs    = Math.max(1L, channelTimeoutMs);
        this.faultInjector       = faultInjector == null ? FaultInjector.NOOP : faultInjector;
        this.userEmbeddingStore  = userEmbeddingStore;
    }

    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit <= 0) return List.of();

        QuotaSpec quota = null;
        if (userEmbeddingStore != null) {
            int userId = Integer.parseInt(query.userId());
            boolean isCold = userEmbeddingStore.getEmbedding(userId) == null;
            quota = isCold ? QuotaSpec.cold(limit) : QuotaSpec.warm(limit);
        }

        List<CompletableFuture<ChannelResult>> futures = new ArrayList<>(channels.size());
        for (RecallChannel channel : channels) {
            if (!healthMonitor.isAvailable(channel.name())) {
                log.debug("Channel '{}' is in backoff — skipping", channel.name());
                continue;
            }
            String name = channel.name();
            CompletableFuture<ChannelResult> future = CompletableFuture
                    .supplyAsync(() -> {
                        faultInjector.maybeInject("channel:" + name);
                        return new ChannelResult(name, channel.recall(query, limit), null);
                    }, executor)
                    .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> new ChannelResult(name, List.of(), ex));
            futures.add(future);
        }

        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        Map<String, List<MovieCandidate>> channelResults = new LinkedHashMap<>();
        for (CompletableFuture<ChannelResult> future : futures) {
            ChannelResult result = future.join();
            if (result.error() != null) {
                healthMonitor.recordFailure(result.channel());
                Throwable err = result.error();
                log.warn("Channel '{}' failed: {}", result.channel(),
                        err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName());
                continue;
            }
            healthMonitor.recordSuccess(result.channel());
            List<MovieCandidate> sorted = result.candidates().stream()
                    .sorted(Comparator.comparingDouble(MovieCandidate::score).reversed()
                            .thenComparing(MovieCandidate::itemId))
                    .toList();
            channelResults.put(result.channel(), sorted);
        }

        if (quota == null) {
            return legacyMerge(channelResults, query, limit);
        }
        return quotaMerge(channelResults, quota, query, limit);
    }

    private List<MovieCandidate> legacyMerge(Map<String, List<MovieCandidate>> channelResults,
                                              RecommendationQuery query, int limit) {
        Map<String, MovieCandidate> merged = new LinkedHashMap<>();
        for (List<MovieCandidate> candidates : channelResults.values()) {
            for (MovieCandidate c : candidates) {
                if (query.excludedItemIds().contains(c.itemId())) continue;
                merged.merge(c.itemId(), c,
                        (existing, incoming) ->
                                incoming.score() > existing.score() ? incoming : existing);
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(MovieCandidate::score).reversed()
                        .thenComparing(MovieCandidate::itemId))
                .limit(limit)
                .toList();
    }

    private List<MovieCandidate> quotaMerge(Map<String, List<MovieCandidate>> channelResults,
                                             QuotaSpec quota,
                                             RecommendationQuery query, int limit) {
        Set<String> selectedIds = new LinkedHashSet<>();
        List<MovieCandidate> result = new ArrayList<>();

        for (RecallChannel channel : channels) {
            String name = channel.name();
            List<MovieCandidate> candidates = channelResults.getOrDefault(name, List.of());
            int channelSlots = quota.slotsFor(name);
            int count = 0;
            for (MovieCandidate c : candidates) {
                if (count >= channelSlots) break;
                if (!selectedIds.contains(c.itemId()) && !query.excludedItemIds().contains(c.itemId())) {
                    result.add(c);
                    selectedIds.add(c.itemId());
                    count++;
                }
            }
        }

        if (result.size() < limit) {
            Map<String, MovieCandidate> gapPool = new LinkedHashMap<>();
            for (List<MovieCandidate> candidates : channelResults.values()) {
                for (MovieCandidate c : candidates) {
                    if (!selectedIds.contains(c.itemId()) && !query.excludedItemIds().contains(c.itemId())) {
                        gapPool.merge(c.itemId(), c,
                                (a, b) -> b.score() > a.score() ? b : a);
                    }
                }
            }
            gapPool.values().stream()
                    .sorted(Comparator.comparingDouble(MovieCandidate::score).reversed()
                            .thenComparing(MovieCandidate::itemId))
                    .limit(limit - result.size())
                    .forEach(result::add);
        }

        result.sort(Comparator.comparingDouble(MovieCandidate::score).reversed()
                .thenComparing(MovieCandidate::itemId));
        return result.size() > limit ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
    }

    private record ChannelResult(String channel, List<MovieCandidate> candidates, Throwable error) {
        ChannelResult { Objects.requireNonNull(candidates, "candidates"); }
    }
}
```

- [ ] **Step 4: Run all recall service tests**

```bash
mvn test -Dtest=MultiChannelRecallServiceTest -pl . 2>&1 | tail -10
```

Expected: BUILD SUCCESS, all tests pass (existing + 4 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/MultiChannelRecallService.java \
        src/test/java/com/recsys/service/retrieval/MultiChannelRecallServiceTest.java
git commit -m "feat: add cold-start detection and quota-aware two-phase merge to MultiChannelRecallService"
```

---

## Task 7: Wire `RecSysServer` with all new components

**Files:**
- Modify: `src/main/java/com/recsys/serving/RecSysServer.java`

- [ ] **Step 1: Update `RecSysServer.run()` to wire new components**

In `src/main/java/com/recsys/serving/RecSysServer.java`, apply these changes:

Add the following import alongside existing imports:

```java
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.service.retrieval.ColdStartChannel;
import com.recsys.online.ops.FaultInjector;
import java.util.concurrent.Executors;
```

Replace the block that builds `recallService` (currently lines 80–85):

```java
            // Before:
            MultiChannelRecallService recallService = new MultiChannelRecallService(List.of(
                    new EmbeddingChannel(candidateGenerator),
                    new TrendingChannel(topkStore),
                    new GenreHistoryChannel(candidateGenerator),
                    new PopularityChannel(dataManager)
            ));
```

With:

```java
            // After:
            GlobalPopularityStore globalPopStore = new GlobalPopularityStore(jedisPool);

            MultiChannelRecallService recallService = new MultiChannelRecallService(
                    List.of(
                            new EmbeddingChannel(candidateGenerator),
                            new ColdStartChannel(topkStore, globalPopStore),
                            new TrendingChannel(topkStore, List.of("last_hour", "last_day")),
                            new GenreHistoryChannel(candidateGenerator),
                            new PopularityChannel(dataManager, globalPopStore)
                    ),
                    new ChannelHealthMonitor(),
                    Executors.newFixedThreadPool(10),
                    200L,
                    FaultInjector.NOOP,
                    userEmbCache
            );
```

- [ ] **Step 2: Verify the project compiles**

```bash
mvn package -DskipTests 2>&1 | tail -15
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Run the full retrieval test suite**

```bash
mvn test -Dtest="MultiChannelRecallServiceTest,TrendingChannelTest,PopularityChannelTest,ColdStartChannelTest,QuotaSpecTest,GlobalPopularityStoreTest,EmbeddingChannelTest,GenreHistoryChannelTest,ChannelHealthMonitorTest,RecSysServerIntegrationTest,RecSysServerRegressionTest" -pl . 2>&1 | tail -20
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 4: Run the full test suite**

```bash
mvn test 2>&1 | tail -20
```

Expected: BUILD SUCCESS, no regressions.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/serving/RecSysServer.java
git commit -m "feat: wire ColdStartChannel, GlobalPopularityStore, and multi-window TrendingChannel into RecSysServer"
```

---

## Self-Review

**Spec coverage check:**

| Spec section | Covered by task |
|---|---|
| §4.1 `ColdStartChannel` (topk:last_day, topk:last_month, global:item_popularity) | Task 3 |
| §4.2 `QuotaSpec` warm/cold factory methods | Task 2 |
| §5.1 `MultiChannelRecallService` 6-arg constructor + cold-start detection | Task 6 |
| §5.1 Two-phase quota-fill + gap-fill merge | Task 6 |
| §5.2 `TrendingChannel` multi-window time-decay | Task 4 |
| §5.3 `PopularityChannel` Redis-primary + DataManager fallback | Task 5 |
| §6 `GlobalPopularityStore` thin Redis wrapper | Task 1 |
| §6 `RecSysServer` wiring | Task 7 |
| §7 Unit tests for all new/modified components | Tasks 1–6 |
| §7 Regression guard — existing tests pass without modification | Task 7 step 3–4 |
| Backward compat: 1-arg and 5-arg constructors unchanged | Task 6 (nullUserEmbeddingStore test) |

No gaps found.
