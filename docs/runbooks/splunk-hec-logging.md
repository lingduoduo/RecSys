# Splunk HEC logging

All four service mains — RecSys Serving API (6010), Model Serving (8080), Online Serving
(7010), API Gateway (8010) — can ship their structured JSON log events straight to
Splunk's HTTP Event Collector (HEC), in addition to the console/`logs/*.log` output they
already produce. It is off unless `SPLUNK_HEC_TOKEN` is set: with no token,
`SplunkHecAppender` starts, does nothing, and every request path is unaffected. Console
logging is never gated on this — the `CONSOLE` and `SPLUNK` appenders are attached
side by side in `src/main/resources/logback-common.xml`, included by the single
`src/main/resources/logback.xml` that all four mains load (see
["A Logback constraint"](#a-logback-constraint) below for why there is no separate
Spring config).

Design: `docs/superpowers/specs/2026-07-31-splunk-hec-log-shipping-design.md`

**Side effect: the three Armeria mains now run at INFO, not DEBUG.** Before this
feature, RecSys Serving (6010), Online Serving (7010), and API Gateway (8010) had no
Logback configuration at all and fell back to Logback's built-in `BasicConfigurator`,
which attaches a console appender to root at DEBUG. `logback-common.xml`'s root logger
is INFO, so this change also raises those three services' effective log level.
Almost certainly an improvement — none of this repo's own packages issue `log.debug`
calls on their hot paths, so nothing first-party is lost — but it is a real,
previously-undocumented operational change that ships alongside the Splunk feature.

## A Logback constraint

There is only one Logback config file in this repo, `src/main/resources/logback.xml`,
and it governs all four service mains — including the Spring Boot model service on
8080, not just the three Armeria mains that run outside Spring entirely.

That is not an oversight; it is forced by how Spring Boot resolves its logging config.
`AbstractLoggingSystem.getSelfInitializationConfig()` scans for **standard-location**
files first — `logback-test.xml`, then `logback.xml` — and returns as soon as it finds
one, *before* it ever looks for a `-spring` variant such as `logback-spring.xml`.
Because `logback.xml` exists on the classpath, Spring Boot finds it on that first pass
and never reaches the `-spring` lookup at all. A `logback-spring.xml` file would
therefore be unreachable dead code, which is why this repo has none — it was deleted
during implementation once this precedence was confirmed (see commit `793067f`).

The practical consequence: **Spring-only Logback tags — `<springProfile>`,
`<springProperty>` — cannot be used anywhere in this repo's Logback config.** They only
have any effect inside a `-spring` file, and no `-spring` file is ever loaded while
`logback.xml` is present. If you need Spring-profile-aware logging configuration, it has
to happen in a different phase (e.g. driven by a plain environment variable read inside
`SplunkHecConfig` or an equivalent class), not via a Logback tag. Someone will eventually
reach for `<springProfile>` inside `logback-common.xml` or `logback.xml`; it will parse,
Joran will silently ignore it outside a `-spring` file, and the intended behavior will
simply never happen. This section exists so that person finds the answer before losing
an afternoon to it.

## The delivery contract: at-most-once

`SplunkHecAppender` is shaped like `AsyncEventPublisher`: a bounded in-memory queue, one
daemon drain thread, batched HTTP POSTs, and **drop-on-full rather than block**. Logs are
diagnostics, not the system of record — a Splunk outage or a burst of log volume must
degrade to console-only, never stall a serving thread or grow the heap without bound.

Concretely, an event is dropped and never retried when:
- the bounded queue (`SPLUNK_HEC_QUEUE_CAPACITY`, default 10,000) is full, or
- the HTTP POST to Splunk fails for any reason (connection refused, timeout, TLS
  failure, a non-2xx response) — `SplunkHecClient.send` never throws and never retries.

**A Splunk search result is therefore a lower bound on what was logged, not a complete
record.** `stdout` / `logs/*.log` (the `CONSOLE` appender, which is never dropped or
gated) is the authoritative copy. State this plainly to anyone who wants to use a Splunk
`count` as evidence of anything — it can only ever undercount, never overcount.

## Local bring-up

The reliable reference environment is Docker on a genuine x86_64 host. The repository's
real-Splunk integration test uses that environment in CI. Apple Silicon can also be useful
for local development when Colima runs the amd64 image through VZ/Rosetta, but that path is
**best-effort**: Splunk has also crashed in indexing/KVStore under emulation. The procedure
below makes the Mac path reproducible without claiming that every Splunk image or macOS
release will run it successfully.

[`SplunkHecIntegrationTest`](../../src/test/java/com/recsys/infrastructure/observability/SplunkHecIntegrationTest.java)
boots a real Splunk, runs **this exact `docker/splunk/init.sh`** inside it, ships events
through a real `SplunkHecAppender`, and asserts they come back from Splunk's search API
with the right `source`, `sourcetype`, `index`, level, and MDC fields. It runs on
`ubuntu-latest` (x86_64) via
[`.github/workflows/splunk-hec-integration.yml`](../../.github/workflows/splunk-hec-integration.yml)
whenever anything under the appender, the Logback configs, the compose file, or
`docker/splunk/` changes — and weekly through `resilience-scheduled.yml`'s `docker` job.
Check that workflow's most recent run for the current status.

### Check the host and Docker daemon

Run all commands from the repository root. First identify the host architecture and make
sure the intended Docker provider is running:

```bash
uname -m
docker context show
docker info
```

`uname -m` prints `x86_64` on the reliable reference path and `arm64` on an Apple Silicon
Mac. If `docker info` reports a missing `~/.colima/.../docker.sock`, that Docker context
points to a stopped Colima profile. Start the intended profile or select the context that
owns the containers; switching contexts does not move containers or volumes between Docker
daemons.

### Apple Silicon: create a Rosetta-enabled Colima profile

Skip this subsection on a genuine x86_64 host. On Apple Silicon, use a separate profile so
the experiment does not replace the repository's default Colima VM or its Redis/Kafka/Flink
containers:

```bash
/usr/sbin/softwareupdate --install-rosetta --agree-to-license
colima start splunk \
  --vm-type vz \
  --vz-rosetta \
  --runtime docker \
  --cpu 4 \
  --memory 8 \
  --disk 60
docker context use colima-splunk
docker run --rm --platform linux/amd64 alpine uname -m
```

The last command must print `x86_64`. Colima 0.10 or newer exposes the `--vz-rosetta`
option. The Compose file also pins the Splunk container to `platform: linux/amd64`.

### Create stable local credentials

Compose validates its required variables even for commands such as `ps` and `down`. Keep
one credential pair in a permission-restricted temporary env file and pass it to every
Compose command:

```bash
umask 077
printf 'SPLUNK_PASSWORD=%s\nSPLUNK_HEC_TOKEN=%s\n' \
  "$(openssl rand -hex 18)" \
  "$(uuidgen)" \
  > /tmp/recsys-splunk.env

cut -d= -f1 /tmp/recsys-splunk.env
```

The check prints only the two variable names, not their values:

```text
SPLUNK_PASSWORD
SPLUNK_HEC_TOKEN
```

Do not regenerate this file while reusing existing `splunk-etc` and `splunk-var` volumes.
The admin password and HEC token are established during volume initialization; changing a
later shell variable does not change the credentials stored by Splunk.

#### Credential mismatch recovery

A rejected UI login or HEC `403 Invalid token` usually means this env file was
regenerated while initialized volumes were retained. This recovery is only for **file-only
drift** while the original container has not been recreated: it exports both secrets into
the current shell, so use it only in a trusted local session. It does not print either
value or replace the existing env file unless inspection and extraction both succeed:

```bash
splunk_env="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' splunk)" || {
  printf '%s\n' 'Could not inspect the running splunk container; keeping the existing env file.' >&2
  exit 1
}
SPLUNK_PASSWORD="$(printf '%s\n' "$splunk_env" | sed -n 's/^SPLUNK_PASSWORD=//p')"
SPLUNK_HEC_TOKEN="$(printf '%s\n' "$splunk_env" | sed -n 's/^SPLUNK_HEC_TOKEN=//p')"
if [ -z "$SPLUNK_PASSWORD" ] || [ -z "$SPLUNK_HEC_TOKEN" ]; then
  printf '%s\n' 'Missing Splunk credentials on the running container; keeping the existing env file.' >&2
  exit 1
fi
export SPLUNK_PASSWORD SPLUNK_HEC_TOKEN
tmp_splunk_env="$(mktemp /tmp/recsys-splunk.env.XXXXXX)" || exit 1
if ! (umask 077; printf 'SPLUNK_PASSWORD=%s\nSPLUNK_HEC_TOKEN=%s\n' \
  "$SPLUNK_PASSWORD" "$SPLUNK_HEC_TOKEN" > "$tmp_splunk_env"); then
  rm -f "$tmp_splunk_env"
  exit 1
fi
if ! chmod 600 "$tmp_splunk_env"; then
  rm -f "$tmp_splunk_env"
  exit 1
fi
case "$(uname -s)" in
  Darwin|FreeBSD|NetBSD|OpenBSD)
    if ! mv -h "$tmp_splunk_env" /tmp/recsys-splunk.env; then
      rm -f "$tmp_splunk_env"
      printf '%s\n' 'Could not safely replace the env file; keeping the existing target.' >&2
      exit 1
    fi
    ;;
  Linux)
    if ! mv -T "$tmp_splunk_env" /tmp/recsys-splunk.env; then
      rm -f "$tmp_splunk_env"
      printf '%s\n' 'Could not safely replace the env file; keeping the existing target.' >&2
      exit 1
    fi
    ;;
  *)
    rm -f "$tmp_splunk_env"
    printf '%s\n' 'Unsupported platform for safe env-file replacement; keeping the existing target.' >&2
    exit 1
    ;;
esac
```

If the container was recreated after credentials changed, `docker inspect` can return
those new invalid values instead of the credentials stored in the retained volumes. Run
the direct HEC event test below after recovery to distinguish that case. A persistent
`403 Invalid token` requires the documented clean `down -v` reset; it deletes the local
indexes and Splunk configuration.

### Start Splunk and wait for HEC

```bash
docker compose --env-file /tmp/recsys-splunk.env \
  -f docker-compose.splunk.yml up -d
```

First boot normally takes 1–3 minutes and can take longer under emulation. `up -d` waits
because `splunk-init` depends on the Splunk health check. A warning about orphan Redis,
Kafka, ZooKeeper, or Flink containers is informational: those containers belong to the
repository's other Compose stack. **Do not add `--remove-orphans`**, or Compose may delete
local infrastructure that the services still need.

In another terminal, check progress without reparsing the Compose file:

```bash
docker ps -a --filter name=splunk \
  --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
docker logs -f splunk
```

Stop following a log with Ctrl-C; that does not stop the container. Once Splunk is healthy,
follow the one-shot provisioner:

```bash
docker logs -f splunk-init
```

Wait for:

```text
HEC is accepting events over plain HTTP. Ready.
```

The Compose view should then show Splunk healthy and `splunk-init` exited successfully:

```bash
docker compose --env-file /tmp/recsys-splunk.env \
  -f docker-compose.splunk.yml ps -a
```

### Open and log into the Splunk UI

Verify the web endpoint before opening it:

```bash
curl --fail --max-time 10 http://localhost:8000/ >/dev/null && \
  echo "Splunk UI is ready"
```

The following command deliberately prints the local UI password:

```bash
sed -n 's/^SPLUNK_PASSWORD=//p' /tmp/recsys-splunk.env
```

Open `http://localhost:8000` (on macOS, `open http://localhost:8000`) and sign in with:

```text
Username: admin
Password: output from the previous command
```

### Verify HEC directly

Load the same credentials into the current shell:

```bash
set -a
. /tmp/recsys-splunk.env
set +a
```

Validate that HEC is healthy before starting the applications:

```bash
curl --fail --show-error --max-time 10 http://localhost:8088/services/collector/health
```

Expected response:

```json
{"text":"HEC is healthy","code":17}
```

Send one event without involving the application:

```bash
curl --fail --show-error --max-time 10 \
  -H "Authorization: Splunk ${SPLUNK_HEC_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data '{"event":{"level":"INFO","message":"manual UI verification"},"index":"recsys","sourcetype":"recsys:app:log","source":"manual-test"}' \
  http://localhost:8088/services/collector/event
```

Expected response:

```json
{"text":"Success","code":0}
```

In **Search & Reporting**, set the time picker to **All time** if necessary and run:

```spl
index=recsys source="manual-test" "manual UI verification"
```

### Start the services, verify readiness, and search their logs

Start the streaming dependencies first:

```bash
docker compose -f docker-compose.streaming.yml up -d
```

This independent Compose file does not consume Splunk credentials, so it does not need
`/tmp/recsys-splunk.env`.

Load the stable credentials and the local-development values in the shell used to start
the services:

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

The check must print the following without exposing either secret:

```text
HEC token=SET signing key=SET anonymous=true
```

Before launching, make sure an earlier run did not leave a listener on any service port:

```bash
lsof -nP -iTCP:6010 -sTCP:LISTEN
lsof -nP -iTCP:7010 -sTCP:LISTEN
lsof -nP -iTCP:8080 -sTCP:LISTEN
lsof -nP -iTCP:8010 -sTCP:LISTEN
```

Stop any listener left by an earlier run before relaunching. Then keep the startup script
in the foreground:

```bash
sh scripts/run-microservices-local.sh
```

In a second terminal, follow all four service logs:

```bash
tail -f logs/recsys-serving.log logs/online-serving.log \
  logs/model-serving.log logs/api-gateway.log
```

Check every service independently:

```bash
curl --fail --show-error --max-time 10 http://localhost:6010/health
curl --fail --show-error --max-time 10 http://localhost:7010/health
curl --fail --show-error --max-time 10 http://localhost:8080/health/ready
curl --fail --show-error --max-time 10 http://localhost:8010/health
```

One healthy endpoint does not prove the startup script completed: each process must be
healthy. Model serving can fail when the required local model artifacts are absent.

After the services are ready, generate traffic:

```bash
curl --fail http://localhost:8010/health
```

In **Search & Reporting**, set the time picker to **All time** if necessary. The
following local cookbook uses the same index and sourcetype for every search:

All events:

```spl
index=recsys sourcetype="recsys:app:log"
```

One service:

```spl
index=recsys sourcetype="recsys:app:log" source="recsys-serving"
```

Counts by source:

```spl
index=recsys sourcetype="recsys:app:log" | stats count by source
```

Counts by source and level:

```spl
index=recsys sourcetype="recsys:app:log" | stats count by source level
```

Recent warnings and errors:

```spl
index=recsys sourcetype="recsys:app:log" earliest=-15m (level=WARN OR level=ERROR)
```

A message fragment:

```spl
index=recsys sourcetype="recsys:app:log" message="manual UI verification"
```

One-minute event counts:

```spl
index=recsys sourcetype="recsys:app:log" | timechart span=1m count
```

Exceptions:

```spl
index=recsys sourcetype="recsys:app:log" exception=*
```

`traceId` is currently populated only by model serving (port 8080); see
[Useful searches](#useful-searches) for its limitation across the other three services.

### Troubleshooting local bring-up

#### Compose says a required variable is missing

Compose interpolates the entire file before executing any subcommand. Use the stable env
file even for `ps` and `down`:

```bash
docker compose --env-file /tmp/recsys-splunk.env \
  -f docker-compose.splunk.yml ps -a
```

#### Docker cannot connect to a Colima socket

Check which profile owns the active context:

```bash
docker context show
docker context ls
colima status
colima status splunk
```

For this Mac workflow, start and select the dedicated profile:

```bash
colima start splunk
docker context use colima-splunk
```

#### Compose remains at `Container splunk Waiting`

Do not start the JVM services yet. Inspect actual progress:

```bash
docker ps -a --filter name=splunk \
  --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
docker logs --tail=200 splunk
docker logs --tail=100 splunk-init
```

During a normal first boot, continue waiting. `splunk-init` retries HEC for up to 150
seconds after the main container's health check succeeds.

#### The container says `healthy`, but port 8000 does not work

The management health check can briefly retain a successful result while Splunk's Ansible
startup restarts `splunkd`. Check both the web endpoint and the real process state:

```bash
curl -I --max-time 5 http://localhost:8000/
docker exec -u splunk splunk /opt/splunk/bin/splunk status
docker logs --tail=200 splunk
docker logs --tail=100 splunk-init
```

If `splunkd` is not running and the logs stop progressing, the UI cannot work even though
Docker still displays `healthy`.

#### UI login fails or HEC returns `403 Invalid token`

This is credential drift: the env file no longer matches the credentials established in
the initialized volumes. Follow [Credential mismatch recovery](#credential-mismatch-recovery)
to restore both values from the running container. Use `down -v` only if the running
container cannot supply them or if a clean reset is intended.

#### Splunk crashes on Apple Silicon

The image has no native arm64 build. These messages indicate the known emulation failure,
not an application or browser problem:

```text
Received fatal signal 11 (Segmentation fault)
IndexerTPoolWorker
KVStore service will not start because kvstore process terminated
```

This reproduced against fresh volumes under some emulation configurations. Rosetta may
work better than ordinary QEMU/binfmt, but it is not a guarantee. Use the x86_64 GitHub
integration workflow or a genuine x86_64 host when the failure repeats.

### Cleanup

Stop the containers while keeping the local index and configuration:

```bash
docker compose --env-file /tmp/recsys-splunk.env \
  -f docker-compose.splunk.yml down
```

The next command permanently deletes the local Splunk index, admin configuration, and HEC
token along with the containers:

```bash
docker compose --env-file /tmp/recsys-splunk.env \
  -f docker-compose.splunk.yml down -v
```

If the env file is already gone, placeholders are sufficient for cleanup because Compose
only needs them to parse the file:

```bash
SPLUNK_PASSWORD=unused SPLUNK_HEC_TOKEN=unused \
  docker compose -f docker-compose.splunk.yml down -v
```

Verify that no Splunk containers remain:

```bash
docker ps -a --filter name=splunk
```

If the dedicated profile is no longer needed, stop it after cleanup:

```bash
colima stop splunk
```

### Why the Mac path remains best-effort

`splunk/splunk` publishes no arm64 image. On Apple Silicon, the Compose file forces
`platform: linux/amd64`, but `splunkd` can segfault during first-boot indexing under
emulation:

```
Received fatal signal 11 (Segmentation fault) on PID <n>. Crashing thread: IndexerTPoolWorker-0
```

with a companion failure in `splunkd.log`:

```
Failed to start mongod on first attempt reason=KVStore service will not start because kvstore process terminated
```

This reproduced on two independent boots, including one against freshly emptied
volumes, so it is not stale state — it is `splunkd` faulting under x86_64-on-arm64
emulation, most plausibly in the KVStore/mongod component. A Rosetta-enabled Colima
profile has progressed through UI startup and authentication on this project, but that
does not invalidate the failures on other boots. Treat genuine x86_64 as authoritative.

**What the unit tests cover**, independent of a running Splunk: the appender's HEC wire
format, its batching behavior, and the `Authorization: Splunk <token>` header are proven
against a stub HTTP collector in `SplunkHecAppenderTest`. All five `Splunk*`/observability
test classes (`SplunkHecConfigTest`, `SplunkHecEventSerializerTest`, `SplunkHecClientTest`,
`SplunkHecAppenderTest`, `SplunkLogbackWiringTest`) run in the `-Presilience` PR gate. A
stub collector cannot catch a payload that Splunk rejects or silently mangles, nor exercise
index creation and the HEC provisioning REST calls — which is why the docker-tagged
integration test above exists.

**Two HEC-enabling mechanisms are in place, deliberately.** Which one does the real work
was unknown when this was written, so both are present and the integration test asserts
only the outcome:
1. The Splunk image's own env vars (`SPLUNK_HEC_TOKEN`, `SPLUNK_HEC_SSL: "False"`),
   honoured by its Ansible provisioning layer.
2. `docker/splunk/init.sh` independently repairs the same state via Splunk's management
   REST API (create the index, force HEC's global `enableSSL` off, create the token with a
   pinned value) and then polls the plain-HTTP collector endpoint until it accepts an event.

The belt-and-braces approach is not redundant: upstream `splunk/docker-splunk#40` documents
`SPLUNK_HEC_TOKEN` historically not being reliably honoured for *standalone* instances,
which is why `init.sh`'s management-API repair exists rather than trusting the env var
alone. If you want to know which one is load-bearing on a given image tag, remove one and
watch whether `initScriptEnablesPlainHttpHec` still passes.

### Recovering from an interrupted first boot or token change

If the `splunk` container is killed (or the host restarts) before it finishes
initializing, `splunk-etc`/`splunk-var` are left half-provisioned, and the next start
fails fast with:

```
Checking appserver port [127.0.0.1:8065]: ...
ERROR: appserver port [127.0.0.1:8065] - port is already bound.
```

This looks alarming — it names a port — but nothing external is bound there; it is an
internal port check tripping over a second `splunkd` starting against stale state. The
fix is to discard the half-initialized volumes and start clean. This permanently deletes
the local index and configuration:

```bash
docker compose --env-file /tmp/recsys-splunk.env \
  -f docker-compose.splunk.yml down -v
```

`init.sh` is idempotent for a **stable** token: re-running it against existing volumes
(e.g. a plain restart) is a no-op. It is not idempotent across a token *change*. Splunk
has no in-place update for an HEC token's value on a name collision, and `init.sh`
neither deletes nor recreates the token — it only attempts to create one named `recsys`.
If `SPLUNK_HEC_TOKEN` changes between runs against volumes that already have a `recsys`
token provisioned, the **old** token stays live and `splunk-init` exits 1 trying to
verify the new one. Recovery is the same as above: `down -v`, then bring the stack up
with the new token from empty volumes.

## Enabling in EKS

**No Splunk is deployed by these manifests.** `k8s/base`, `k8s/eks-shared`, and both
region overlays (`k8s/eks`, `k8s/eks-us-west-2`) contain no Splunk Service, Deployment,
or `ExternalName` — `grep -rn splunk k8s/` turns up only the `SPLUNK_*` env blocks in
the four service Deployments. Every one of them hardcodes
`SPLUNK_HEC_URL: http://splunk:8088/services/collector/event`, a bare Kubernetes service
name that resolves to nothing in-cluster until something provides it. Creating the
Secret below and restarting is **not sufficient by itself** — without also repointing
`SPLUNK_HEC_URL`, all four services will have a live token and every batch will fail
DNS resolution against `splunk`. This fails safe (console logging is unaffected,
`TRANSPORT_FAILURE` is throttled), but it does not ship anything to Splunk.

The `recsys-splunk` Secret (key `hec-token`) is wired as `optional: true` into all four
`k8s/base/*.yaml` deployments, the same pattern as `recsys-online-admin` /
`SHARD_ADMIN_TOKEN` — pods are schedulable before the Secret exists, and the appender
stays inert. Creating the Secret does not affect already-running pods: the appender
reads its configuration from the environment once, at Logback startup, so an existing
pod keeps shipping nothing (or keeps using an old token) until it restarts.

```bash
kubectl -n recsys create secret generic recsys-splunk --from-literal=hec-token='<token>'
```

**Before restarting, repoint `SPLUNK_HEC_URL` at a real collector.** Depending on where
Splunk actually runs, that is one of:

- **In-cluster Splunk Service** — deploy (or point to) a Service literally named
  `splunk` in the same namespace, matching the manifests' default as-is, and skip the
  next step.
- **Externally hosted Splunk, reachable via a stable DNS name** — add a Kubernetes
  `ExternalName` Service named `splunk` that points at the real collector hostname, so
  the manifests' existing `http://splunk:8088/...` default resolves correctly with no
  patch to the Deployments themselves.
- **Any other real HEC endpoint** — patch each Deployment's `SPLUNK_HEC_URL` `value:`
  (in `k8s/base/api-gateway.yaml`, `catalog-serving.yaml`, `model-serving.yaml`,
  `online-serving.yaml`) to the actual collector URL via a Kustomize overlay patch, and
  re-render (`kubectl kustomize k8s/eks`) before applying.

Whichever option you take, the destination needs an egress rule. `k8s/base/network-policy.yaml`
permits `app: splunk` on 8088 from all four serving policies, which covers the first two options
unchanged. The third does not: changing `SPLUNK_HEC_URL` in `k8s/base` fails
`NetworkPolicyEgressManifestTest` until a matching rule exists, but changing it through an *overlay*
patch is invisible to that test — it reads `k8s/base` only. An overlay that repoints Splunk must add
the matching egress rule by hand, the same way `network-policy-elasticache-patch.yaml` does for
ElastiCache.

Only once one of those is in place does a rollout restart actually start shipping logs:

```bash
kubectl -n recsys rollout restart deployment/recsys-api-gateway
kubectl -n recsys rollout restart deployment/recsys-catalog-serving
kubectl -n recsys rollout restart deployment/recsys-model-serving
kubectl -n recsys rollout restart deployment/recsys-online-serving
```

Confirm the Secret name (`recsys-splunk`) and key (`hec-token`) match what each
deployment's `SPLUNK_HEC_TOKEN` env entry references — see the `SPLUNK_*` block in
`k8s/base/api-gateway.yaml` (and the equivalent block in the other three manifests) for
the exact `secretKeyRef`.

`SPLUNK_SERVICE_NAME` and `SPLUNK_HEC_URL` are plain `value:` entries per manifest, not
sourced from the Secret — only the token is sensitive.

## Disabling

```bash
kubectl -n recsys delete secret recsys-splunk
kubectl -n recsys rollout restart deployment/recsys-api-gateway
kubectl -n recsys rollout restart deployment/recsys-catalog-serving
kubectl -n recsys rollout restart deployment/recsys-model-serving
kubectl -n recsys rollout restart deployment/recsys-online-serving
```

Because the Secret is `optional: true`, deleting it does not stop pods from being
schedulable; the appender simply comes back up inert on the next restart. Console
logging continues completely untouched throughout.

## Useful searches

```
index=recsys sourcetype="recsys:app:log" | stats count by source
```

`source` is `SPLUNK_SERVICE_NAME`, so this breaks counts down per service
(`recsys-serving`, `model-serving`, `online-serving`, `api-gateway` locally; see
`scripts/run-microservices-local.sh` and the `k8s/base/*.yaml` manifests for exactly
which value each service sets).

Filter to errors only:

```
index=recsys sourcetype="recsys:app:log" level=ERROR
```

Correlate one request via `traceId`:

```
index=recsys sourcetype="recsys:app:log" traceId="<id>"
```

**`traceId` is only populated by the model service (port 8080).** `TraceIdAspect` is
Spring AOP, wired through Spring's proxying — the three Armeria mains (RecSys Serving,
Online Serving, API Gateway) never populate it, because there is no Spring container
to weave the aspect into. Do not expect cross-service trace correlation through
`traceId` today; a search for one will only ever surface model-service events.

## Tuning

| Variable | Default | Effect |
|---|---|---|
| `SPLUNK_HEC_TOKEN` | unset | Enablement switch. Unset means the appender is a no-op — console-only. |
| `SPLUNK_HEC_URL` | `http://splunk:8088/services/collector/event` | Full collector URL. |
| `SPLUNK_HEC_INDEX` | `recsys` | Splunk index events are written to. |
| `SPLUNK_HEC_SOURCETYPE` | `recsys:app:log` | Splunk `sourcetype` field. |
| `SPLUNK_SERVICE_NAME` | `recsys` | Per-service `source` field — set uniquely by each of the four mains. |
| `SPLUNK_HEC_QUEUE_CAPACITY` | `10000` | Bounded queue depth before events are dropped. |
| `SPLUNK_HEC_BATCH_SIZE` | `100` | Max events per POST. |
| `SPLUNK_HEC_LINGER_MS` | `1000` | How long the drain thread waits for a batch's first event before checking again. |
| `SPLUNK_HEC_TIMEOUT_MS` | `2000` | Connect + request timeout. |
| `SPLUNK_HEC_INSECURE_TLS` | `false` | Accept Splunk's self-signed certificate when the URL is `https://`. Dev-only — see below. |

What to change, and when:
- **`dropped` climbing during bursts** (visible as repeated `TRANSPORT_FAILURE`/queue
  pressure in the Logback status output — see Diagnosing below) → raise
  `SPLUNK_HEC_QUEUE_CAPACITY`. The queue is sized for steady-state; a burst that
  outruns it is exactly the scenario drop-on-full exists to survive without blocking a
  serving thread, but a queue that is too small will drop routinely rather than only
  under genuine overload.
- **Want higher steady-state throughput** → raise `SPLUNK_HEC_BATCH_SIZE`, so more
  events go out per HTTP POST.
- **Collector is far away / slow to respond** → raise `SPLUNK_HEC_TIMEOUT_MS` so a
  distant collector's normal latency doesn't get misclassified as a transport failure.

`SPLUNK_HEC_INSECURE_TLS=true` disables Splunk TLS certificate verification entirely. It
exists only to point a developer at a local Splunk instance's stock self-signed
certificate over `https://`. It defaults to `false` and is never set in any committed
manifest — do not set it against anything but a local, disposable instance.

## Diagnosing

**The appender cannot log its own failures through slf4j.** Any slf4j call from inside
`SplunkHecAppender` would route back into the very appender attached to the root
logger and recurse. Instead, it reports through Logback's own status API
(`addInfo`/`addWarn`/`addError`), which surfaces as plain lines on stdout, formatted
like:

```
WARN in ch.qos.logback.classic.LoggerContext - Splunk HEC delivery failed (SERVER_ERROR); dropped 12 events. Total failed: 12
```

**These never reach Splunk** — that's the whole point of routing them elsewhere,
since the appender cannot depend on the thing it's trying to diagnose. If you are
debugging "why is nothing arriving in Splunk," look at the service's own stdout /
`logs/<service>.log`, not at a Splunk search.

**Never set `-Djdk.httpclient.HttpClient.log` (or the `jdk.httpclient.HttpClient.log`
system property another way) on a service with this appender attached.** It is the
obvious first move when debugging "nothing is arriving in Splunk," and it is the wrong
one. The JDK's `HttpClient` logs through `System.Logger`, and this repo's classpath
routes `System.Logger` → `java.util.logging` → `SLF4JBridgeHandler` → slf4j → Logback →
this very appender (`jul-to-slf4j` arrives via `spring-boot-starter-logging`, and Spring
Boot installs the bridge handler). The only thing standing between that chain and a
recursive loop is that `HttpClient`'s own request/response logging defaults to off.
Turning it on makes every HTTP POST this appender's client makes emit a log line, which
gets enqueued, which gets shipped, which logs again — a self-sustaining amplification
loop. It will not stack-overflow (Logback's per-thread re-entrancy guard in
`UnsynchronizedAppenderBase` prevents that), so instead the drain thread pins itself in
the loop indefinitely, which is arguably worse: quieter, and it stops shipping every
other log line while it happens.

`SplunkHecClient.send` classifies every outcome into one of five values
(`SplunkHecClient.Outcome`), and a warning is throttled to once per outcome kind per
minute (an unthrottled warning per failed batch would itself become a log flood during
an outage):

| Outcome | Meaning |
|---|---|
| `SUCCESS` | Splunk accepted the batch (2xx). |
| `AUTH_REJECTED` | Splunk returned 401/403 — the token is wrong, expired, or was never actually created for this index. Logged as `addError`, with an explicit "Check SPLUNK_HEC_TOKEN." suffix. |
| `SERVER_ERROR` | Splunk returned some other non-2xx status. |
| `TRANSPORT_FAILURE` | The request never left the host — connection refused, DNS failure. Also covers a batch that failed to serialize, and a caught `Throwable` from the drain loop itself (an `OutOfMemoryError` or `StackOverflowError` most likely to fire during the very burst this appender exists to survive). **These events are definitely lost.** |
| `INDETERMINATE` | The request was sent and no response came back — a read timeout, or an interrupt while awaiting the response. **Delivery is genuinely unknown:** Splunk may have indexed the batch. |

**`INDETERMINATE` is not a softer `TRANSPORT_FAILURE`, and the distinction is
operational.** A failed event is lost, full stop. An indeterminate event is lost *or*
already in Splunk — so it is the set that a retry, whether added here or bolted on
upstream, would duplicate. Collapsing the two would report a definite loss that may not
have happened and hide the duplicate risk entirely. Counting them separately means an
`indeterminate` figure of zero is a real statement: everything the appender handed to
Splunk was acknowledged one way or the other.

**Splunk's own rejection text is included in the warning.** `SplunkHecClient` reads the
HEC response body rather than discarding it, so the warning carries `Splunk said: HTTP
400: {"text":"Incorrect index",...}` instead of a bare `SERVER_ERROR`. That string is
what distinguishes a misconfiguration from back-pressure. It is bounded to 300
characters, has control characters stripped, and has the HEC token and any
`Authorization`-looking header redacted — because the body is whatever answered on the
configured URL, which is not guaranteed to be Splunk, and a proxy that echoes request
headers would otherwise put the token straight into `logs/*.log` and into Splunk itself.

### Shutdown

`stop()` emits one summary line describing what happened to the tail of the log stream —
the moment an operator most needs the detail and normally gets the least:

```
Splunk HEC appender stopped. queuedAtShutdown=812 inFlightBatchesAtShutdown=1
flushedAtShutdown=812 gracefulDrainMillis=143 forcedInterrupt=false confirmed=4096
failed=0 indeterminate=0 droppedQueueFull=0
```

- `queuedAtShutdown` / `inFlightBatchesAtShutdown` — what was still pending when the stop
  began.
- `flushedAtShutdown` — events drained and shipped by `stop()` itself, after the drain
  thread finished.
- `gracefulDrainMillis` — how long the drain thread took to wind down.
- `forcedInterrupt` — whether the drain thread overran its 2 s budget and had to be
  interrupted. **`true` means the tail of the stream is uncertain**, and the line is
  emitted at WARN rather than INFO.
- `confirmed` / `failed` / `indeterminate` / `droppedQueueFull` — the final tallies.

`stop()` deliberately joins the drain thread *before* interrupting it, so a POST already
on the wire is allowed to complete within the budget; the interrupt is an escalation only.
This is pinned by `stopLetsAnInFlightSendFinishInsteadOfAbortingIt` — a regression test
worth keeping, because the original ordering was wrong and six review rounds against a
stub collector did not catch it.

Startup itself logs one line either way, also through the status API: either
`Splunk HEC appender shipping to <url> (index=..., source=...)` when enabled, or
`Splunk HEC appender disabled: <reason>` when not (info-level for the ordinary "no
token" case, error-level if a token was supplied but the rest of the configuration —
e.g. `SPLUNK_HEC_URL` — is unusable).

## Divergences from a production Splunk

Mirroring `docs/runbooks/cdn-local.md`'s framing: this is a demonstration of HEC
ingestion, not an emulation of a production Splunk deployment.

- **Single instance, no clustering.** No indexer cluster, no search head cluster, no
  cluster master. A production deployment would spread ingestion and search load
  across multiple peers; this is one container.
- **No forwarders.** Events go straight from the JVMs to HEC. There is no universal or
  heavy forwarder tier, no intermediate buffering beyond this appender's own bounded
  queue.
- **No retention tiering.** No hot/warm/cold/frozen bucket lifecycle, no archiving, no
  data retention policy beyond whatever this Splunk instance's defaults are.
- **HEC on plain HTTP with a shared dev token.** `init.sh` deliberately forces the
  global HEC setting to `enableSSL=0` so the collector accepts plain-HTTP POSTs (see
  the compose file's `SPLUNK_HEC_SSL: "False"` and `init.sh`'s explicit `enableSSL=0`
  call) — this exists purely so a local run needs no certificate trust setup. A real
  Splunk HEC endpoint should be TLS, and a real deployment issues distinct tokens per
  source rather than one token shared across every developer's stack.

This is a wiring harness for the appender's HEC contract, not a production Splunk
stand-in.

## A note on secret scanning

GitGuardian raised one incident against this feature's branch. **It was resolved as a
false positive, not by rotating anything**, and the reasoning is recorded here so nobody
has to reconstruct it from a dashboard.

**What it flagged:** the literal `changeme-splunk-admin`, used as `SPLUNK_PASSWORD` in
the bring-up example in this runbook and in `docker-compose.splunk.yml`'s header. An
accompanying `local-dev-hec-token` placeholder was flagged with it.

**Why it was not a secret.** It set the admin password of a throwaway `splunk/splunk`
container started by `docker-compose.splunk.yml` on a developer's laptop, published on
localhost. That stack has never run anywhere but a disposable container: it is not
deployed, not reachable from outside the host, and holds no real data. The value was
never a credential for any Splunk instance, AWS account, or other system belonging to
this project, and it grants access to nothing that outlives a `docker compose down`.
Real deployments take `SPLUNK_HEC_TOKEN` from the `recsys-splunk` Kubernetes Secret,
provisioned out-of-band and referenced by `secretKeyRef` — never committed.

**Current state: removed from the tree.** Both call sites now generate a value at run
time (`openssl rand -base64 18` for the password, `uuidgen` for the token), matching how
this repo already handles `RECOMMENDATION_CURSOR_SIGNING_KEY`. A copy-pasteable literal
password in a runbook is a mild real risk regardless of what a scanner thinks — people
paste them into environments that are not local — so this is worth doing on its own
merits.

**It still exists in history**, in the merged `feat/splunk-hec-log-shipping` commits
(`80749ab`, `197b1d8`, `208fc6d`). That is deliberate. Rewriting shared history to purge
a value that was never sensitive trades a real disruption — every clone and open branch
needing recovery — for no security gain.

### Where the ignore actually lives — and where it does not

The check on pull requests, **"GitGuardian Security Checks", comes from the GitGuardian
GitHub App**, which scans server-side. Its incident state lives in the GitGuardian
dashboard and **can only be changed there**: sign in at `dashboard.gitguardian.com` and
resolve the incident as a false positive.

`.gitguardian.yaml` in this repo does **not** suppress that check. That file is read by
[ggshield](https://docs.gitguardian.com/ggshield-docs/configuration) only — the CLI, the
pre-commit hook, and the ggshield GitHub Action — and this repo runs none of them (no
ggshield step in `.github/workflows/`, no `.pre-commit-config.yaml`). It is a record of
the reasoning plus a config that becomes live the moment ggshield is adopted, not a
control that is doing anything today. An earlier version of this section claimed
otherwise; that was wrong.

Note also that the App is **not** blocking: `main` has no branch protection, so a red
GitGuardian check does not prevent a merge (#259 was merged with it red).

**If a future scan flags something here, do not assume it is this placeholder.** Check
the value against `.gitguardian.yaml`'s documented entries first — they are the record of
what has been judged benign, even though the file does not enforce it. A genuine
`SPLUNK_HEC_TOKEN` for a real collector appearing in a diff is a real incident: rotate it
in Splunk, do not resolve it as a false positive.
