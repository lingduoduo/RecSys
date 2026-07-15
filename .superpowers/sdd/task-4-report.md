# Task 4 Report: Self-Validating SQL Plans

## Status

Complete. `SqlPlan` now rejects placeholder/bind-count mismatches while preserving its existing constructor and pagination APIs.

## TDD Evidence

- RED: `mvn test -Dtest=MillionScalePaginationSqlTest`
  - Exit 1; 12 tests run, 2 failures.
  - Both mismatch tests failed because no exception was raised.
- GREEN: `mvn test -Dtest=MillionScalePaginationSqlTest,MySqlClientTest`
  - Exit 0; 28 tests run, 0 failures, 0 errors, 0 skipped.
- Hygiene: `git diff --check`
  - Exit 0.

## Files

- `src/main/java/com/recsys/application/pagination/MillionScalePaginationSql.java`
  - Counts `?` characters and compares them with immutable bind values.
  - Throws `IllegalArgumentException` reporting expected and actual counts.
  - Documents that quoted/comment question marks are outside the fixed-plan contract.
- `src/test/java/com/recsys/application/pagination/MillionScalePaginationSqlTest.java`
  - Covers too few binds, too many binds, and valid first/subsequent cursor plans.
- `src/test/java/com/recsys/infrastructure/persistence/MySqlClientTest.java`
  - No edit was required: its existing `SqlPlan` fixtures already have matching counts.

## Self-Review

- Existing public signatures are unchanged.
- Validation occurs after `List.copyOf`, retaining immutable bind-value behavior.
- The implementation deliberately does not parse SQL strings, matching the internal fixed-template scope.
- The unrelated pre-existing modification to `.superpowers/sdd/task-3-report.md` was not included.

## Commit

`feat(mysql): validate sql plan bindings`

## Concerns

The raw character count intentionally rejects SQL whose quoted literals or comments contain `?`; callers must keep such SQL outside `SqlPlan`.
