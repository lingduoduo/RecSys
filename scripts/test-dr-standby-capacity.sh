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
  if [[ "$path" == *"eks-us-west-2-active"* ]] && [ -n "${FAKE_ACTIVE_API_MIN:-}" ]; then mins[0]="$FAKE_ACTIVE_API_MIN"; fi
  digest="${FAKE_IMAGE_DIGEST:-sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}"
  if [ "$scenario" = wrong-image ] && [[ "$path" == *"/eks-us-west-2" ]]; then digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb; fi
  names=(api-gateway catalog-serving model-serving online-serving)
  if [ "$scenario" = reordered ]; then
    hpa_header="kind: HorizontalPodAutoscaler
apiVersion: autoscaling/v2"
    deployment_header="kind: Deployment
apiVersion: ${FAKE_DEPLOYMENT_API:-apps/v1}"
  else
    hpa_header="apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler"
    deployment_header="apiVersion: ${FAKE_DEPLOYMENT_API:-apps/v1}
kind: Deployment"
  fi
  for i in 0 1 2 3; do
    cat <<YAML
$hpa_header
${FAKE_DUPLICATE_TOP_KEY:-}
metadata:
  name: recsys-${names[$i]}
  namespace: recsys
spec:
  minReplicas: ${FAKE_HPA_MIN_TAG:-}${mins[$i]}
  maxReplicas: 8
---
YAML
  done
  deployment_names=(api-gateway catalog-serving model-serving online-serving outbox-relay)
  [ "$scenario" != duplicate-resource ] || deployment_names+=(api-gateway)
  for name in "${deployment_names[@]}"; do
    if [ "$name" = outbox-relay ]; then
      topology=""
    else
      topology="      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule"
    fi
    cat <<YAML
$deployment_header
metadata:
  name: recsys-$name
  namespace: ${FAKE_DEPLOYMENT_NAMESPACE:-recsys}
  annotations:
    nested-lookalike: "kind: HorizontalPodAutoscaler"
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
elif [[ "$args" == *" config view "* ]]; then
  printf '{"contexts":[{"name":"%s","context":{"cluster":"target"}}],"clusters":[{"name":"target","cluster":{"server":"%s"}}]}\n' \
    "${FAKE_CURRENT_CONTEXT:-prod-us-west-2}" "${FAKE_CLUSTER_SERVER:-https://west.example.invalid}"
elif [[ "$args" == *" get hpa "* ]]; then
  state=""; [ ! -e "$FAKE_CLUSTER_STATE" ] || state="$(cat "$FAKE_CLUSTER_STATE")"
  if [ "$state" = warm ] || { { [ "$scenario" = "warm" ] || [ "$scenario" = "stateful" ] || [ "$scenario" = "apply-fail" ] || [ "$scenario" = "partial-apply" ] || [ "$scenario" = "blocked-child" ]; } && [ -z "$state" ]; }; then
    printf 'recsys-api-gateway 1 8\nrecsys-catalog-serving 1 8\nrecsys-model-serving 2 8\nrecsys-online-serving 1 8\n'
  else
    printf 'recsys-api-gateway %s 8\nrecsys-catalog-serving 2 8\nrecsys-model-serving 3 8\nrecsys-online-serving 2 8\n' "${FAKE_ACTIVE_API_MIN:-2}"
  fi
  [ "$scenario" != unrelated ] || printf 'unrelated-hpa 99 100\n'
  [ "$scenario" != max-mismatch ] || printf 'recsys-api-gateway 2 99\n'
elif [[ "$args" == *" apply "* ]]; then
  payload="$(cat)"
  if [ "$scenario" = "apply-fail" ]; then exit 1; fi
  python3 -c '
import json,sys
d=json.load(sys.stdin)
assert d["apiVersion"]=="v1" and d["kind"]=="List"
items=d["items"]; names=[x["metadata"]["name"] for x in items]
assert all(x["apiVersion"]=="autoscaling/v2" and x["kind"]=="HorizontalPodAutoscaler" for x in items)
assert set(names)=={"recsys-api-gateway","recsys-catalog-serving","recsys-model-serving","recsys-online-serving"} and len(names)==4
' <<<"$payload"
  if [[ "$args" != *" --dry-run=server "* ]] && [ "$scenario" != partial-apply ]; then
    if [[ "$payload" == *'"minReplicas":1'* ]]; then printf warm >"$FAKE_CLUSTER_STATE"; else printf promoted >"$FAKE_CLUSTER_STATE"; fi
  fi
  printf 'applied\n'
elif [[ "$args" == *" rollout status "* || "$args" == *" wait pod "* ]]; then
  if [ "$scenario" = "blocked-child" ] && [[ "$args" == *" rollout status "* ]]; then
    : >"${FAKE_BLOCK_MARKER:?}"
    sleep 2
  fi
  [ "$scenario" != "rollout-fail" ]
elif [[ "$args" == *" get pdb "* ]]; then
  if [ "$scenario" = "pdb-fail" ]; then
    printf 'recsys-api-gateway-pdb 0 0 1\nrecsys-catalog-serving-pdb 1 2 2\nrecsys-model-serving-pdb 1 3 3\nrecsys-online-serving-pdb 1 2 2\n'
  else
    printf 'recsys-api-gateway-pdb 1 3 3\nrecsys-catalog-serving-pdb 1 2 2\nrecsys-model-serving-pdb 1 3 3\nrecsys-online-serving-pdb 1 2 2\n'
  fi
elif [[ "$args" == *" get --raw "* ]]; then
  if [ "$scenario" = "cutover-race" ]; then
    : >"${FAKE_RACE_MARKER:?}"
    sleep 0.2
  fi
  if [ "$scenario" = "service-fail" ]; then printf '{"status":"DOWN"}\n'; else printf '{"status":"UP"}\n'; fi
elif [[ "$args" == *" get nodes "* ]]; then
  if [ "$scenario" = "topology-fail" ]; then zones=(us-west-2a); else zones=(us-west-2a us-west-2b); fi
  python3 - "${zones[@]}" <<'PY'
import json,sys
print(json.dumps({"items":[{"metadata":{"name":"node-"+str(i),"labels":{"topology.kubernetes.io/zone":z}},
"spec":{},"status":{"conditions":[{"type":"Ready","status":"True"}]}} for i,z in enumerate(sys.argv[1:])]}))
PY
elif [[ "$args" == *" get pods "* ]]; then
  python3 - "$scenario" <<'PY'
import json,sys
counts={"recsys-api-gateway":2,"recsys-catalog-serving":2,"recsys-model-serving":3,"recsys-online-serving":2}
items=[]
for app,count in counts.items():
  for i in range(count):
    node="node-0" if sys.argv[1]=="pod-placement-fail" else "node-"+str(i%2)
    items.append({"metadata":{"name":app+"-"+str(i),"labels":{"app":app}},"spec":{"nodeName":node},
      "status":{"conditions":[{"type":"Ready","status":"True"}]}})
print(json.dumps({"items":items}))
PY
else
  echo "unexpected fake kubectl invocation: $*" >&2
  exit 90
fi
FAKE
chmod +x "$TMP/bin/kubectl"

export PATH="$TMP/bin:$PATH"
export FAKE_KUBECTL_LOG="$LOG"
export FAKE_CLUSTER_STATE="$TMP/cluster-promoted"
CONTEXT_IDENTITIES="$TMP/context-identities.json"
printf '{"contexts":{"prod-us-west-2":{"region":"us-west-2","server":"https://west.example.invalid"},"west-alias":{"region":"us-west-2","server":"https://west.example.invalid"}}}\n' >"$CONTEXT_IDENTITIES"
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

reset_case() { : >"$LOG"; rm -f "$FAKE_CLUSTER_STATE"; unset DR_WRITER_IDENTITY DR_REPLICATION_DIRECTION DR_REPLICATION_LAG_STATUS DR_TRAFFIC_TARGET DR_CAPACITY_READY FAKE_IMAGE_SEPARATOR FAKE_DUPLICATE_TOP_KEY FAKE_ACTIVE_API_MIN FAKE_HPA_MIN_TAG FAKE_DEPLOYMENT_API FAKE_DEPLOYMENT_NAMESPACE; export DR_CONTEXT_IDENTITY_FILE="$CONTEXT_IDENTITIES" DR_DEPENDENCY_EVIDENCE_FILE="$TMP/dependency-evidence.json" FAKE_CLUSTER_SERVER=https://west.example.invalid FAKE_KUBECTL_SCENARIO=healthy FAKE_CURRENT_CONTEXT=prod-us-west-2 FAKE_IMAGE_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; make_dependency_evidence; : >"$LOG"; }
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
payload_digest() {
  local overlay="$1" rendered payload
  rendered="$(kubectl kustomize "$overlay")"
  payload="$(printf '%s' "$rendered" | ruby -ryaml -rjson -e '
d=Psych.parse_stream(STDIN.read).children.map(&:to_ruby).compact
h=d.select{|x|x["kind"]=="HorizontalPodAutoscaler"}.sort_by{|x|x.dig("metadata","name")}
c=nil;c=lambda{|o|o.is_a?(Hash) ? o.keys.sort.to_h{|k|[k,c.call(o[k])]} : (o.is_a?(Array) ? o.map{|v|c.call(v)} : o)}
puts JSON.generate(c.call({"apiVersion"=>"v1","kind"=>"List","items"=>h}))
')"
  printf '%s' "$payload" | shasum -a 256 | awk '{print $1}'
}
make_dependency_evidence() {
  local active standby status
  active="$(payload_digest "$ROOT/k8s/eks-us-west-2-active")"
  standby="$(payload_digest "$ROOT/k8s/eks-us-west-2")"
  if [ "${FAKE_KUBECTL_SCENARIO:-healthy}" = dependency-fail ]; then status=failed; else status=healthy; fi
  python3 - "$DR_DEPENDENCY_EVIDENCE_FILE" "$active" "$standby" "$status" <<'PY'
import datetime,json,sys
json.dump({"schemaVersion":1,"source":"recsys-dependency-observer/v1",
 "provenance":"approved-read-only-dependency-probes",
 "observedAt":datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00","Z"),
 "status":sys.argv[4],"manifestDigests":[sys.argv[2],sys.argv[3]]},open(sys.argv[1],"w"))
PY
}
make_evidence() {
  local path="$1" rendered payload digest
  rendered="$(kubectl kustomize "$ROOT/k8s/eks-us-west-2-active")"
  payload="$(printf '%s' "$rendered" | ruby -ryaml -rjson -e '
d=Psych.parse_stream(STDIN.read).children.map(&:to_ruby).compact
h=d.select{|x|x["kind"]=="HorizontalPodAutoscaler"}.sort_by{|x|x.dig("metadata","name")}
c=nil;c=lambda{|o|o.is_a?(Hash) ? o.keys.sort.to_h{|k|[k,c.call(o[k])]} : (o.is_a?(Array) ? o.map{|v|c.call(v)} : o)}
puts JSON.generate(c.call({"apiVersion"=>"v1","kind"=>"List","items"=>h}))
')"
  digest="$(printf '%s' "$payload" | shasum -a 256 | awk '{print $1}')"
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
reset_case; export FAKE_CURRENT_CONTEXT=west-alias
if "$SCRIPT" promote --context west-alias --region us-west-2 --report "$TMP/alias.json" >/dev/null; then ok "configured exact context alias accepted"; else bad "configured exact context alias accepted"; fi
reset_case; export FAKE_CLUSTER_SERVER=https://east.example.invalid
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/identity.json" >/dev/null 2>&1; then bad "wrong authoritative cluster identity rejected"; else ok "wrong authoritative cluster identity rejected"; fi

reset_case; export FAKE_IMAGE_DIGEST=sha256:0000000000000000000000000000000000000000000000000000000000000000
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/placeholder.json" >/dev/null 2>&1; then bad "placeholder image rejected"; else ok "placeholder image rejected"; fi
no_mutation "placeholder rejection happens before mutation"

reset_case; export FAKE_IMAGE_SEPARATOR=:
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/tag.json" >/dev/null 2>&1; then bad "mutable or malformed image rejected"; else ok "mutable or malformed image rejected"; fi
no_mutation "mutable image rejected before mutation"

reset_case; export FAKE_KUBECTL_SCENARIO=wrong-image
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/wrong-image.json" >/dev/null 2>&1; then bad "inconsistent regional digest rejected"; else ok "inconsistent regional digest rejected"; fi
no_mutation "inconsistent digest rejected before mutation"

reset_case; export FAKE_DUPLICATE_TOP_KEY='kind: HorizontalPodAutoscaler'
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/duplicate-key.json" >/dev/null 2>&1; then bad "duplicate top-level YAML key rejected"; else ok "duplicate top-level YAML key rejected"; fi
no_mutation "duplicate key rejected before mutation"

reset_case; export FAKE_KUBECTL_SCENARIO=duplicate-resource
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/duplicate-resource.json" >/dev/null 2>&1; then bad "duplicate resource identity rejected"; else ok "duplicate resource identity rejected"; fi
no_mutation "duplicate resource rejected before mutation"

reset_case; export FAKE_HPA_MIN_TAG='!unsafe '
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/tagged-scalar.json" >/dev/null 2>&1; then bad "custom tagged scalar rejected"; else ok "custom tagged scalar rejected"; fi

reset_case; export FAKE_DEPLOYMENT_NAMESPACE='!unsafe recsys'
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/tagged-object.json" >/dev/null 2>&1; then bad "custom tagged object value rejected"; else ok "custom tagged object value rejected"; fi

reset_case; export FAKE_DEPLOYMENT_API=apps/v1beta1
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/wrong-api.json" >/dev/null 2>&1; then bad "wrong Deployment API rejected"; else ok "wrong Deployment API rejected"; fi

reset_case; export FAKE_DEPLOYMENT_NAMESPACE=other
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/wrong-namespace.json" >/dev/null 2>&1; then bad "wrong Deployment namespace rejected"; else ok "wrong Deployment namespace rejected"; fi

reset_case
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/nested-lookalike.json" >/dev/null; then ok "nested kind lookalike does not alter structural classification"; else bad "nested kind lookalike does not alter structural classification"; fi

reset_case; export FAKE_KUBECTL_SCENARIO=reordered
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/reordered.json" >/dev/null; then ok "reordered structural fields are parsed by identity"; else bad "reordered structural fields are parsed by identity"; fi

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
printf 'do-not-overwrite\n' >"$TMP/existing-report.json"
if "$SCRIPT" demote --context prod-us-west-2 --region us-west-2 --report "$TMP/existing-report.json" >/dev/null 2>&1; then bad "existing audit report rejected"; else ok "existing audit report rejected"; fi
if [ "$(cat "$TMP/existing-report.json")" = "do-not-overwrite" ]; then ok "existing audit report preserved"; else bad "existing audit report preserved"; fi
no_mutation "report identity conflict rejected before mutation"

reset_case
printf 'still-owned\n' >"$TMP/forged-lock-report.json"
if DR_INHERITED_LOCK_FDS=1,2 "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/forged-lock-report.json" >/dev/null 2>&1; then bad "plain environment cannot forge inherited lock ownership"; else ok "plain environment cannot forge inherited lock ownership"; fi
if [ "$(cat "$TMP/forged-lock-report.json")" = still-owned ]; then ok "forged lock environment cannot overwrite report"; else bad "forged lock environment cannot overwrite report"; fi

reset_case
lock_key="$(printf '%s/%s' prod-us-west-2 recsys | shasum -a 256 | awk '{print $1}')"
mkdir -p "${TMPDIR:-/tmp}/recsys-dr-locks"
python3 - "${TMPDIR:-/tmp}/recsys-dr-locks/operation-$lock_key.lock" "$TMP/lock-held" <<'PY' &
import fcntl,sys,time
with open(sys.argv[1],"a+b") as f:
    fcntl.flock(f,fcntl.LOCK_EX)
    open(sys.argv[2],"w").close()
    time.sleep(30)
PY
lock_holder=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -e "$TMP/lock-held" ] && break; sleep 0.05; done
kill -9 "$lock_holder"
wait "$lock_holder" 2>/dev/null || true
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --dry-run --report "$TMP/recovered-lock.json" >/dev/null; then ok "kernel lock recovers after abnormal holder termination"; else bad "kernel lock recovers after abnormal holder termination"; fi

reset_case; export FAKE_KUBECTL_SCENARIO=blocked-child FAKE_BLOCK_MARKER="$TMP/orphan-child-blocked"
"$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/orphan-promote.json" >/dev/null 2>&1 &
wrapper_pid=$!
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do [ -e "$FAKE_BLOCK_MARKER" ] && break; sleep 0.05; done
kill -9 "$wrapper_pid"
wait "$wrapper_pid" 2>/dev/null || true
"$SCRIPT" demote --context prod-us-west-2 --region us-west-2 --report "$TMP/orphan-demote.json" >/dev/null 2>&1 &
opposite_pid=$!
sleep 0.2
if [ "$(cat "$FAKE_CLUSTER_STATE")" = promoted ] && kill -0 "$opposite_pid" 2>/dev/null; then ok "orphan child retains operation lock after wrapper SIGKILL"; else bad "orphan child retains operation lock after wrapper SIGKILL"; fi
opposite_status=0; wait "$opposite_pid" || opposite_status=$?
if [ "$opposite_status" -eq 0 ] && [ "$(cat "$FAKE_CLUSTER_STATE")" = warm ]; then ok "operation lock releases after orphan child exits"; else bad "operation lock releases after orphan child exits"; fi
unset FAKE_BLOCK_MARKER

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
make_dependency_evidence; : >"$LOG"
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/dependency.json" >/dev/null 2>&1; then bad "dependency failure fails"; else ok "dependency failure fails"; fi
json_assert "dependency failure report is not ready" "$TMP/dependency.json" 'd["ready"] is False'

reset_case; export FAKE_KUBECTL_SCENARIO=service-fail
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/service.json" >/dev/null 2>&1; then bad "service readiness failure fails"; else ok "service readiness failure fails"; fi
json_assert "service failure report is not ready" "$TMP/service.json" 'd["ready"] is False'

reset_case; export FAKE_KUBECTL_SCENARIO=topology-fail
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/topology.json" >/dev/null 2>&1; then bad "single-zone schedulability fails"; else ok "single-zone schedulability fails"; fi

reset_case; export FAKE_KUBECTL_SCENARIO=pod-placement-fail
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/pod-placement.json" >/dev/null 2>&1; then bad "target pods lacking cross-zone placement fail"; else ok "target pods lacking cross-zone placement fail"; fi

reset_case; export FAKE_ACTIVE_API_MIN=3
make_dependency_evidence; : >"$LOG"
if "$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/manifest-minimum.json" >/dev/null 2>&1; then bad "placement follows raised desired HPA minimum"; else ok "placement follows raised desired HPA minimum"; fi

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
if [ "$run_status" -eq 0 ]; then ok "known cutover evidence accepted"; else bad "known cutover evidence accepted"; python3 -m json.tool "$TMP/cutover.json" >&2; fi
no_mutation "successful cutover check is read-only"

reset_case
export DR_WRITER_IDENTITY=us-west-2 DR_REPLICATION_DIRECTION=west-to-east DR_REPLICATION_LAG_STATUS=accepted DR_TRAFFIC_TARGET=us-west-2
export FAKE_KUBECTL_SCENARIO=cutover-race FAKE_RACE_MARKER="$TMP/cutover-probing"
make_evidence "$TMP/evidence-race.json"
"$SCRIPT" cutover-check --context prod-us-west-2 --region us-west-2 --evidence "$TMP/evidence-race.json" --report "$TMP/cutover-race.json" >/dev/null &
cutover_pid=$!
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do [ -e "$FAKE_RACE_MARKER" ] && break; sleep 0.05; done
"$SCRIPT" demote --context prod-us-west-2 --region us-west-2 --report "$TMP/demote-race.json" >/dev/null &
demote_pid=$!
cutover_status=0; wait "$cutover_pid" || cutover_status=$?
demote_status=0; wait "$demote_pid" || demote_status=$?
if [ "$cutover_status" -eq 0 ] && [ "$demote_status" -eq 0 ]; then ok "cutover final gate serializes against concurrent demote"; else bad "cutover final gate serializes against concurrent demote"; fi
json_assert "serialized cutover report remains ready" "$TMP/cutover-race.json" 'd["ready"] is True'
unset FAKE_RACE_MARKER

reset_case; export DR_WRITER_IDENTITY=us-east-1 DR_REPLICATION_DIRECTION=east-to-west DR_REPLICATION_LAG_STATUS=accepted DR_TRAFFIC_TARGET=us-east-1 DR_DEPENDENCY_HEALTH=healthy DR_CAPACITY_READY=ready
make_evidence "$TMP/evidence-failback.json"
if "$SCRIPT" failback-check --context prod-us-west-2 --region us-west-2 --evidence "$TMP/evidence-failback.json" --report "$TMP/failback.json" >/dev/null; then ok "known failback evidence accepted"; else bad "known failback evidence accepted"; python3 -m json.tool "$TMP/failback.json" >&2; fi
no_mutation "failback check is read-only"

run_ok "verify remains offline" verify
if python3 - "$LOG" <<'PY'
import sys
rows=open(sys.argv[1],"rb").read().splitlines()
assert rows and all(b"kustomize" in row and b"--context" not in row for row in rows)
PY
then ok "verify only renders manifests"; else bad "verify only renders manifests"; fi

reset_case
printf 'immutable\n' >"$TMP/verify-existing.json"
if "$SCRIPT" verify --report "$TMP/verify-existing.json" >/dev/null 2>&1; then bad "verify rejects existing report"; else ok "verify rejects existing report"; fi
if [ "$(cat "$TMP/verify-existing.json")" = immutable ]; then ok "verify preserves existing report"; else bad "verify preserves existing report"; fi

reset_case; export FAKE_KUBECTL_SCENARIO=blocked-child FAKE_BLOCK_MARKER="$TMP/shared-report-blocked"
"$SCRIPT" promote --context prod-us-west-2 --region us-west-2 --report "$TMP/shared-report.json" >/dev/null 2>&1 &
cluster_pid=$!
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do [ -e "$FAKE_BLOCK_MARKER" ] && break; sleep 0.05; done
"$SCRIPT" verify --report "$TMP/shared-report.json" >/dev/null 2>&1 &
verify_pid=$!
cluster_status=0; wait "$cluster_pid" || cluster_status=$?
verify_status=0; wait "$verify_pid" || verify_status=$?
if [ "$cluster_status" -eq 0 ] && [ "$verify_status" -ne 0 ]; then ok "concurrent verify cannot overwrite cluster report"; else bad "concurrent verify cannot overwrite cluster report"; fi
json_assert "shared report retains cluster command identity" "$TMP/shared-report.json" 'd["command"] == "promote" and d["ready"] is True'
unset FAKE_BLOCK_MARKER

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
