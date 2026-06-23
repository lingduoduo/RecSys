# Spec: Vector/Recall Micro-Optimizations (benchmark-gated) + dead-code cleanup

## Objective
Establish a benchmark for the recall hot path, then apply **only** the micro-optimizations that measurably help — avoiding speculative churn. Plus one unconditional cleanup (remove dead `VectorMath.cosine`). These were all marked "benchmark-driven follow-up" in the hot-path spec.

## Guiding principle
Low-impact micro-opts must be **measured, not assumed**. Land a JMH benchmark first; apply each candidate only if it moves a metric beyond noise. Anything that doesn't is dropped (and that's a successful outcome — it prevents wasted complexity).

## Scope

### A. Unconditional cleanup — remove dead `VectorMath.cosine`
File: `src/main/java/com/recsys/infrastructure/vectordb/VectorMath.java` (line 8).
Confirmed zero callers in `src/main` and `src/test`; the recall path uses `innerProduct`. Delete `cosine(float[],float[])`. Zero risk, no benchmark needed.

### B. Benchmark harness for the recall path
Add a JMH benchmark (new `src/jmh` or a `@Tag("bench")` JUnit harness excluded from the default Surefire run) covering `ExactVectorIndex.topK` / `LshVectorIndex.search` / `VectorMath.innerProduct` over a representative embedding set (sizes ~1k/10k/100k, k=10/50/100). Report ops/s and allocation rate (GC profiler). This harness gates C and D.

### C. Candidate micro-opts — apply each only if the benchmark shows a real gain
- `ExactVectorIndex.topK` (lines 52-53, 75-76): the min-heap is drained into a list then re-sorted O(k log k); replace with drain-then-reverse O(k) for descending order.
- `LshVectorIndex` (HashSet copy of candidates before the fallback decision): defer the `new HashSet<>(...)`/`removeAll` until after confirming LSH candidates are sufficient, so the full-scan fallback path skips the allocation.
- `EmbeddingLSH` bucket miss: avoid `List.of()` allocation on `getOrDefault` misses (null-check + shared empty list); evaluate whether Hamming-1 probing earns its cost on the real dataset.

### D. Cross-cutting time-source (separate sub-decision, benchmark-gated)
`System.currentTimeMillis()` is called many times per request across caches/guards (heaviest: `LogicalExpiryEmbeddingCache` ×10, `HotKeyDetector` ×7). A shared cached-time supplier (a volatile `long` refreshed every ~1ms by one daemon thread) could cut syscalls. **Only** pursue if the benchmark/profiler shows time calls are a measurable fraction of hot-path cost — otherwise drop (likely negligible). If pursued, do it as its own task with an injectable time source so tests stay deterministic.

## Out of Scope
- `VectorMath.innerProduct` is already 4-way unrolled — leave it.
- FAISS/native ANN backends.
- Speculative items the audit rated LOW that lack a hot-path case (LlmResponseCache body-clone, MultiLevel L1 LRU policy, AsyncEventPublisher adaptive batching, SingleFlight timeout, Bloom FP-rate tuning): list them in the benchmark report as "evaluated / not pursued" unless a profile elevates them. Do not implement blindly.

## Testing
- Existing `ExactVectorIndexTest`, `LshVectorIndexTest`, `EmbeddingLSHTest`, `VectorMathTest` must pass unchanged after C (behavior identical — same top-K, same order).
- For B: the benchmark compiles and runs locally; it is excluded from `mvn test` (tagged/separate source set) so CI time is unaffected.
- For A: full build green after deletion (no references).
- Each applied opt in C keeps the relevant unit test green and is committed with its before/after benchmark numbers in the message.

## Risks
- Benchmark noise → false positives. Mitigation: require a clear, repeatable delta (e.g. >5%) before landing; otherwise drop.
- Reverse-vs-sort must preserve exact ordering of ties — assert via existing index tests.

## Success
- Dead `cosine` removed; a runnable recall benchmark exists; only measured-beneficial micro-opts are merged (each with numbers); unhelpful candidates explicitly dropped. All existing tests green.
