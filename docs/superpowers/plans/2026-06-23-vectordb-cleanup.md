# Vectordb Cleanup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use `- [ ]`.

**Goal:** Remove dead code from `VectorMath`. Explicitly defer the speculative recall micro-optimizations until a benchmark justifies them (per the spec's measure-first principle).

**Tech Stack:** Java 17, JUnit 5 + AssertJ.

## Scope decision (honest outcome of the audit)
The Spec-5 audit items were mostly speculative micro-opts the spec said to **measure, not assume**. On inspection of current `main`:
- `VectorMath.cosine(...)` — **zero callers** in `src/main` and `src/test` → dead. Delete.
- `VectorMath.normSq(...)` — **zero callers** → dead. Delete.
- `ExactVectorIndex` dedup (instance `topK` vs static `search`) — the static `search` **is used** (`api/serving/RecommendationService:139`) and consolidating it would either add a per-call map copy (worse) or a non-trivial extraction. Not a safe free win → **not done**.
- Heap drain-reverse, LSH `HashSet` defer, `EmbeddingLSH` `List.of()` alloc, cross-cutting cached time-source — speculative; unproven benefit and some carry tie-order/behaviour risk → **deferred pending a JMH benchmark** (documented, not implemented). Standing up a CI JMH harness that nobody runs is more cost than the unproven wins justify.

So this PR is a focused dead-code removal. The deferred candidates are listed in the spec + PR body as a measured-first backlog.

## Global Constraints
- Behaviour-preserving (removing unreferenced code only). `mvn clean test` green.
- Branch `optimize/vectordb-cleanup` (spec already on branch).

---

### Task 1: Delete dead `VectorMath` methods

**Files:** Modify `src/main/java/com/recsys/infrastructure/vectordb/VectorMath.java`.

- [ ] **Step 1: Re-confirm zero callers**
Run: `grep -rn "VectorMath.cosine\|\.cosine(\|VectorMath.normSq\|\.normSq(" src/main src/test | grep -v VectorMath.java`
Expected: no output (both methods unreferenced).

- [ ] **Step 2: Delete the `cosine(float[],float[])` method and the `normSq(float[])` method** from `VectorMath.java`. Keep `innerProduct` and `parseVector` (both live).

- [ ] **Step 3: Compile + run the vectordb tests**
Run: `mvn -q clean test -Dtest='VectorMathTest,ExactVectorIndexTest,LshVectorIndexTest,EmbeddingLSHTest'`
Expected: PASS (no test referenced the deleted methods).

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/recsys/infrastructure/vectordb/VectorMath.java
git commit -m "refactor: remove dead VectorMath.cosine and normSq (zero callers)"
```

---

### Task 2: Full-suite verification
- [ ] `mvn clean test` → BUILD SUCCESS, 0 failures.

## Deferred (pending benchmark) — recorded, not implemented
- `ExactVectorIndex.topK`: drain min-heap + reverse instead of re-sort (O(k) vs O(k log k)) — beware tie-order; needs a test/benchmark.
- `LshVectorIndex`: defer the candidate `HashSet` copy to the fallback branch.
- `EmbeddingLSH`: avoid `List.of()` allocation on bucket miss; evaluate Hamming-1 probing value.
- Cross-cutting: shared cached time-source to cut `System.currentTimeMillis()` calls.
- `ExactVectorIndex` instance/static dedup (DRY) — only worth it if it doesn't add a per-call allocation.

## Self-Review
- Unconditional cleanup (delete dead cosine + normSq) → Task 1. ✓
- Speculative items explicitly deferred with rationale (no blind changes). ✓
- No placeholders; behaviour-preserving. ✓
