#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/reports with spaces"
LOG="$TMP/kubectl.argv"

cat >"$TMP/bin/kubectl" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" >>"$FAKE_KUBECTL_LOG"
printf '\n' >>"$FAKE_KUBECTL_LOG"
scenario="${FAKE_KUBECTL_SCENARIO:-healthy}"
if [ "${1:-}" = "kustomize" ]; then
  path="$2"
  if [[ "$path" == *"eks-us-west-2-active"* || "$path" == *"/base" ]]; then mins=(2 2 3 2); else mins=(1 1 2 1); fi
  digest="${FAKE_IMAGE_DIGEST:-sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}"
  if [ "$scenario" = wrong-image ] && [[ "$path" == *"/eks-us-west-2" ]]; then digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb; fi
  names=(api-gateway catalog-serving model-serving online-serving)
  for i in 0 1 2 3; do
    cat <<YAML
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: recsys-${names[$i]}
  namespace: recsys
spec:
  minReplicas: ${mins[$i]}
  maxReplicas: 8
---
YAML
  done
  for name in api-gateway catalog-serving model-serving online-serving outbox-relay; do
    if [ "$name" = outbox-relay ]; then
      topology=""
    else
      topology="      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule"
    fi
    cat <<YAML
apiVersion: apps/v1
kind: Deployment
metadata:
  name: recsys-$name
spec:
  template:
    spec:
$topology
      containers:
      - name: app
        image: example.invalid/recsys${FAKE_IMAGE_SEPARATOR:-@}$digest
---
YAML
  done
  exit 0
fi

args=" $* "
if [[ "$args" == *" config current-context "* ]]; then
  printf '%s\n' "${FAKE_CURRENT_CONTEXT:-prod-us-west-2}"
elif [[ "$args" == *" get hpa "* ]]; then
  state=""; [ ! -e "$FAKE_CLUSTER_STATE" ] || state="$(cat "$FAKE_CLUSTER_STATE")"
  if [ "$state" = warm ] || { { [ "$scenario" = "warm" ] || [ "$scenario" = "stateful" ] || [ "$scenario" = "apply-fail" ] || [ "$scenario" = "partial-apply" ]; } && [ -z "$state" ]; }; then
    printf 'recsys-api-gateway 1 8\nrecsys-catalog-serving 1 8\nrecsys-model-serving 2 8\nrecsys-online-serving 1 8\n'
  else
    printf 'recsys-api-gateway 2 8\nrecsys-catalog-serving 2 8\nrecsys-model-serving 3 8\nrecsys-online-serving 2 8\n'
  fi
  [ "$scenario" != unrelated ] || printf 'unrelated-hpa 99 100\n'
  [ "$scenario" != max-mismatch ] || printf 'recsys-api-gateway 2 99\n'
elif [[ "$args" == *" apply "* ]]; then
  payload="$(cat)"
  if [ "$scenario" = "apply-fail" ]; then exit 1; fi
  python3 -c '
import re,sys
docs=re.split(r"(?m)^---\s*$",sys.stdin.read())
names=[]
for d in docs:
 k=re.search(r"(?m)^kind:\s*(\S+)\s*$",d)
 if not k: continue
 assert k.group(1)=="HorizontalPodAutoscaler"
 n=re.search(r"(?m)^\s+name:\s+(\S+)",d); assert n; names.append(n.group(1))
assert set(names)=={"recsys-api-gateway","recsys-catalog-serving","recsys-model-serving","recsys-online-serving"} and len(names)==4
' <<<"$payload"
  if [[ "$args" != *" --dry-run=server "* ]] && [ "$scenario" != partial-apply ]; then
    if [[ "$payload" == *"minReplicas: 1"* ]]; then printf warm >"$FAKE_CLUSTER_STATE"; else printf promoted >"$FAKE_CLUSTER_STATE"; fi
  fi
  printf 'applied\n'
elif [[ "$args" == *" rollout status "* || "$args" == *" wait pod "* ]]; then
  [ "$scenario" != "rollout-fail" ]
elif [[ "$args" == *" get pdb "* ]]; then
  if [ "$scenario" = "pdb-fail" ]; then
    printf 'recsys-api-gateway-pdb 0 0 1\nrecsys-catalog-serving-pdb 1 2 2\nrecsys-model-serving-pdb 1 3 3\nrecsys-online-serving-pdb 1 2 2\n'
  else
    printf 'recsys-api-gateway-pdb 1 3 3\nrecsys-catalog-serving-pdb 1 2 2\nrecsys-model-serving-pdb 1 3 3\nrecsys-online-serving-pdb 1 2 2\n'
  fi
elif [[ "$args" == *" get --raw "* ]]; then
  if [ "$scenario" = "service-fail" ]; then printf '{"status":"DOWN"}\n'; else printf '{"status":"UP"}\n'; fi
elif [[ "$args" == *" get nodes "* ]]; then
  if [ "$scenario" = "topology-fail" ]; then printf 'us-west-2a\n'; else printf 'us-west-2a\nus-west-2b\n'; fi
else
  echo "unexpected fake kubectl invocation: $*" >&2
  exit 90
fi
FAKE
chmod +x "$TMP/bin/kubectl"

export PATH="$TMP/bin:$PATH"
export FAKE_KUBECTL_LOG="$LOG"
export FAKE_CLUSTER_STATE="$TMP/cluster-promoted"
cat >"$TMP/bin/dependency-probe" <<'PROBE'
#!/usr/bin/env bash
[ "${FAKE_KUBECTL_SCENARIO:-healthy}" != dependency-fail ] && { echo healthy; exit 0; }
echo failed
exit 1
PROBE
chmod +x "$TMP/bin/dependency-probe"
SCRIPT="$ROOT/scripts/dr-standby-capacity.sh"
PASS=0
FAIL=0

reset_case() { : >"$LOG"; rm -f "$FAKE_CLUSTER_STATE"; unset DR_WRITER_IDENTITY DR_REPLICATION_DIRECTION DR_REPLICATION_LAG_STATUS DR_TRAFFIC_TARGET DR_CAPACITY_READY FAKE_IMAGE_SEPARATOR; export DR_DEPENDENCY_PROBE="$TMP/bin/dependency-probe" FAKE_KUBECTL_SCENARIO=healthy FAKE_CURRENT_CONTEXT=prod-us-west-2 FAKE_IMAGE_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; }
ok() { PASS=$((PASS + 1)); printf 'ok - %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf 'not ok - %s\n' "$1" >&2; }
run_ok() { local name="$1"; shift; reset_case; if "$SCRIPT" "$@" >"$TMP/out" 2>"$TMP/err"; then ok "$name"; else bad "$name"; sed -n '1,120p' "$TMP/err" >&2; fi; }
run_fail() { local name="$1"; shift; reset_case; if "$SCRIPT" "$@" >"$TMP/out" 2>"$TMP/err"; then bad "$name (unexpected success)"; else ok "$name"; fi; }
no_mutation() {
  local name="$1"
  if python3 - "$LOG" <<'PY'
import sys
d=open(sys.argv[1], "rb").read().replace(b"\0", b" ").decode()
bad=(" apply ", " patch ", " delete ", " route53 ", " failover-global-cluster ", " promote-read-replica ")
raise SystemExit(any(x in " "+d+" " for x in bad))
PY
  then ok "$name"; else bad "$name"; fi
}
json_assert() {
  local name="$1" file="$2" expr="$3"
  if python3 - "$file" "$expr" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
assert eval(sys.argv[2], {"__builtins__": {}}, {"d": d})
PY
  then ok "$name"; else bad "$name"; fi
}
make_evidence() {
  local path="$1" rendered digest
  rendered="$(kubectl kustomize "$ROOT/k8s/eks-us-west-2-active")"
  digest="$(printf '%s' "$rendered" | shasum -a 256 | awk '{print $1}')"
  python3 - "$path" "$digest" \
    "${DR_WRITER_IDENTITY:-unknown}" "${DR_REPLICATION_DIRECTION:-unknown}" \
    "${DR_REPLICATION_LAG_STATUS:-unknown}" "${DR_TRAFFIC_TARGET:-unknown}" \
    "${DR_EVIDENCE_HEALTH:-healthy}" "${DR_EVIDENCE_AGE_SECONDS:-0}" <<'PY'
import datetime,json,sys
path,digest,writer,direction,lag,traffic,health,age=sys.argv[1:]
at=datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(seconds=int(age))
json.dump({"source":"fake-read-only-probes","observedAt":at.isoformat().replace("+00:00","Z"),
 "writerIdentity":writer,"replicationDirection":direction,"lagStatus":lag,
 "rpoAccepted":True,"trafficTarget":traffic,"health":health,"manifestDigest":digest},open(path,"w"))
PY
  : >"$LOG"
}

run_fail "wrong context rejected" promote --context prod-us-east-1 --region us-west-2 --report "$TMP/wrong.json"
json_assert "wrong context writes failed report" "$TMP/wrong.json" 'd["schemaVersion"] == 1 and d["ready"] is False'
run_fail "spoofed context rejected" promote --context evil-prod-us-west-2 --region us-west-2 --report "$TMP/spoof.json"

reset_case; export FAKE_IMAGE_DIGEST=sha256:0000000000000000000000000000000000000000000000000000000000000000
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/placeholder.json" >/dev/null 2>&1; then bad "placeholder image rejected"; else ok "placeholder image rejected"; fi
no_mutation "placeholder rejection happens before mutation"

reset_case; export FAKE_IMAGE_SEPARATOR=:
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/tag.json" >/dev/null 2>&1; then bad "mutable or malformed image rejected"; else ok "mutable or malformed image rejected"; fi
no_mutation "mutable image rejected before mutation"

reset_case; export FAKE_KUBECTL_SCENARIO=wrong-image
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/wrong-image.json" >/dev/null 2>&1; then bad "inconsistent regional digest rejected"; else ok "inconsistent regional digest rejected"; fi
no_mutation "inconsistent digest rejected before mutation"

run_ok "already promoted succeeds" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/reports with spaces/promote.json"
no_mutation "already promoted does not apply"
json_assert "report schema is complete" "$TMP/reports with spaces/promote.json" '"schemaVersion" in d and "command" in d and "timestamp" in d and "targetRegion" in d and "context" in d and "gitCommit" in d and "manifestDigest" in d and "checks" in d and "capacityChange" in d and "ready" in d and "remainingOperatorActions" in d and d["capacityChange"] == "none" and d["ready"] is True'

reset_case; export FAKE_KUBECTL_SCENARIO=unrelated
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/unrelated.json" >/dev/null; then ok "unrelated HPA is ignored"; else bad "unrelated HPA is ignored"; fi

reset_case; export FAKE_KUBECTL_SCENARIO=max-mismatch
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/max.json" >/dev/null 2>&1; then bad "duplicate or mismatched target max rejected"; else ok "duplicate or mismatched target max rejected"; fi

reset_case; export FAKE_KUBECTL_SCENARIO=warm
"$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/applied.json" >/dev/null
if python3 - "$LOG" <<'PY'
import sys
d=open(sys.argv[1],"rb").read().replace(b"\0",b" ").decode()
assert " apply -f - " in " "+d+" "
PY
then ok "promote applies HPA documents"; else bad "promote applies HPA documents"; fi
json_assert "applied report truthful" "$TMP/applied.json" 'd["capacityChange"] == "applied" and d["ready"] is True'

reset_case; export FAKE_KUBECTL_SCENARIO=apply-fail
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/apply-fail.json" >/dev/null 2>&1; then bad "apply failure rejected"; else ok "apply failure rejected"; fi
json_assert "apply failure remains attempted and not ready" "$TMP/apply-fail.json" 'd["capacityChange"] == "attempted" and d["ready"] is False'

reset_case; export FAKE_KUBECTL_SCENARIO=partial-apply
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/partial-apply.json" >/dev/null 2>&1; then bad "partial apply convergence rejected"; else ok "partial apply convergence rejected"; fi
json_assert "partial apply remains attempted and not ready" "$TMP/partial-apply.json" 'd["capacityChange"] == "attempted" and d["ready"] is False'

reset_case
lock_key="$(printf '%s/%s' prod-us-west-2 recsys | shasum -a 256 | awk '{print $1}')"
lock_dir="${TMPDIR:-/tmp}/recsys-dr-$lock_key.lock"
mkdir "$lock_dir"
if "$SCRIPT" demote --context prod-us-west-2 --region us-west-2 --report "$TMP/locked.json" >/dev/null 2>&1; then bad "concurrent opposite action rejected"; else ok "concurrent opposite action rejected"; fi
rmdir "$lock_dir"
no_mutation "lock conflict rejected before mutation"

reset_case; export FAKE_KUBECTL_SCENARIO=stateful
"$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/first.json" >/dev/null
: >"$LOG"
"$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/second.json" >/dev/null
no_mutation "second promote is a no-op"
json_assert "second promote reports no capacity change" "$TMP/second.json" 'd["capacityChange"] == "none" and d["ready"] is True'

reset_case; export FAKE_KUBECTL_SCENARIO=warm
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --dry-run --report "$TMP/dry.json" >/dev/null 2>&1; then bad "dry-run with drift is not operationally ready"; else ok "dry-run with drift is not operationally ready"; fi
if python3 - "$LOG" <<'PY'
import sys
rows=[r.replace(b"\0",b" ").decode() for r in open(sys.argv[1],"rb").read().splitlines()]
applies=[r for r in rows if " apply " in " "+r+" "]
assert applies and all(" --dry-run=server " in " "+r+" " for r in applies)
PY
then ok "dry-run performs server validation without mutation"; else bad "dry-run performs server validation without mutation"; fi
json_assert "dry-run reported truthfully" "$TMP/dry.json" 'd["capacityChange"] == "dry-run" and d["ready"] is False'

reset_case
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --dry-run --report "$TMP/dry-ready.json" >/dev/null; then ok "converged server dry-run is ready"; else bad "converged server dry-run is ready"; fi
json_assert "converged dry-run remains dry-run" "$TMP/dry-ready.json" 'd["capacityChange"] == "dry-run" and d["ready"] is True'

reset_case; export FAKE_KUBECTL_SCENARIO=rollout-fail
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/rollout.json" >/dev/null 2>&1; then bad "partial rollout fails"; else ok "partial rollout fails"; fi
json_assert "partial rollout report is not ready" "$TMP/rollout.json" 'd["ready"] is False'

reset_case; export FAKE_KUBECTL_SCENARIO=pdb-fail
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/pdb.json" >/dev/null 2>&1; then bad "unhealthy PDB fails"; else ok "unhealthy PDB fails"; fi
json_assert "PDB failure report is not ready" "$TMP/pdb.json" 'd["ready"] is False'

reset_case; export FAKE_KUBECTL_SCENARIO=dependency-fail
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/dependency.json" >/dev/null 2>&1; then bad "dependency failure fails"; else ok "dependency failure fails"; fi
json_assert "dependency failure report is not ready" "$TMP/dependency.json" 'd["ready"] is False'

reset_case; export FAKE_KUBECTL_SCENARIO=service-fail
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/service.json" >/dev/null 2>&1; then bad "service readiness failure fails"; else ok "service readiness failure fails"; fi
json_assert "service failure report is not ready" "$TMP/service.json" 'd["ready"] is False'

reset_case; export FAKE_KUBECTL_SCENARIO=topology-fail
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/topology.json" >/dev/null 2>&1; then bad "single-zone schedulability fails"; else ok "single-zone schedulability fails"; fi

reset_case; export DR_WRITER_IDENTITY=unknown DR_REPLICATION_DIRECTION=west-to-east DR_REPLICATION_LAG_STATUS=accepted DR_TRAFFIC_TARGET=us-west-2 DR_DEPENDENCY_HEALTH=healthy DR_CAPACITY_READY=ready
make_evidence "$TMP/evidence-bad.json"
if "$SCRIPT" cutover-check --context prod-us-west-2 --region us-west-2 --evidence "$TMP/evidence-bad.json" --report "$TMP/cutover-bad.json" >/dev/null 2>&1; then bad "unknown cutover evidence rejected"; else ok "unknown cutover evidence rejected"; fi
no_mutation "cutover check is read-only"

reset_case; export DR_WRITER_IDENTITY=us-west-2 DR_REPLICATION_DIRECTION=west-to-east DR_REPLICATION_LAG_STATUS=unknown DR_TRAFFIC_TARGET=us-west-2 DR_DEPENDENCY_HEALTH=healthy DR_CAPACITY_READY=ready
make_evidence "$TMP/evidence-lag.json"
if "$SCRIPT" cutover-check --context prod-us-west-2 --region us-west-2 --evidence "$TMP/evidence-lag.json" --report "$TMP/cutover-lag-bad.json" >/dev/null 2>&1; then bad "unknown replication lag evidence rejected"; else ok "unknown replication lag evidence rejected"; fi
no_mutation "lag rejection is read-only"

reset_case; export DR_WRITER_IDENTITY=us-west-2 DR_REPLICATION_DIRECTION=west-to-east DR_REPLICATION_LAG_STATUS=accepted DR_TRAFFIC_TARGET=unknown DR_DEPENDENCY_HEALTH=healthy DR_CAPACITY_READY=ready
make_evidence "$TMP/evidence-target.json"
if "$SCRIPT" cutover-check --context prod-us-west-2 --region us-west-2 --evidence "$TMP/evidence-target.json" --report "$TMP/cutover-target-bad.json" >/dev/null 2>&1; then bad "unknown traffic target rejected"; else ok "unknown traffic target rejected"; fi
no_mutation "traffic-target rejection is read-only"

reset_case; export DR_WRITER_IDENTITY=us-west-2 DR_REPLICATION_DIRECTION=west-to-east DR_REPLICATION_LAG_STATUS=accepted DR_TRAFFIC_TARGET=us-west-2 DR_EVIDENCE_AGE_SECONDS=3600
make_evidence "$TMP/evidence-stale.json"
if "$SCRIPT" cutover-check --context prod-us-west-2 --region us-west-2 --evidence "$TMP/evidence-stale.json" --report "$TMP/stale.json" >/dev/null 2>&1; then bad "stale evidence rejected"; else ok "stale evidence rejected"; fi
no_mutation "stale evidence rejection is read-only"
unset DR_EVIDENCE_AGE_SECONDS

reset_case; export DR_WRITER_IDENTITY=us-west-2 DR_REPLICATION_DIRECTION=west-to-east DR_REPLICATION_LAG_STATUS=accepted DR_TRAFFIC_TARGET=us-west-2 DR_DEPENDENCY_HEALTH=healthy DR_CAPACITY_READY=ready
make_evidence "$TMP/evidence-cutover.json"
run_status=0; "$SCRIPT" cutover-check --context prod-us-west-2 --region us-west-2 --evidence "$TMP/evidence-cutover.json" --report "$TMP/cutover.json" >/dev/null || run_status=$?
if [ "$run_status" -eq 0 ]; then ok "known cutover evidence accepted"; else bad "known cutover evidence accepted"; fi
no_mutation "successful cutover check is read-only"

reset_case; export DR_WRITER_IDENTITY=us-east-1 DR_REPLICATION_DIRECTION=east-to-west DR_REPLICATION_LAG_STATUS=accepted DR_TRAFFIC_TARGET=us-east-1 DR_DEPENDENCY_HEALTH=healthy DR_CAPACITY_READY=ready
make_evidence "$TMP/evidence-failback.json"
if "$SCRIPT" failback-check --context prod-us-west-2 --region us-west-2 --evidence "$TMP/evidence-failback.json" --report "$TMP/failback.json" >/dev/null; then ok "known failback evidence accepted"; else bad "known failback evidence accepted"; fi
no_mutation "failback check is read-only"

run_ok "verify remains offline" verify
if python3 - "$LOG" <<'PY'
import sys
rows=open(sys.argv[1],"rb").read().splitlines()
assert rows and all(b"kustomize" in row and b"--context" not in row for row in rows)
PY
then ok "verify only renders manifests"; else bad "verify only renders manifests"; fi

reset_case; export FAKE_KUBECTL_SCENARIO=healthy
"$SCRIPT" demote --context prod-us-west-2 --region us-west-2 --report "$TMP/demote.json" >/dev/null
if python3 - "$LOG" <<'PY'
import sys
d=open(sys.argv[1],"rb").read().replace(b"\0",b" ").decode()
assert " apply -f - " in " "+d+" " and " apply -k " not in " "+d+" "
PY
then ok "demote applies HPA-only input"; else bad "demote applies HPA-only input"; fi

printf '%s passed, %s failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
