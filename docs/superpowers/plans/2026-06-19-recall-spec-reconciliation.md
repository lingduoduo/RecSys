# Recall Spec Reconciliation + Coverage Plan

_Date: 2026-06-19_
_Scope: Reconcile the shared-recall design docs with the shipped code and close the one real test-coverage gap surfaced by verification. No production behavior change._

**Context:** A full verification of the three shared-recall sub-projects (PR #125 / #126 / branch `feat/recall-cleanup`) found the implementation correct and the test suite green. It surfaced four notes. This plan resolves all four: two are doc reconciliations, one is a false alarm to document, one is a genuine unit-coverage gap to close.

## Note triage

| # | Note | Verdict | Task |
|---|---|---|---|
| 1 | SP2 design §4.1 says `OnlineRecentHistoryChannel` emits the summed `recencyBoost - rank`; code emits rank-based `1.0/(rank+1)` (sum only orders intra-channel) | Genuine spec error — contradicts the doc's own §7 and the code since commit `b4b27ab` | Task 1 (done in spec) |
| 2 | SP2 design §4.4 wiring snippet shows `.channelTimeoutMs(200L)` | Superseded, not wrong — SP3 made the timeout the env-tunable builder default and dropped the call | Task 1 (forward-ref footnote) |
| 3 | `QuotaPolicy.defaultOnline()` looked like SP1 scope creep | False alarm — added by SP2 commit `b65fa1b`; the verifier read the merged tree | Task 3 (document only) |
| 4 | `RecallConfig.readLongEnv` parse path (`Long.parseLong`, trim, `NumberFormatException` fallback) is unit-untested; only the unset→default path is covered | Real coverage gap. The SP3 decision (no env-set test, since Java env is immutable at runtime) stands; but the **parse** logic is orthogonal to env immutability and is testable behind a pure seam | Task 2 |

## Global Constraints

- Java 17, Maven, JUnit 5, AssertJ. `mvn test -Dtest=RecallConfigTest` runs one class.
- **No production behavior change.** `readLongEnv` keeps identical semantics; the seam is a pure refactor + added test.
- Stay scoped to `RecallConfig`. `ShardedTopKStore.readLongEnv` parses inline too; leaving it as-is is deliberate — it is not in the notes and is out of scope here.

---

### Task 1: Reconcile SP2 design doc (DONE)

**File:** `docs/superpowers/specs/2026-06-18-online-recall-adoption-design.md`

- [x] §4.1 — rewrite the scoring sentence: blend weight `recencyBoost - rank` orders the channel; the emitted `MovieCandidate.score()` is rank-based `1.0/(rank+1)` (scale-matched to other channels for the quota gap-fill merge). Now consistent with §7 ("rank-based scores") and the code.
- [x] §4.4 — add a `> _Superseded by sub-project 3:_` footnote noting the explicit `.channelTimeoutMs(200L)` was dropped in favor of the env-tunable `RECALL_CHANNEL_TIMEOUT_MS` builder default.

---

### Task 2: Close the `readLongEnv` parse-path coverage gap

**Files:**
- Modify: `src/main/java/com/recsys/service/retrieval/multichannel/RecallConfig.java`
- Test: `src/test/java/com/recsys/service/retrieval/multichannel/RecallConfigTest.java` (extend)

**Interfaces:**
- Produces: package-private `static long RecallConfig.parseLongOrDefault(String raw, long defaultValue)` (pure; null/blank/`NumberFormatException` → default, else `Long.parseLong(raw.trim())`). `readLongEnv` delegates to it. Public/used API unchanged.

- [ ] **Step 1: Write the failing tests** — append to `RecallConfigTest.java`:

```java
    @Test
    void parseLongOrDefault_parsesValidAndTrims() {
        assertThat(RecallConfig.parseLongOrDefault("1500", 200L)).isEqualTo(1500L);
        assertThat(RecallConfig.parseLongOrDefault("  7  ", 200L)).isEqualTo(7L);
    }

    @Test
    void parseLongOrDefault_fallsBackOnNullBlankOrGarbage() {
        assertThat(RecallConfig.parseLongOrDefault(null, 200L)).isEqualTo(200L);
        assertThat(RecallConfig.parseLongOrDefault("", 200L)).isEqualTo(200L);
        assertThat(RecallConfig.parseLongOrDefault("   ", 200L)).isEqualTo(200L);
        assertThat(RecallConfig.parseLongOrDefault("abc", 200L)).isEqualTo(200L);
    }
```

- [ ] **Step 2: Run to verify it fails** — `mvn test -Dtest=RecallConfigTest` → COMPILATION FAILURE (`parseLongOrDefault` missing).

- [ ] **Step 3: Extract the seam in `RecallConfig`:**

```java
    static long readLongEnv(String name, long defaultValue) {
        return parseLongOrDefault(System.getenv(name), defaultValue);
    }

    static long parseLongOrDefault(String raw, long defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
```

- [ ] **Step 4: Run to verify it passes** — `mvn test -Dtest=RecallConfigTest` → PASS (existing 5 + 2 new).

---

### Task 3: Note the false-alarm + update SP3 testing note

- [ ] Update `docs/superpowers/specs/2026-06-18-recall-cleanup-design.md` §5: note that the `readLongEnv` parse branches are now unit-covered via the pure `parseLongOrDefault` seam (the env-set override remains documented-not-asserted because Java env is immutable at runtime — `ShardedTopKStoreTtlConfigTest` precedent).
- [ ] `defaultOnline()` scope concern is a verification false alarm (SP2 commit `b65fa1b`); recorded in the triage table above. No code change.

---

### Task 4: Verify

- [ ] `mvn -q -DskipTests compile` → BUILD SUCCESS.
- [ ] `mvn test -Dtest=RecallConfigTest` → PASS.
- [ ] `mvn test` full suite green (docs + pure-seam refactor cannot regress behavior; confirm).

## Self-Review

- Spec §4.1 / §4.4 reconciled → Task 1 (done).
- `readLongEnv` parse coverage → Task 2 (seam + 2 tests).
- `defaultOnline` false alarm + SP3 testing note → Task 3.
- No behavior change: `readLongEnv` output identical for every input; only an internal seam added.
