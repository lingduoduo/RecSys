# Local Splunk Runbook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Splunk HEC runbook's local section into a complete, copy-pasteable workflow for x86_64 hosts and Apple Silicon Macs using Colima with Rosetta.

**Architecture:** Keep the existing single runbook and replace its fragmented local bring-up guidance with a linear happy path followed by symptom-oriented recovery. Preserve the delivery-contract, EKS, tuning, diagnostics, and production-divergence material outside the local section.

**Tech Stack:** Markdown, Docker Compose v2, Colima 0.10+, macOS VZ/Rosetta, Splunk HEC, POSIX shell commands.

## Global Constraints

- Genuine x86_64 remains the reliable reference environment.
- Apple Silicon with Rosetta is explicitly best-effort and may still fail in Splunk KVStore/indexing.
- `/tmp/recsys-splunk.env` is the canonical local credential source for every Compose command.
- Never recommend `--remove-orphans`; those containers may belong to other repository Compose stacks.
- State explicitly before `down -v` that it deletes local indexes and Splunk configuration.
- Never print credentials unless the command's purpose is explicitly credential retrieval for local login.

---

### Task 1: Rewrite and verify local Splunk operations

**Files:**
- Modify: `docs/runbooks/splunk-hec-logging.md`
- Reference: `docker-compose.splunk.yml`
- Reference: `docker/splunk/init.sh`
- Reference: `scripts/run-microservices-local.sh`
- Reference: `docs/superpowers/specs/2026-08-04-local-splunk-runbook-design.md`

**Interfaces:**
- Consumes: Compose services `splunk` and `splunk-init`, published ports `8000` and `8088`, and environment variables `SPLUNK_PASSWORD` and `SPLUNK_HEC_TOKEN`.
- Produces: A single local workflow whose commands consistently reuse `/tmp/recsys-splunk.env` and whose search examples target `index=recsys` and `sourcetype="recsys:app:log"`.

- [x] **Step 1: Replace the local support-boundary introduction**

Document both paths at the beginning of `## Local bring-up`: x86_64 Docker is the
supported reference path and Apple Silicon uses a dedicated Colima VZ/Rosetta profile
as a best-effort path. Include:

```bash
uname -m
docker context show
docker info
```

- [x] **Step 2: Add Apple Silicon Colima/Rosetta setup**

Add the isolated-profile commands and state that the final command must print `x86_64`:

```bash
/usr/sbin/softwareupdate --install-rosetta --agree-to-license
colima start splunk --vm-type vz --vz-rosetta --runtime docker --cpu 4 --memory 8 --disk 60
docker context use colima-splunk
docker run --rm --platform linux/amd64 alpine uname -m
```

- [x] **Step 3: Add persistent credential generation and startup**

Use a mode-restricted env file and make every Compose command pass it:

```bash
umask 077
printf 'SPLUNK_PASSWORD=%s\nSPLUNK_HEC_TOKEN=%s\n' \
  "$(openssl rand -hex 18)" \
  "$(uuidgen)" \
  > /tmp/recsys-splunk.env

docker compose --env-file /tmp/recsys-splunk.env \
  -f docker-compose.splunk.yml up -d
```

Explain normal first-boot timing and warn that the orphan message is informational;
do not recommend `--remove-orphans`.

- [x] **Step 4: Add readiness and UI-login checks**

Document:

```bash
docker compose --env-file /tmp/recsys-splunk.env \
  -f docker-compose.splunk.yml ps
docker logs -f splunk-init
curl --fail --max-time 10 http://localhost:8000/ >/dev/null
sed -n 's/^SPLUNK_PASSWORD=//p' /tmp/recsys-splunk.env
open http://localhost:8000
```

Expected initialization text is `HEC is accepting events over plain HTTP. Ready.`;
the UI username is `admin`.

- [x] **Step 5: Add application and end-to-end HEC verification**

Load credentials and start services:

```bash
set -a
. /tmp/recsys-splunk.env
set +a
sh scripts/run-microservices-local.sh
```

Include a direct HEC POST expecting `{"text":"Success","code":0}`, an application
health request, and searches for the manual event and counts by service source.

- [x] **Step 6: Add symptom-oriented local troubleshooting**

Cover missing Compose variables, missing Colima socket, a long `Waiting` state,
misleading health while port 8000 is unavailable, credential mismatch, and ARM signal
11/KVStore failures. Include this explicitly local credential-recovery command:

```bash
docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' splunk \
  | sed -n 's/^SPLUNK_PASSWORD=//p'
```

- [x] **Step 7: Add cleanup and verification**

Provide stop and destructive reset separately:

```bash
docker compose --env-file /tmp/recsys-splunk.env \
  -f docker-compose.splunk.yml down
docker compose --env-file /tmp/recsys-splunk.env \
  -f docker-compose.splunk.yml down -v
docker ps -a --filter name=splunk
```

State before `down -v` that it permanently removes local Splunk indexes and
configuration. Include placeholder variables only for cleanup after the env file is gone.

- [x] **Step 8: Verify the documentation**

Run:

```bash
rg -n 'docker-compose.splunk.yml' docs/runbooks/splunk-hec-logging.md
rg -n -- '--remove-orphans|SPLUNK_PASSWORD|SPLUNK_HEC_TOKEN|colima-splunk|localhost:8000' \
  docs/runbooks/splunk-hec-logging.md
git diff --check
```

Expected: local Compose examples use the env file except the explicitly documented
missing-env cleanup fallback; `--remove-orphans` appears only in a warning not to use it;
`git diff --check` emits no output.

- [x] **Step 9: Commit the runbook update**

```bash
git add docs/runbooks/splunk-hec-logging.md
git commit -m "docs: expand local Splunk runbook"
```

### Task 2: Add a contextual README entry point

**Files:**
- Modify: `README.md`
- Reference: `docs/runbooks/splunk-hec-logging.md`
- Test: `src/test/java/com/recsys/docs/DocumentationIndexTest.java`

**Interfaces:**
- Consumes: The existing **Start the artifact-dependent full stack** contributor workflow.
- Produces: One contextual link to `docs/runbooks/splunk-hec-logging.md`, while retaining the existing complete runbook-index link.

- [x] **Step 1: Add the contextual link**

Immediately after the full-stack startup command, add a short paragraph stating that
Splunk log shipping is optional and linking to the runbook for local collector, web UI,
credentials, HEC verification, troubleshooting, and cleanup. Do not duplicate commands
from the runbook or add a new top-level README section.

- [x] **Step 2: Verify documentation links and formatting**

Run:

```bash
mvn --batch-mode test -Dtest=DocumentationIndexTest -DexcludedGroups=load,docker
git diff --check
```

Expected: `DocumentationIndexTest` passes 2/2 and `git diff --check` emits no output.

- [x] **Step 3: Commit and publish to the existing PR branch**

```bash
git add -- README.md docs/superpowers/plans/2026-08-04-local-splunk-runbook.md
git commit -m "docs: link local Splunk setup from README"
git push origin docs/local-splunk-runbook
```

### Task 3: Integrate validated local troubleshooting and search workflows

**Files:**
- Modify: `docs/runbooks/splunk-hec-logging.md`
- Modify: `docs/superpowers/plans/2026-08-04-local-splunk-runbook.md`
- Reference: `scripts/run-microservices-local.sh`
- Reference: `docker-compose.streaming.yml`
- Test: `src/test/java/com/recsys/docs/DocumentationIndexTest.java`

**Interfaces:**
- Consumes: `/tmp/recsys-splunk.env`, the running `splunk` container environment,
  HEC endpoints on port 8088, streaming infrastructure from
  `docker-compose.streaming.yml`, and host services on ports 6010, 7010, 8080, and
  8010.
- Produces: One linear, validated workflow from Splunk startup through backend log
  searches, with recovery steps for credential drift and stale host processes.

- [x] **Step 1: Add credential recovery and HEC validation**

After the stable-credential warning, explain that HTTP 403 or a rejected UI login can
mean the env file was regenerated while initialized volumes were retained. Recover both
values from the local container and recreate the restricted env file:

```bash
export SPLUNK_PASSWORD="$(
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' splunk |
  sed -n 's/^SPLUNK_PASSWORD=//p'
)"
export SPLUNK_HEC_TOKEN="$(
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' splunk |
  sed -n 's/^SPLUNK_HEC_TOKEN=//p'
)"
umask 077
printf 'SPLUNK_PASSWORD=%s\nSPLUNK_HEC_TOKEN=%s\n' \
  "$SPLUNK_PASSWORD" "$SPLUNK_HEC_TOKEN" > /tmp/recsys-splunk.env
```

Validate HEC before application startup:

```bash
curl --fail http://localhost:8088/services/collector/health
```

Expected body: `{"text":"HEC is healthy","code":17}`. Retain the direct event POST
and its expected `{"text":"Success","code":0}` response as the token check.

- [x] **Step 2: Replace the application startup subsection with explicit gates**

Document streaming dependency startup first:

```bash
docker compose -f docker-compose.streaming.yml up -d
```

Then load the stable credentials and required development-only values:

```bash
set -a
. /tmp/recsys-splunk.env
set +a
export RECOMMENDATION_CURSOR_SIGNING_KEY="$(openssl rand -hex 32)"
export GATEWAY_ALLOW_ANONYMOUS=true
export SPLUNK_HEC_URL="http://localhost:8088/services/collector/event"
printf 'HEC token=%s signing key=%s anonymous=%s\n' \
  "${SPLUNK_HEC_TOKEN:+SET}" \
  "${RECOMMENDATION_CURSOR_SIGNING_KEY:+SET}" \
  "$GATEWAY_ALLOW_ANONYMOUS"
```

The expected check is `HEC token=SET signing key=SET anonymous=true`; it must not print
either secret. Check ports 6010, 7010, 8080, and 8010 with `lsof` before running
`sh scripts/run-microservices-local.sh`, and explain that a listener left by an earlier
run must be stopped before relaunching.

- [x] **Step 3: Add service monitoring and health verification**

Keep `scripts/run-microservices-local.sh` in the foreground. In a second terminal,
document:

```bash
tail -f logs/recsys-serving.log logs/online-serving.log \
  logs/model-serving.log logs/api-gateway.log
```

Check every service independently:

```bash
curl http://localhost:6010/health
curl http://localhost:7010/health
curl http://localhost:8080/health/ready
curl http://localhost:8010/health
```

State that one healthy endpoint does not prove the script completed, and mention that
model serving can fail when required local model artifacts are absent.

- [x] **Step 4: Expand the local search cookbook**

Add copy-pasteable searches for all events, one service, counts by source, counts by
source and level, recent warnings/errors, a message fragment, a one-minute time chart,
and exceptions. Every query uses `index=recsys sourcetype="recsys:app:log"`. Retain the
warning that only model serving currently supplies `traceId`.

- [x] **Step 5: Consolidate overlapping troubleshooting**

Move the existing password-only recovery guidance into one credential-mismatch section
that covers both UI login failure and HEC `403 Invalid token`. Keep destructive
`down -v` as the fallback only when the running container credentials cannot be used or
a clean reset is intended.

- [x] **Step 6: Verify and publish the revision**

Run:

```bash
rg -n 'collector/health|RECOMMENDATION_CURSOR_SIGNING_KEY|GATEWAY_ALLOW_ANONYMOUS|docker-compose.streaming.yml|lsof -nP|logs/recsys-serving.log|stats count by source level' docs/runbooks/splunk-hec-logging.md
mvn --batch-mode test -Dtest=DocumentationIndexTest -DexcludedGroups=load,docker
git diff --check
```

Expected: every new workflow marker is present, `DocumentationIndexTest` passes 2/2,
and `git diff --check` emits no output. Mark Task 3 complete, commit the runbook and plan,
and push `docs/local-splunk-runbook` to the existing PR.
