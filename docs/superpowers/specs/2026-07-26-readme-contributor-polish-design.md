# Contributor-First README Design

## Goal

Replace the repository's 2,311-line README with a concise contributor guide
that gets a local development environment running quickly and routes deeper
architecture and operational material to the existing documentation.

The primary reader is a contributor who has cloned the repository and needs to
build, test, run, inspect, and troubleshoot it locally.

## Scope

The change rewrites `README.md` only. Existing architecture, configuration,
system-design, and runbook documents remain authoritative and are linked rather
than duplicated.

The target length is approximately 400–700 lines. Clarity and correctness take
priority over reaching a specific line count.

## Information Architecture

The README will use this order:

1. Project purpose and four-service summary.
2. A five-minute quick start for the Docker-backed local stack.
3. Service ports and health endpoints.
4. Common contributor workflows:
   - build and deterministic tests;
   - start all services;
   - start one service;
   - stop and reset local infrastructure;
   - inspect logs and health.
5. Repository layout and where changes belong.
6. Configuration entry points and a short list of essential variables.
7. Testing profiles, including deterministic, load, and Docker boundaries.
8. Focused troubleshooting for the most likely local failures.
9. Curated documentation index for architecture, APIs, fault tolerance,
   configuration, Kubernetes, DR, and deeper subsystem investigations.
10. Contribution handoff: validation commands to run before opening a PR.

## Content Rules

- Commands must reflect files and scripts present on current `main`.
- Docker/Colima requirements must distinguish macOS from other environments.
- Expected health endpoints and ports must match the implemented services.
- The README must not promise that Redis failures always return `200`; it will
  distinguish data-path degradation from bounded emergency rate limiting.
- Deep API inventories, SQL examples, algorithm explanations, Kubernetes
  procedures, DR sequences, and subsystem investigations will be replaced by
  descriptive links to their authoritative documents.
- The quick-start path will avoid optional production infrastructure and
  credentials.
- Commands that delete/reset local state will be clearly labeled.
- Load and Docker tests will be presented as opt-in environmental suites, not
  ordinary pre-commit checks.
- Outdated transcript-style code/result samples will be removed. This includes
  large response dumps with fabricated or point-in-time counters, latency,
  utilization, failure rates, in-flight requests, thresholds, and suggested
  weights.
- A response example may remain only when it is short, stable, and verified
  against current production code or tests. Volatile health and operations
  responses will instead document the endpoint, status semantics, and stable
  field names, then link to the authoritative test or subsystem document.
- Commented sequences such as `// 200 — healthy` followed by several simulated
  `503` bodies will be replaced by a compact status/reason table when those
  states remain part of the current contract, or removed when they do not.

## Verification

The implementation will:

- verify every referenced local file and heading exists;
- check all README relative Markdown links;
- run the documented lightweight build/test or validation commands where the
  environment permits;
- compare retained request/response examples with current endpoint
  implementations and contract tests;
- scan for sampled numeric results and stale multi-response transcripts;
- scan for stale unlimited fail-open, obsolete scripts, and contradictory
  startup commands;
- run `git diff --check`;
- independently review the final README for contributor usability and technical
  accuracy.

## Non-Goals

- Redesigning production code, configuration, APIs, or deployment behavior.
- Rewriting the linked architecture and runbook documents.
- Adding screenshots, generated diagrams, badges, or marketing copy.
- Providing exhaustive API or subsystem documentation in the README.
