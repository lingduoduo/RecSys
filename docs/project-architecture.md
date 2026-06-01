# Project Architecture

The production code keeps the existing `com.recsys` package name, but the recommendation
path now has an explicit architecture slice under `com.recsys.recommendation`.

## Target Boundaries

| Layer | Package | Responsibility |
|---|---|---|
| Domain model | `recommendation.model` | Request, candidate, ranked item, and response records |
| Retrieval | `recommendation.service.retrieval` | Multi-channel recall from vector, collaborative, trending, or future channels |
| Ranking | `recommendation.service.ranking` | Candidate scoring and top-k ordering |
| Hydration | `recommendation.service.hydrator` | Metadata and feature enrichment after ranking |
| Pagination | `recommendation.service.pagination` | Cursor-safe paging over ranked lists |
| Orchestration | `recommendation.service.recommendation` | End-to-end recommendation pipeline coordination |
| Feedback | `recommendation.service.feedback` | Feedback capture and event publication contracts |

## Migration Path

1. Keep existing controllers and serving endpoints stable.
2. Adapt current retrieval, ranking, online, and model-serving implementations behind the new
   `RecallChannel`, `CandidateRanker`, `RecommendationHydrator`, and `EventPublisherService`
   interfaces.
3. Move Redis, vector index, model-serving, and tracing clients behind infrastructure packages
   only after callers depend on those interfaces.
4. Retire duplicate orchestration code once the Spring and Jetty paths call
   `RecommendationOrchestrator`.

This gives the codebase the shape of the proposed movie recommendation architecture without a
large package rename that would make review and rollout harder.
