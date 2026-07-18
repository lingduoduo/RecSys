# Task 5 Report: Durable API Acceptance and Signed Consistency Tokens

## RED

- Added `ConsistencyTokenCodecTest` first. `mvn test -Dtest=ConsistencyTokenCodecTest` failed at test compilation because `ConsistencyToken` and `ConsistencyTokenCodec` did not exist.
- Added HTTP acceptance cases to `OnlineServicesTest`. The focused run then failed at test compilation because `DurableEventPublisher` did not exist.
- The first post-implementation run exposed leaked Mockito stubs between HTTP cases; resetting the publisher before each case made the test isolation explicit.

## GREEN

- Added an HMAC-SHA256, three-segment base64url consistency-token codec with constant-time signature comparison, injected `Clock`, exact 24-hour lifetime, UUID/user subject fields, expiry/future-issued checks, bounded parsing, and startup rejection of secrets shorter than 32 UTF-8 bytes.
- Added synchronous `DurableEventPublisher` acceptance through `OutboxRepository.enqueue`. It returns the persisted row's event ID and creation time, preserves outbox conflicts, and maps other persistence failures to a dedicated availability failure.
- Feature-view success now follows durable commit and includes `X-Consistency-Token`. Persistence failures return 503 without a token; conflicting event content returns 409 without a token; identical retries receive the same persisted acceptance/token.
- Legacy async constructors remain available for existing tests, while `OnlinePredictionServer` requires MySQL plus `ONLINE_CONSISTENCY_TOKEN_SECRET` and wires the durable path in production.
- Outbox duplicate comparison treats `createdAt` as server metadata rather than immutable request content, allowing a later identical retry of the same event ID to return the original stored acceptance.

## Verification

- `mvn test -Dtest=ConsistencyTokenCodecTest,OnlineServicesTest,AsyncEventPublisherTest -DfailIfNoTests=false` — 15 tests, 0 failures, 0 errors.
- `mvn test -Dtest=ConsistencyTokenCodecTest,OnlineServicesTest,AsyncEventPublisherTest,OnlinePredictionServerIntegrationTest,MySqlOutboxRepositoryIntegrationTest -DfailIfNoTests=false` — build success; 27 executed tests, 0 failures, 0 errors. The Testcontainers-gated MySQL class was not executed in this environment.

## Self-review

- Confirmed signing covers both encoded header and payload and comparison uses `MessageDigest.isEqual`.
- Confirmed no token is constructed or attached until `repository.enqueue` returns successfully.
- Confirmed tokens use the persisted acceptance timestamp, so identical retries produce the same token even when retry time differs.
- Confirmed unrelated dirty reports and the `src/main/resources/artifacts` scratch link are excluded from Task 5 staging.
- Known operational requirement: online serving now fails fast unless durable MySQL configuration and a 32-byte-or-longer `ONLINE_CONSISTENCY_TOKEN_SECRET` are present; this is intentional.
