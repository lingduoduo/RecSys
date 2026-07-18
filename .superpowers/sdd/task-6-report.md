# Task 6 Report: Primary Read-Your-Writes Waiter

## Status

Implemented bounded consistency-token waiting for online recommendations, with primary-only lineage and feature reads.

## TDD evidence

- RED: `mvn test -Dtest=ConsistencyWaiterTest,RoutingRedisExecutorTest,OnlineServicesTest` failed at test compilation because `ConsistencyWaiter` and `executePrimaryRead` did not exist.
- RED: the HTTP tests then failed at test compilation because the token-aware `Prediction` constructor and `recommendPrimary` path did not exist.
- GREEN focused suite: `mvn test -Dtest=ConsistencyWaiterTest,RoutingRedisExecutorTest,OnlineServicesTest,OnlineFeatureStoreTest,OnlineRecommendationServiceTest,OnlinePredictionServerDurableConfigTest` passed 44 tests.

## Implementation

- Added explicit primary-read routing. A routing executor delegates primary reads to `router.writable().executeRead(...)`; replicas are never consulted.
- Added a primary-only Redis lineage reader. It checks `SISMEMBER lineage:event:<eventId> user:<userId>:recent_movies`, exactly matching the feature key inserted by the Flink lineage Lua script.
- Added an injected-clock/injected-sleeper waiter with 50 ms polling and a hard two-second timeout cap.
- Added token validation and subject binding before recommendation execution. Invalid, expired, and mismatched tokens map to 400, 409, and 403.
- Pending materialization maps to 202 with `Retry-After: 1`.
- Applied materialization uses a primary/no-cache recent-history feature read. Tokenless recommendation behavior remains on the existing cache/replica path.
- Wired the waiter only when durable consistency is enabled.

## Concerns

- The existing focused HTTP failure test intentionally logs its simulated durable repository failure; it remains passing.
- The full `mvn test` run reached unrelated existing failures: `OutboxRelayTest.terminalRepositoryFailuresAreReported` failed once and passed alone on immediate rerun; a second full run failed `MySqlIndexContractTest` because the branch's pre-existing outbox/saga indexes exceed that test's expected movie-only inventory. Neither failure touches Task 6 code.
- No lineage semantics were invented: the reader uses the current Flink sink's exact set member contract.

## Reviewer-finding follow-up

- Propagated an explicit primary/no-cache consistency mode through `OnlineRecommendationService`, `MultiChannelRecallService`, `RecallChannel`, and every dynamic online channel. APPLIED requests now use primary reads for both recent-history snapshots, online-recent-history recall, warm/cold user-embedding classification, embedding recall, trending recall, popularity/cold-start recall, and the response trending snapshot. Immutable catalog/movie lookups remain unchanged.
- Added primary methods to the dynamic store ports and concrete Redis/cache implementations. The embedding cache wrappers delegate primary reads directly down to Redis; Top-K and global popularity primary methods skip JVM cache/singleflight/stale fallback and never use `executeRead`.
- Hardened `ConsistencyWaiter` around a monotonic deadline. It checks the deadline before every poll and after every lineage command, never performs a final post-deadline lookup, and rejects a result that completes after the deadline.
- Propagated each poll's remaining budget into `RedisLineageReader` and a timed primary Redis executor method. Lettuce uses a dedicated pooled connection with its sync command timeout set to the remaining request budget.
- RED evidence: the propagation tests initially failed compilation because the primary APIs were absent; the slow-lineage test failed with `expected PENDING but was APPLIED` before the post-command deadline check.
- GREEN evidence: `ConsistencyWaiterTest` passes 6/6 and `mvn package -DskipTests` succeeds. The broader Mockito-based focused suite compiles, but execution is blocked in this environment because Byte Buddy cannot self-attach to this Amazon Corretto 17 VM; all 71 reported errors share that Mockito initialization cause rather than assertion failures.
