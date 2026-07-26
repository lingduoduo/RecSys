# Contributor-First README Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the oversized README with a concise local-contributor guide, consolidating useful detail into authoritative Markdown under `docs/` and making `CONFIG_GUIDE.md` the configuration source of truth.

**Architecture:** Treat `README.md` as a navigation and local-development surface, not a second architecture manual. Inventory every existing top-level section, merge unique useful content into the established topic owner, remove stale transcripts and sampled results, then rebuild README around one verified local workflow and a curated documentation map.

**Tech Stack:** GitHub-flavored Markdown, Maven, Docker Compose, shell scripts, repository-local Java services and tests.

## Global Constraints

- README target length is approximately 300–500 lines; clarity and correctness take priority over the line count.
- README keeps only content needed to build, run, test, troubleshoot, or navigate the repository locally.
- Useful detailed material moves into the existing authoritative `docs/` Markdown file before its README copy is removed.
- `CONFIG_GUIDE.md` is the single authoritative environment-variable reference.
- Retained response examples must be short, stable, and verified against current production code or contract tests.
- Remove transcript-style sampled counters, latency, utilization, failure-rate, in-flight, threshold, and suggested-weight values.
- Do not add screenshots, generated diagrams, badges, marketing copy, dependencies, or production behavior changes.
- Load and Docker tests remain opt-in environmental suites and must not be presented as normal pre-commit checks.

---

### Task 1: Build the README Migration Inventory and Consolidate Topic Owners

**Files:**

- Modify: `docs/system_design/03_DB_Sharding.md`
- Modify: `docs/system_design/08_Rate_Limits.md`
- Modify: `docs/system_design/09_API_Gateway.md`
- Modify: `docs/system_design/10_MicroServices.md`
- Modify: `docs/system_design/15_Eventual_Consistency.md`
- Modify: `docs/system_design/17_Scalability.md`
- Modify: `docs/system_design/18_Fault_Tolerance.md`
- Modify only when unique README content is absent: other existing `docs/system_design/*.md` or `docs/runbooks/*.md`
- Create: `docs/superpowers/plans/2026-07-26-readme-content-migration.md`

**Interfaces:**

- Consumes: all level-two and level-three headings from the current `README.md`.
- Produces: a migration table with columns `README section`, `Disposition`, `Destination`, and `Verification`.
- Produces: authoritative topic documents containing any useful details that existed only in README.

- [ ] **Step 1: Capture every README section**

Run:

```bash
rg -n '^#{2,3} ' README.md
```

Copy every heading into
`docs/superpowers/plans/2026-07-26-readme-content-migration.md`. Assign exactly
one disposition:

```text
KEEP — contributor workflow remains in README
MERGE — unique useful content moves to an existing Markdown owner
LINK — owner already covers the material; README keeps one descriptive link
REMOVE — stale, sampled, redundant, or non-contributor content
```

- [ ] **Step 2: Map known duplicated sections**

The inventory must at minimum map:

```text
SQL Use Cases / SQL Backend Patterns -> docs/system_design/13_DB_Indexing.md
Microservice Gateway -> docs/system_design/09_API_Gateway.md
Service Registry -> docs/system_design/11_Service_Discovery.md
Fault Tolerance -> docs/system_design/18_Fault_Tolerance.md
Sharded Record Store -> docs/system_design/03_DB_Sharding.md
Redis Read Replicas -> docs/system_design/04_Replication.md
Durable Eventual Consistency -> docs/system_design/15_Eventual_Consistency.md
Load Balancing -> docs/system_design/01_Load_Balancing.md
Capacity Planning -> docs/system_design/17_Scalability.md
LLM Gateway -> docs/system_design/09_API_Gateway.md and docs/system_design/16_SSE_Streaming.md
Model Rate Limiting -> docs/system_design/08_Rate_Limits.md
AWS Saga Orchestration -> docs/system_design/15_Eventual_Consistency.md
```

- [ ] **Step 3: Compare README detail with each owner**

For every `MERGE` row, use focused searches such as:

```bash
rg -n 'SQL Backend Patterns|seek pagination|delayed join|FORCE INDEX' README.md docs/system_design/13_DB_Indexing.md
rg -n 'MicroserviceGatewayServer|credential stripping|longest-prefix' README.md docs/system_design/09_API_Gateway.md
rg -n 'ShardedRecordStore|generation dual-read|reshard' README.md docs/system_design/03_DB_Sharding.md
```

Copy only missing, current facts into the destination document. Do not copy
sample outputs, duplicated introductions, or README-specific anchor links.

- [ ] **Step 4: Verify migrated facts against source**

For each added fact, identify a production class, deployment manifest, script,
or contract test in the migration table's `Verification` column. Examples:

```text
src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java
src/main/java/com/recsys/infrastructure/persistence/MillionScalePaginationSql.java
src/main/java/com/recsys/infrastructure/redis/ShardedRecordStore.java
scripts/dr-standby-capacity.sh
```

If the referenced file or symbol does not exist, remove the claim.

- [ ] **Step 5: Run document hygiene checks**

Run:

```bash
rg -n 'TBD|TODO|placeholder' docs/superpowers/plans/2026-07-26-readme-content-migration.md
git diff --check
```

Expected: no incomplete inventory rows and no whitespace errors.

- [ ] **Step 6: Commit**

```bash
git add docs/system_design docs/runbooks \
  docs/superpowers/plans/2026-07-26-readme-content-migration.md
git commit -m "docs: consolidate README design material"
```

---

### Task 2: Centralize Configuration in CONFIG_GUIDE.md

**Files:**

- Modify: `CONFIG_GUIDE.md`
- Reference: `README.md`
- Reference: `k8s/base/configmap.yaml`
- Reference: `src/main/java/com/recsys/config/EnvConfig.java`
- Reference: service startup/configuration classes found with `rg 'System.getenv|EnvConfig' src/main/java`

**Interfaces:**

- Consumes: per-service configuration tables currently duplicated in README.
- Produces: one authoritative guide organized into local essentials, service settings, resilience controls, security/integrations, and deployment-only settings.
- Produces: a stable README link target for advanced configuration.

- [ ] **Step 1: Inventory duplicate variables**

Run:

```bash
rg -o '`[A-Z][A-Z0-9_]+`' README.md CONFIG_GUIDE.md | sort
rg -n 'System.getenv|EnvConfig\\.' src/main/java
```

Record variables present only in README or described differently in both files.

- [ ] **Step 2: Verify defaults and strict parsing**

Check every changed entry against production parsing and
`k8s/base/configmap.yaml`. Explicitly verify:

```text
REDIS_HOST
REDIS_PORT
PORT
ONLINE_DEMO_PORT
SERVER_PORT
GATEWAY_PORT
ONLINE_REDIS_EMERGENCY_LIMIT_ENABLED
ONLINE_REDIS_EMERGENCY_RATE_PER_SECOND
ONLINE_REDIS_EMERGENCY_BURST
RECSYS_MAIN_CLASS
```

For emergency limiter settings, document invalid-value startup failure,
zero-value rollback, per-replica scope, and Redis-failure-only use.

- [ ] **Step 3: Reorganize the guide**

Use this order:

```text
Local development essentials
Service selection and ports
Redis and streaming infrastructure
Recommendation and model serving
Gateway, authentication, and LLM integration
Resilience and overload controls
Observability
Deployment-only settings
```

Avoid repeating the same variable in multiple tables; use cross-links within
the guide where one setting affects multiple services.

- [ ] **Step 4: Check completeness and contradictions**

Run:

```bash
rg -n 'ONLINE_REDIS_EMERGENCY|RECSYS_MAIN_CLASS|REDIS_HOST|GATEWAY_PORT' CONFIG_GUIDE.md
rg -n 'unlimited|always returns 200|parse-with-default' CONFIG_GUIDE.md
git diff --check
```

Expected: required entries are present; no unlimited fail-open or silent
invalid-value claim remains.

- [ ] **Step 5: Commit**

```bash
git add CONFIG_GUIDE.md
git commit -m "docs: centralize runtime configuration"
```

---

### Task 3: Rewrite README as the Local Contributor Entry Point

**Files:**

- Modify: `README.md`
- Reference: `docker-compose.streaming.yml`
- Reference: `scripts/run-microservices-local.sh`
- Reference: `pom.xml`
- Reference: service main classes and health contract tests

**Interfaces:**

- Consumes: Task 1 migration inventory and Task 2 authoritative configuration guide.
- Produces: a 300–500 line README organized around local contributor workflows.

- [ ] **Step 1: Replace the top-level structure**

Write these sections in order:

```text
# RecSys Backend Service
## What runs locally
## Prerequisites
## Five-minute quick start
## Services and health checks
## Common contributor workflows
## Testing
## Repository layout
## Configuration
## Troubleshooting
## Documentation map
## Before opening a pull request
```

Do not recreate a separate architecture-layers or system-design catalog above
the quick start.

- [ ] **Step 2: Write the five-minute path**

Use only verified commands:

```bash
docker compose -f docker-compose.streaming.yml up -d
mvn package -DskipTests
sh scripts/run-microservices-local.sh
curl --fail http://localhost:8010/health
```

Document Colima as a macOS option, not a universal prerequisite. State where
logs are written and provide the repository's actual stop/reset commands. Mark
any destructive local-volume reset clearly.

- [ ] **Step 3: Keep a compact service table**

For ports `6010`, `7010`, `8080`, and `8010`, list:

```text
service name | main class/start command | health endpoint | purpose
```

Verify each main class and endpoint with `rg`. Avoid response dumps containing
sampled counters or timestamps.

- [ ] **Step 4: Replace volatile response transcripts**

Remove blocks such as:

```text
// 200 — healthy
{"recentRequests":42,"recentFailureRate":0.02,...}
// 503 — model not yet loaded
...
```

If the contract remains useful, replace it with:

```text
status | stable reason | contributor action
```

Verify reasons against current tests or response-building source. Otherwise,
link to the owning health/load document.

- [ ] **Step 5: Document testing boundaries**

Include:

```bash
mvn --batch-mode validate
mvn --batch-mode -Presilience test
mvn --batch-mode test
```

Describe `load` and `docker` groups as opt-in:

```bash
mvn --batch-mode test -DexcludedGroups=docker -Dgroups=load
mvn --batch-mode test -DexcludedGroups=load -Dgroups=docker
```

Link scheduled evidence details to
`docs/system_design/18_Fault_Tolerance.md` and the resilience workflows.

- [ ] **Step 6: Add concise navigation**

The documentation map must link, without duplicating their contents, to:

```text
CONFIG_GUIDE.md
docs/system_design/README.md
docs/system_design/09_API_Gateway.md
docs/system_design/10_MicroServices.md
docs/system_design/18_Fault_Tolerance.md
docs/runbooks/overload-protection.md
docs/runbooks/dr-regional-failover.md
docs/runbooks/dr-failback.md
docs/runbooks/dr-game-day.md
docs/ml/
```

If `docs/system_design/README.md` or `docs/ml/` does not exist, link to the
actual existing index/path discovered with `rg --files docs`; do not invent it.

- [ ] **Step 7: Enforce the size and duplication budget**

Run:

```bash
wc -l README.md
rg -n '^## (SQL Use Cases|Microservice Gateway|Sharded Record Store|Redis Read Replicas|Durable Eventual Consistency|Load Balancing|Capacity Planning|Pipeline Optimizations|AWS Saga Orchestration)$' README.md
rg -n 'recentRequests.*42|recentFailureRate.*0\\.02|recentAvgLatencyMs.*11\\.4|inFlightRequests.*64' README.md
```

Expected: 300–500 lines; no duplicated deep-design headings; no sampled
transcript values.

- [ ] **Step 8: Commit**

```bash
git add README.md
git commit -m "docs: make README contributor-first"
```

---

### Task 4: Verify Links, Commands, and Contributor Usability

**Files:**

- Modify only for verification corrections: `README.md`, `CONFIG_GUIDE.md`, and Task 1 destination documents

**Interfaces:**

- Consumes: the rewritten README and consolidated docs.
- Produces: evidence that links resolve, commands reference real files/classes, retained examples match current contracts, and the branch is clean.

- [ ] **Step 1: Validate local Markdown links**

Run a repository-local script or shell loop that:

```text
extracts relative Markdown targets from README.md and CONFIG_GUIDE.md
strips anchors
URL-decodes paths
fails when a non-HTTP target does not exist
```

Expected: every local target exists.

- [ ] **Step 2: Validate documented files and main classes**

Run:

```bash
test -f docker-compose.streaming.yml
test -x scripts/run-microservices-local.sh
rg -n 'class (RecSysServer|OnlinePredictionServer|MicroserviceGatewayServer)' src/main/java
rg -n 'spring-boot-maven-plugin|maven-surefire-plugin|<id>resilience</id>' pom.xml
```

Expected: all commands and classes referenced by README exist.

- [ ] **Step 3: Validate current contracts**

Search retained status/reason text and endpoints in production/tests:

```bash
rg -n '/health|/health/ready|/online/ops|shutting down|overloaded|high failure rate' \
  src/main/java src/test/java
```

Remove or correct any README contract that cannot be verified.

- [ ] **Step 4: Run lightweight project verification**

Run:

```bash
mvn --batch-mode validate
git diff --check
git status --short
```

If environment permits, also run:

```bash
mvn --batch-mode -Presilience test
```

Record sandbox/JVM-attach limitations exactly; do not claim an unexecuted or
environment-blocked suite passed.

- [ ] **Step 5: Review the migration inventory**

Confirm every removed level-two/level-three README section has a completed
`KEEP`, `MERGE`, `LINK`, or `REMOVE` row and that every `MERGE` destination is
part of the branch diff.

- [ ] **Step 6: Commit verification corrections**

If verification changed files:

```bash
git add README.md CONFIG_GUIDE.md docs
git commit -m "docs: verify contributor documentation"
```

If no corrections were needed, record that fact in the task report without an
empty commit.
