# Task 1 Report: Key-Aware Bounded Publisher

## Status

Implemented and committed Task 1 only.

Commit: `dd577760fd4656c45d0f9b2d663c74bacc8309ec` (`feat(kafka): publish online events by user key`)

## Changes

- Replaced the bounded string queue with `EventEnvelope(key, value)` while retaining the legacy `sendBatch(List<String>)` transport seam.
- Added keyed `publish(String key, String event)` and preserved the existing source-compatible overloads.
- Added rejection metric support for invalid extracted keys.
- Added keyed Kafka constructors, key extraction on publish, and keyed `ProducerRecord` creation during envelope draining.
- Added `MovieEventKafkaKeyExtractor` using a shared Jackson mapper with positive-long normalization and malformed/missing/non-positive/overflow rejection.
- Added envelope preservation, keyed Kafka record, extractor happy-path, and extractor rejection tests.

## TDD Evidence

RED command:

`mvn test -Dtest=AsyncEventPublisherTest,KafkaAsyncEventPublisherTest,MovieEventKafkaKeyExtractorTest`

Observed expected test-compilation failure for missing `EventEnvelope`, keyed `publish`, keyed Kafka constructor, and `MovieEventKafkaKeyExtractor`.

GREEN/final command (run outside the filesystem sandbox because Mockito's inline Byte Buddy mock maker requires JVM attachment):

`mvn test -Dtest=AsyncEventPublisherTest,KafkaAsyncEventPublisherTest,MovieEventKafkaKeyExtractorTest,SqsAsyncEventPublisherTest`

Result: `BUILD SUCCESS`; 19 tests run, 0 failures, 0 errors, 0 skipped.

Additional verification: `git diff --check` and `git diff --cached --check` both passed before commit.

## Self-review

- SQS and existing string-batch subclasses remain compatible because `sendEnvelopes` delegates values to the unchanged `sendBatch` seam.
- Drain and synchronous close paths both preserve keys.
- Kafka's legacy direct `sendBatch` path continues producing null-key records.
- Invalid keys increment dropped rather than published.
- Pre-existing dirty and untracked files were not staged or modified by this task.

## Concerns

- Mockito-based tests cannot initialize inside the managed sandbox on this host due to JVM self-attachment restrictions; the identical focused suite passes outside the sandbox.

## Review Fix

Review identified that an empty-returning extractor incorrectly made legacy constructors reject every public `publish` call. Legacy constructors now use explicit no-extractor (`null`) mode, so `publish(String)` delegates to the base publisher and Kafka receives a null-key record. Only explicitly configured extractors reject events without a valid key.

Added regression coverage for both public legacy paths:

- `publish(String)` is accepted and asynchronously produces a null-key Kafka record.
- `publish(LogCollector.KafkaEvent)` dynamically dispatches through the Kafka string overload, is accepted, and produces the envelope value with a null Kafka key.

Review RED command:

`mvn test -Dtest=KafkaAsyncEventPublisherTest`

Result before fix: 6 tests run, 2 failures. Both new legacy publishing tests expected `true` but received `false`.

Review GREEN command:

`mvn test -Dtest=AsyncEventPublisherTest,KafkaAsyncEventPublisherTest,MovieEventKafkaKeyExtractorTest,SqsAsyncEventPublisherTest`

Result after fix: `BUILD SUCCESS`; 21 tests run, 0 failures, 0 errors, 0 skipped.
