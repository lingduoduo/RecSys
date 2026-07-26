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
  cat <<YAML
apiVersion: apps/v1
kind: Deployment
metadata:
  name: recsys-api-gateway
spec:
  template:
    spec:
      containers:
      - name: app
        image: example.invalid/recsys@$digest
YAML
  exit 0
fi

args=" $* "
if [[ "$args" == *" config current-context "* ]]; then
  printf '%s\n' "${FAKE_CURRENT_CONTEXT:-prod-us-west-2}"
elif [[ "$args" == *" get hpa "* ]]; then
  if [ "$scenario" = "warm" ] || { [ "$scenario" = "stateful" ] && [ ! -e "$FAKE_CLUSTER_STATE" ]; }; then
    printf 'recsys-api-gateway 1\nrecsys-catalog-serving 1\nrecsys-model-serving 2\nrecsys-online-serving 1\n'
  else
    printf 'recsys-api-gateway 2\nrecsys-catalog-serving 2\nrecsys-model-serving 3\nrecsys-online-serving 2\n'
  fi
elif [[ "$args" == *" apply "* ]]; then
  cat >/dev/null
  if [ "$scenario" = "stateful" ]; then : >"$FAKE_CLUSTER_STATE"; fi
  printf 'applied\n'
elif [[ "$args" == *" rollout status "* || "$args" == *" wait pod "* ]]; then
  [ "$scenario" != "rollout-fail" ]
elif [[ "$args" == *" get pdb "* ]]; then
  if [ "$scenario" = "pdb-fail" ]; then printf 'pdb-a 0 0 1\n'; else printf 'pdb-a 1 3 3\n'; fi
else
  echo "unexpected fake kubectl invocation: $*" >&2
  exit 90
fi
FAKE
chmod +x "$TMP/bin/kubectl"

export PATH="$TMP/bin:$PATH"
export FAKE_KUBECTL_LOG="$LOG"
export FAKE_CLUSTER_STATE="$TMP/cluster-promoted"
SCRIPT="$ROOT/scripts/dr-standby-capacity.sh"
PASS=0
FAIL=0

reset_case() { : >"$LOG"; rm -f "$FAKE_CLUSTER_STATE"; unset DR_WRITER_IDENTITY DR_REPLICATION_DIRECTION DR_REPLICATION_LAG_STATUS DR_TRAFFIC_TARGET DR_CAPACITY_READY; export DR_DEPENDENCY_HEALTH=healthy FAKE_KUBECTL_SCENARIO=healthy FAKE_CURRENT_CONTEXT=prod-us-west-2 FAKE_IMAGE_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; }
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

run_fail "wrong context rejected" promote --context prod-us-east-1 --region us-west-2 --report "$TMP/wrong.json"
json_assert "wrong context writes failed report" "$TMP/wrong.json" 'd["schemaVersion"] == 1 and d["ready"] is False'

reset_case; export FAKE_IMAGE_DIGEST=sha256:0000000000000000000000000000000000000000000000000000000000000000
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/placeholder.json" >/dev/null 2>&1; then bad "placeholder image rejected"; else ok "placeholder image rejected"; fi
no_mutation "placeholder rejection happens before mutation"

run_ok "already promoted succeeds" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/reports with spaces/promote.json"
no_mutation "already promoted does not apply"
json_assert "report schema is complete" "$TMP/reports with spaces/promote.json" '"schemaVersion" in d and "command" in d and "timestamp" in d and "targetRegion" in d and "context" in d and "gitCommit" in d and "manifestDigest" in d and "checks" in d and "capacityChange" in d and "ready" in d and "remainingOperatorActions" in d and d["capacityChange"] == "none" and d["ready"] is True'

reset_case; export FAKE_KUBECTL_SCENARIO=warm
"$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/applied.json" >/dev/null
if python3 - "$LOG" <<'PY'
import sys
d=open(sys.argv[1],"rb").read().replace(b"\0",b" ").decode()
assert " apply -f - " in " "+d+" "
PY
then ok "promote applies HPA documents"; else bad "promote applies HPA documents"; fi
json_assert "applied report truthful" "$TMP/applied.json" 'd["capacityChange"] == "applied" and d["ready"] is True'

reset_case; export FAKE_KUBECTL_SCENARIO=stateful
"$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/first.json" >/dev/null
: >"$LOG"
"$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/second.json" >/dev/null
no_mutation "second promote is a no-op"
json_assert "second promote reports no capacity change" "$TMP/second.json" 'd["capacityChange"] == "none" and d["ready"] is True'

reset_case; export FAKE_KUBECTL_SCENARIO=warm
"$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --dry-run --report "$TMP/dry.json" >/dev/null
no_mutation "dry-run never applies"
json_assert "dry-run reported" "$TMP/dry.json" 'd["capacityChange"] == "dry-run"'

reset_case; export FAKE_KUBECTL_SCENARIO=rollout-fail
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/rollout.json" >/dev/null 2>&1; then bad "partial rollout fails"; else ok "partial rollout fails"; fi
json_assert "partial rollout report is not ready" "$TMP/rollout.json" 'd["ready"] is False'

reset_case; export FAKE_KUBECTL_SCENARIO=pdb-fail
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/pdb.json" >/dev/null 2>&1; then bad "unhealthy PDB fails"; else ok "unhealthy PDB fails"; fi
json_assert "PDB failure report is not ready" "$TMP/pdb.json" 'd["ready"] is False'

reset_case; export DR_DEPENDENCY_HEALTH=failed
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/dependency.json" >/dev/null 2>&1; then bad "dependency failure fails"; else ok "dependency failure fails"; fi
json_assert "dependency failure report is not ready" "$TMP/dependency.json" 'd["ready"] is False'

reset_case; export DR_WRITER_IDENTITY=unknown DR_REPLICATION_DIRECTION=west-to-east DR_REPLICATION_LAG_STATUS=accepted DR_TRAFFIC_TARGET=us-west-2 DR_DEPENDENCY_HEALTH=healthy DR_CAPACITY_READY=ready
if "$SCRIPT" cutover-check --context prod-us-west-2 --region us-west-2 --report "$TMP/cutover-bad.json" >/dev/null 2>&1; then bad "unknown cutover evidence rejected"; else ok "unknown cutover evidence rejected"; fi
no_mutation "cutover check is read-only"

reset_case; export DR_WRITER_IDENTITY=us-west-2 DR_REPLICATION_DIRECTION=west-to-east DR_REPLICATION_LAG_STATUS=unknown DR_TRAFFIC_TARGET=us-west-2 DR_DEPENDENCY_HEALTH=healthy DR_CAPACITY_READY=ready
if "$SCRIPT" cutover-check --context prod-us-west-2 --region us-west-2 --report "$TMP/cutover-lag-bad.json" >/dev/null 2>&1; then bad "unknown replication lag evidence rejected"; else ok "unknown replication lag evidence rejected"; fi
no_mutation "lag rejection is read-only"

reset_case; export DR_WRITER_IDENTITY=us-west-2 DR_REPLICATION_DIRECTION=west-to-east DR_REPLICATION_LAG_STATUS=accepted DR_TRAFFIC_TARGET=unknown DR_DEPENDENCY_HEALTH=healthy DR_CAPACITY_READY=ready
if "$SCRIPT" cutover-check --context prod-us-west-2 --region us-west-2 --report "$TMP/cutover-target-bad.json" >/dev/null 2>&1; then bad "unknown traffic target rejected"; else ok "unknown traffic target rejected"; fi
no_mutation "traffic-target rejection is read-only"

reset_case; export DR_WRITER_IDENTITY=us-west-2 DR_REPLICATION_DIRECTION=west-to-east DR_REPLICATION_LAG_STATUS=accepted DR_TRAFFIC_TARGET=us-west-2 DR_DEPENDENCY_HEALTH=healthy DR_CAPACITY_READY=ready
run_status=0; "$SCRIPT" cutover-check --context prod-us-west-2 --region us-west-2 --report "$TMP/cutover.json" >/dev/null || run_status=$?
if [ "$run_status" -eq 0 ]; then ok "known cutover evidence accepted"; else bad "known cutover evidence accepted"; fi
no_mutation "successful cutover check is read-only"

reset_case; export DR_WRITER_IDENTITY=us-east-1 DR_REPLICATION_DIRECTION=east-to-west DR_REPLICATION_LAG_STATUS=accepted DR_TRAFFIC_TARGET=us-east-1 DR_DEPENDENCY_HEALTH=healthy DR_CAPACITY_READY=ready
if "$SCRIPT" failback-check --context prod-us-west-2 --region us-west-2 --report "$TMP/failback.json" >/dev/null; then ok "known failback evidence accepted"; else bad "known failback evidence accepted"; fi
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
