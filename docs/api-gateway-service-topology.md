# API Gateway Service Topology

The API gateway is the stable public edge. Domain services sit behind it and can be
split into independent deployments without changing client URLs.

```text
Client
  |
  v
API Gateway
  |-- /api/users          -> User Profile Service
  |-- /api/movies         -> Movie Metadata Service
  |-- /api/features       -> Feature Service
  |-- /api/retrieval      -> Recommendation Retrieval Service
  |-- /api/ranking        -> Ranking Service
  |-- /api/explanations   -> LLM Explanation Service
  |-- /api/agents         -> Agent Workflow Service
  `-- /api/observability  -> Observability Service
```

## Route Ownership

| Gateway prefix | Service | URL env var | Current backing service |
|---|---|---|---|
| `/api/users` | User Profile Service | `USER_PROFILE_SERVICE_URL` | catalog-serving |
| `/api/movies` | Movie Metadata Service | `MOVIE_METADATA_SERVICE_URL` | catalog-serving |
| `/api/features` | Feature Service | `FEATURE_SERVICE_URL` | online-serving |
| `/api/retrieval` | Recommendation Retrieval Service | `RECOMMENDATION_RETRIEVAL_SERVICE_URL` | model-serving |
| `/api/ranking` | Ranking Service | `RANKING_SERVICE_URL` | model-serving |
| `/api/explanations` | LLM Explanation Service | `LLM_EXPLANATION_SERVICE_URL` | LLM/Ollama-compatible endpoint |
| `/api/agents` | Agent Workflow Service | `AGENT_WORKFLOW_SERVICE_URL` | model-serving placeholder |
| `/api/observability` | Observability Service | `OBSERVABILITY_SERVICE_URL` | model-serving health/metrics |

The legacy prefixes `/api/catalog`, `/api/model`, `/api/online`, and `/api/llm`
remain registered for compatibility. New clients should prefer the domain-facing
prefixes above.

## Split Strategy

The repo intentionally keeps shared data models, Redis helpers, retrieval logic,
and ONNX serving code in place while the gateway route map defines service
boundaries. When a domain becomes independently deployable:

1. Create the new service entrypoint and Kubernetes Deployment.
2. Point the corresponding `*_SERVICE_URL` to the new Kubernetes Service.
3. Keep the gateway prefix stable so clients do not change.
4. Move implementation code after the HTTP contract is stable.

This keeps the repository useful as a modular monolith today while making the
API gateway the only client-facing dependency as services are split out.
