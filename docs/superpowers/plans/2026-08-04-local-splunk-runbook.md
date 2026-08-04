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
