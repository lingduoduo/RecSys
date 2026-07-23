# Message Queues in Recsys-Backend-Service

An investigation of the fire-and-forget messaging layer that carries behavioral and
experiment events off the serving path: a bounded in-memory queue drained in batches
by a background thread to one of three transports (log-only, Kafka, or SQS), with
**at-most-once** delivery by design. This is the best-effort counterpart to the
durable transactional-outbox path — the two are deliberately different tools, covered
side by side in §5 and in the
[Eventual Consistency investigation](15_Eventual_Consistency.md).

## The big picture

Events (a feature view, an A/B exposure, a saga transition) must never slow down or
fail a request, so they leave the hot path through
[`AsyncEventPublisher`](src/main/java/com/recsys/infrastructure/messaging/AsyncEventPublisher.java):
the request thread `offer()`s onto a bounded queue and returns in nanoseconds; a
single background thread drains that queue in batches to a broker. Its Javadoc names
the three classic message-queue jobs it does — **peak shaving** (absorb bursts),
**async** (don't block the caller), and **decoupling** (the serving path doesn't know
the broker).

The defining tradeoff is **at-most-once delivery**: if the queue is full or the
broker is down, events are *dropped and counted*, never queued unbounded and never
propagated as a request failure. The default transport is log-only, so local dev,
tests, and the demo need no broker at all. Three producer families and three
transports compose:

| Producer | Default (no broker) | SQS | Kafka |
|---|---|---|---|
| Online events (`7010`) | log-only | `ONLINE_EVENTS_SQS_*` | `ONLINE_EVENTS_KAFKA_*` |
| A/B exposures (`8080`) | log-only | `recsys.events.sqs.*` | `recsys.events.kafka.*` |
| Saga lifecycle | NOOP | `SAGA_EVENTS_SQS_*` | — |

## 1. `AsyncEventPublisher` — the bounded queue and drain loop

The base class is the whole fire-and-forget machine:

- **Bounded queue** — an `ArrayBlockingQueue<EventEnvelope>` of capacity
  `ASYNC_EVENT_QUEUE_CAPACITY` (default **10000**). `EventEnvelope(key, value)` carries
  the optional Kafka partition key alongside the payload.
- **Non-blocking enqueue** — `publish(key, event)` does `queue.offer(...)`: on success
  it bumps `publishedCount`; on a full queue (or after `close()`) it calls
  `recordRejectedEvent()` — increments `droppedCount`, records an
  `async_drop` metric, logs a WARN, and returns `false`. It **never blocks** the
  caller and **never throws**.
- **Batched drain** — one daemon thread (`async-event-publisher`) runs `drainLoop()`:
  `poll(50ms)` for the first event, then `drainTo(batch, batchSize-1)`
  (`ASYNC_EVENT_BATCH_SIZE`, default **100**) to fill a batch, then `sendEnvelopes`.
- **Log-only default** — the base `sendBatch` just increments `drainedCount` and logs
  at DEBUG; concrete transports override it.
- **Graceful close** — `close()` sets `running=false`, interrupts and `join`s the
  drain thread (2 s), then drains and flushes whatever remains synchronously.
- **Observability** — `snapshot()` returns
  `{queueSize, published, dropped, drained, deliveryFailures}` (four `AtomicLong`s).
  On 7010 these surface at `/online/ops` under `events`, where `deliveryFailures`
  (broker-side send errors) is counted *separately* from `dropped` (queue-full or
  invalid-key rejections):

```bash
curl "http://localhost:7010/online/ops" | jq '.events'
# {"queueSize":0,"published":128,"dropped":0,"drained":128,"deliveryFailures":0}
```

## 2. The three transports

- **Log-only (default)** — the base `sendBatch`; used until a transport is
  configured. `LogCollector` is the companion serializer that normalizes a
  `UserBehaviorLog` into the movie-event JSON envelope (schema `user-event-v2`,
  enforcing `userId > 0` / `movieId > 0`, generating an `eventId` UUID if absent).
- **Kafka — keyed, idempotent** —
  [`KafkaAsyncEventPublisher`](src/main/java/com/recsys/infrastructure/messaging/KafkaAsyncEventPublisher.java)
  overrides `sendEnvelopes` to emit one `ProducerRecord(topic, key, value)` per event,
  preserving the partition key, on an **idempotent producer** (`enable.idempotence`,
  `acks=all`, `retries=MAX`, `max.in.flight=5`, `linger.ms=20`, delivery timeout 120 s).
  A send failure calls `recordDeliveryFailure()` and is swallowed; `close()` bounds
  `producer.close(5 s)` so shutdown never hangs on a dead broker. For the
  `ONLINE_EVENTS` transport it carries a key extractor (§3) and **rejects an unkeyed
  event before it enters the queue** — see the partition-key contract in
  [14_Partitioning §3](14_Partitioning.md#3-kafka-topic-partitioning--flink-keyed-pipeline).
- **SQS — standard queue** —
  [`SqsAsyncEventPublisher`](src/main/java/com/recsys/infrastructure/messaging/SqsAsyncEventPublisher.java)
  chunks each batch into `SendMessageBatch` calls of ≤ **10** and logs any failed
  entries. It writes to a **single standard queue** — no `MessageGroupId`, no FIFO —
  so it provides no partitioning or ordering (unlike the Kafka transport); it is a
  best-effort at-most-once sink.

## 3. Transport selection

[`AsyncEventPublisherFactory.fromEnvironment(prefix)`](src/main/java/com/recsys/infrastructure/messaging/AsyncEventPublisherFactory.java)
picks a transport by a **fixed precedence: SQS → Kafka → log-only** —

1. **SQS** if `<PREFIX>_SQS_ENABLED=true` *and* `<PREFIX>_SQS_QUEUE_URL` is non-blank
   (region from `AWS_REGION`, default `us-east-1`).
2. **Kafka** if `<PREFIX>_KAFKA_ENABLED=true` *and* `<PREFIX>_KAFKA_BOOTSTRAP_SERVERS`
   is non-blank. For the special `ONLINE_EVENTS` prefix the topic defaults to
   `movie_events_v2` and a `MovieEventKafkaKeyExtractor` key extractor is attached;
   other prefixes default to topic `online_events` with no extractor.
3. **Log-only** otherwise.

The three producer families each have their own wiring:

- **Online events (7010)** — `AsyncEventPublisherFactory.fromEnvironment("ONLINE_EVENTS")`
  in `OnlinePredictionServer` (`ONLINE_EVENTS_SQS_*` / `ONLINE_EVENTS_KAFKA_*`, topic
  default `movie_events_v2`).

```bash
# SQS
export ONLINE_EVENTS_SQS_ENABLED=true
export ONLINE_EVENTS_SQS_QUEUE_URL="https://sqs.us-east-1.amazonaws.com/…/online-events"
export AWS_REGION=us-east-1
# …or Kafka (topic defaults to movie_events_v2)
export ONLINE_EVENTS_KAFKA_ENABLED=true
export ONLINE_EVENTS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

- **A/B exposures (8080)** — does **not** use this factory; it's a Spring bean
  (`ModelEventConfig`, `recsys.events.sqs.*` / `recsys.events.kafka.*`, exposure topic
  default `ab_exposures`), and its Kafka path publishes **without** a key extractor.
- **Saga lifecycle** — a separate `SagaEventPublishers.fromEnvironment()`
  (`SAGA_EVENTS_SQS_*`, plus `SAGA_EVENTS_SQS_BEST_EFFORT=true` to swallow failures),
  SQS-only, else NOOP.

The key extractor
([`MovieEventKafkaKeyExtractor`](src/main/java/com/recsys/infrastructure/messaging/MovieEventKafkaKeyExtractor.java))
pulls a normalized positive `userId` (`userId`/`user_id`, numeric or `prefix_<id>`) as
the Kafka record key; a missing/zero/negative/unparseable id is rejected before send
and counts toward `dropped` — so a user's events keep per-user order on one partition
and no event is ever published unkeyed.

## 4. Producers and event envelopes

| Producer | What it publishes |
|---|---|
| **Online feature-view events** — `OnlineServices.afterSuccess` | `{eventId, userId, eventType:"feature_view", window, source}` after a successful `/online/features` read |
| **A/B exposure events** — `AbExposureLogger` | `{userId, assignedVariant, servedVariant, fellBackFrom, layer, slot, inExperiment, modelVersion, eventId, timestampMs}` on topic `ab_exposures`; no-op when A/B is off |
| **Saga lifecycle** — `SagaEventPublishers` | saga state transitions (a separate durable `SagaEventPublisher`, not `AsyncEventPublisher`) |

(Note: `ExperienceCollector` groups joined samples for the `OnlineLearner`'s training
join — it's part of online learning, not a message-queue transport.)

## 5. At-most-once vs. durable at-least-once

The messaging layer is deliberately *not* the system's reliable-delivery mechanism.
For events that must not be lost, the transactional outbox is used instead:

| | Messaging (`AsyncEventPublisher`) | Durable outbox |
|---|---|---|
| Semantics | **at-most-once**, best-effort | **at-least-once**, durable |
| Buffer | in-memory bounded queue (drops on full) | persisted outbox table (transactional) |
| Acked when | before delivery (nanoseconds) | committed to the outbox **before** the API acks |
| On failure | dropped / logged | retried by the relay until delivered |

The online feature-view path actually uses **both**: it durably enqueues to the outbox
(`DurableEventPublisher.publishOnline`) for read-your-writes consistency, *and*
fire-and-forgets via `asyncEventPublisher.publish` for best-effort streaming emission.
The at-least-once relay (`OutboxRelay`) and read-your-writes are covered in
[15_Eventual_Consistency](15_Eventual_Consistency.md). The Kafka `movie_events_v2`
topic these publishers write is consumed by the Flink job (`OnlineFeatureStreamingJob`)
→ Redis — the [Kafka → Flink → Redis pipeline](14_Partitioning.md#3-kafka-topic-partitioning--flink-keyed-pipeline).

## 6. Testing

- `AsyncEventPublisherTest` — enqueue returns true, key-preserving envelope, **drops
  silently when the queue is full** (peak shaving), the drain thread flushes,
  snapshot counters.
- `KafkaAsyncEventPublisherTest` — extracted `userId` becomes the Kafka key, legacy
  null-key path, delivery via an injected `Producer`.
- `SqsAsyncEventPublisherTest` — `SendMessageBatch` chunking (≤10) and failure logging.
- `AsyncEventPublisherFactoryTest` — env-driven SQS/Kafka/log-only selection and the
  `ONLINE_EVENTS` topic default.
- `MovieEventKafkaKeyExtractorTest` — id extraction, textual-id parsing, positive-id
  enforcement.

## Sharp edges — notes

1. **At-most-once means events can vanish silently.** A full queue or a broker outage
   drops events (counted in `dropped` / `deliveryFailures`, watchable at
   `/online/ops`) — never blocking the request, but also never retried. Use the outbox
   (§5) for anything that must not be lost.
2. **Only the Kafka `ONLINE_EVENTS` transport keys/orders.** SQS is a standard queue
   (no ordering), and the A/B Kafka path publishes unkeyed — so per-user ordering is a
   guarantee *only* on `movie_events_v2`.
3. **Idempotent producer ≠ end-to-end exactly-once.** Kafka dedups broker retries once
   an event is queued, but the in-memory enqueue can still drop — so the overall
   semantic is at-most-once, not exactly-once.
4. **Off by default is a live-events-off default.** With no transport configured every
   producer is log-only/NOOP, so a deployment that never sets the env vars emits
   nothing to a broker — the streaming pipeline stays empty until Kafka is wired.
5. **Bounded everywhere.** Queue capacity, batch size, producer close timeout, and the
   SQS batch limit are all bounded, so a dead broker degrades to dropped events and a
   fast shutdown rather than memory growth or a hung drain.
