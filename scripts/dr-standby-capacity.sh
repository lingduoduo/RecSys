#!/usr/bin/env bash
# Idempotent, HPA-only preparation and read-only evidence checks for the us-west-2 DR region.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTIVE_OVERLAY="$REPO_ROOT/k8s/eks-us-west-2-active"
STANDBY_OVERLAY="$REPO_ROOT/k8s/eks-us-west-2"
BASE_OVERLAY="$REPO_ROOT/k8s/base"
PRIMARY_OVERLAY="$REPO_ROOT/k8s/eks"
DR_NAMESPACE="recsys"
DR_COMMAND="${1:-}"
DR_CONTEXT=""
DR_REGION=""
DR_REPORT_PATH=""
DR_EVIDENCE_PATH=""
DR_DRY_RUN="false"
DR_CAPACITY_CHANGE="none"
DR_READY="false"
DR_MANIFEST_DIGEST="unknown"
DR_REPORT_WRITTEN="false"
DR_CHECK_NAMES=()
DR_CHECK_RESULTS=()
DR_CHECK_OBSERVED=()
DR_LOCK_DIR=""

usage() {
  cat >&2 <<EOF
Usage: $0 promote|demote --context NAME --region us-west-2 [--report FILE] [--dry-run]
       $0 cutover-check|failback-check --context NAME --region us-west-2 --evidence FILE [--report FILE]
       $0 verify [--report FILE]
EOF
  exit 2
}

add_check() {
  DR_CHECK_NAMES+=("$1")
  DR_CHECK_RESULTS+=("$2")
  DR_CHECK_OBSERVED+=("$3")
}

write_report() {
  [ -n "$DR_REPORT_PATH" ] || return 0
  local report_dir report_tmp git_commit
  report_dir="$(dirname "$DR_REPORT_PATH")"
  mkdir -p "$report_dir"
  report_tmp="$(mktemp "$report_dir/.dr-report.XXXXXX")"
  git_commit="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || printf unknown)"
  python3 - "$report_tmp" "$DR_COMMAND" "$DR_REGION" "$DR_CONTEXT" "$git_commit" \
    "$DR_MANIFEST_DIGEST" "$DR_CAPACITY_CHANGE" "$DR_READY" \
    "${#DR_CHECK_NAMES[@]}" \
    "${DR_CHECK_NAMES[@]}" --results "${DR_CHECK_RESULTS[@]}" \
    --observed "${DR_CHECK_OBSERVED[@]}" <<'PY'
import datetime, json, sys
path, command, region, context, commit, digest, change, ready, count = sys.argv[1:10]
n = int(count)
rest = sys.argv[10:]
ri = rest.index("--results")
oi = rest.index("--observed")
names = rest[:ri]
results = rest[ri + 1:oi]
observed = rest[oi + 1:]
assert len(names) == len(results) == len(observed) == n
actions = {
    "promote": [
        "promote data tier using the approved runbook",
        "run cutover-check",
        "perform operator-confirmed traffic cutover",
    ],
    "demote": ["confirm traffic has left the standby", "archive this report"],
    "cutover-check": ["perform operator-confirmed traffic cutover"],
    "failback-check": ["perform operator-confirmed traffic and data failback"],
    "verify": [],
}.get(command, ["investigate command failure"])
doc = {
    "schemaVersion": 1,
    "command": command,
    "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
    "targetRegion": region,
    "context": context,
    "gitCommit": commit,
    "manifestDigest": digest,
    "checks": [
        {"name": names[i], "passed": results[i] == "true", "observed": observed[i]}
        for i in range(n)
    ],
    "capacityChange": change,
    "ready": ready == "true",
    "remainingOperatorActions": actions,
}
with open(path, "w", encoding="utf-8") as f:
    json.dump(doc, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY
  mv "$report_tmp" "$DR_REPORT_PATH"
  DR_REPORT_WRITTEN="true"
}

finish() {
  local status="$1"
  trap - EXIT
  if [ "$status" -ne 0 ]; then DR_READY="false"; fi
  if [ "$DR_REPORT_WRITTEN" != "true" ]; then
    write_report || printf 'warning: could not write DR report %s\n' "$DR_REPORT_PATH" >&2
  fi
  if [ -n "$DR_LOCK_DIR" ]; then rmdir "$DR_LOCK_DIR" 2>/dev/null || true; fi
  exit "$status"
}
trap 'finish $?' EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    add_check "required-command-$1" false "not found on PATH"
    echo "Required command not found: $1" >&2
    return 1
  }
}

extract_hpas() {
  python3 -c '
import re, sys
out = {}
for doc in re.split(r"(?m)^---\s*$", sys.stdin.read()):
    kind = re.search(r"(?m)^kind:\s*(\S+)\s*$", doc)
    api = re.search(r"(?m)^apiVersion:\s*(\S+)\s*$", doc)
    if not kind or kind.group(1) != "HorizontalPodAutoscaler": continue
    if not api or api.group(1) != "autoscaling/v2": raise SystemExit("invalid HPA apiVersion")
    name = re.search(r"(?m)^\s+name:\s+(\S+)", doc)
    minimum = re.search(r"(?m)^\s+minReplicas:\s+(\d+)", doc)
    maximum = re.search(r"(?m)^\s+maxReplicas:\s+(\d+)", doc)
    if not name or not minimum or not maximum or name.group(1) in out:
        raise SystemExit("malformed or duplicate HPA")
    out[name.group(1)] = (minimum.group(1), maximum.group(1))
expected={"recsys-api-gateway","recsys-catalog-serving","recsys-model-serving","recsys-online-serving"}
if set(out) != expected: raise SystemExit("unexpected HPA set: "+repr(sorted(out)))
for name in sorted(out): print(name, *out[name])
'
}

filter_hpas_only() {
  python3 -c '
import re, sys
docs = [d for d in re.split(r"(?m)^---\s*$", sys.stdin.read())
        if re.search(r"(?m)^kind:\s*HorizontalPodAutoscaler\s*$", d)]
sys.stdout.write("---".join(docs))
'
}

validate_manifest_structure() {
  python3 -c '
import re, sys
docs=re.split(r"(?m)^---\s*$",sys.stdin.read())
expected={"recsys-api-gateway","recsys-catalog-serving","recsys-model-serving","recsys-online-serving","recsys-outbox-relay"}
images={}
topology=set()
for d in docs:
    kind=re.search(r"(?m)^kind:\s*(\S+)\s*$",d)
    if not kind or kind.group(1)!="Deployment": continue
    name=re.search(r"(?m)^metadata:\s*\n(?:^[ \t]+.*\n)*?^\s+name:\s+(\S+)\s*$",d)
    if not name: raise SystemExit("deployment missing name")
    n=name.group(1)
    vals=re.findall(r"(?m)^\s+image:\s*(\S+)\s*$",d)
    if len(vals)!=1: raise SystemExit("deployment must have exactly one image: "+n)
    m=re.fullmatch(r"[^@\s]+@sha256:([0-9a-f]{64})",vals[0])
    if not m or m.group(1)=="0"*64: raise SystemExit("unpinned or placeholder image: "+n)
    images[n]=m.group(1)
    if (re.search(r"(?m)^\s+topologySpreadConstraints:\s*$",d)
        and re.search(r"(?m)^\s+topologyKey:\s*topology\.kubernetes\.io/zone\s*$",d)
        and re.search(r"(?m)^\s+whenUnsatisfiable:\s*DoNotSchedule\s*$",d)):
        topology.add(n)
if set(images)!=expected: raise SystemExit("unexpected deployment set: "+repr(sorted(images)))
if len(set(images.values()))!=1: raise SystemExit("workload digest mismatch")
if topology != expected-{"recsys-outbox-relay"}: raise SystemExit("missing topology spread constraints")
print(next(iter(images.values())))
'
}

render_overlay() {
  kubectl kustomize "$1"
}

validate_context_region() {
  local current
  if [ "$DR_REGION" != "us-west-2" ] || [ "$DR_CONTEXT" != "prod-us-west-2" ]; then
    add_check context-region false "context=$DR_CONTEXT region=$DR_REGION"
    echo "Context '$DR_CONTEXT' is not explicitly bound to region '$DR_REGION'" >&2
    return 1
  fi
  current="$(kubectl config current-context)"
  if [ "$current" != "$DR_CONTEXT" ]; then
    add_check context-region false "requested=$DR_CONTEXT current=$current"
    echo "Current Kubernetes context does not match --context" >&2
    return 1
  fi
  add_check context-region true "context=$DR_CONTEXT region=$DR_REGION"
}

validate_image_identity() {
  local active_digest standby_digest primary_digest rendered
  rendered="$(render_overlay "$ACTIVE_OVERLAY")"
  DR_MANIFEST_DIGEST="$(printf '%s' "$rendered" | shasum -a 256 | awk '{print $1}')"
  active_digest="$(printf '%s' "$rendered" | validate_manifest_structure)" || {
    add_check manifest-structure false "active overlay invalid"; return 1; }
  standby_digest="$(render_overlay "$STANDBY_OVERLAY" | validate_manifest_structure)" || {
    add_check manifest-structure false "standby overlay invalid"; return 1; }
  primary_digest="$(render_overlay "$PRIMARY_OVERLAY" | validate_manifest_structure)" || {
    add_check manifest-structure false "primary overlay invalid"; return 1; }
  if [ "$active_digest" != "$standby_digest" ] || [ "$active_digest" != "$primary_digest" ]; then
    add_check image-identity false "primary=$primary_digest active=$active_digest standby=$standby_digest"
    echo "Rendered DR overlays have a placeholder, missing, or inconsistent image identity" >&2
    return 1
  fi
  add_check manifest-structure true "exact workloads, HPAs, and topology constraints"
  add_check image-identity true "sha256:$active_digest"
}

desired_hpas() {
  render_overlay "$1" | extract_hpas
}

observed_hpas() {
  kubectl --context "$DR_CONTEXT" get hpa --namespace "$DR_NAMESPACE" \
    -o 'jsonpath={range .items[*]}{.metadata.name}{" "}{.spec.minReplicas}{" "}{.spec.maxReplicas}{"\n"}{end}' |
    python3 -c '
import sys
expected={"recsys-api-gateway","recsys-catalog-serving","recsys-model-serving","recsys-online-serving"}
out={}
for line in sys.stdin:
    parts=line.split()
    if not parts or parts[0] not in expected: continue
    if len(parts)!=3 or parts[0] in out or not all(x.isdigit() for x in parts[1:]):
        raise SystemExit("malformed or duplicate observed target HPA")
    out[parts[0]]=parts[1:]
if set(out)!=expected: raise SystemExit("missing observed target HPA")
for name in sorted(out): print(name,*out[name])
'
}

acquire_lock() {
  local lock_key lock_candidate
  lock_key="$(printf '%s/%s' "$DR_CONTEXT" "$DR_NAMESPACE" | shasum -a 256 | awk '{print $1}')"
  lock_candidate="${TMPDIR:-/tmp}/recsys-dr-$lock_key.lock"
  if ! mkdir "$lock_candidate" 2>/dev/null; then
    add_check operation-lock false "another capacity operation holds $DR_CONTEXT/$DR_NAMESPACE"
    return 1
  fi
  DR_LOCK_DIR="$lock_candidate"
  add_check operation-lock true "$DR_CONTEXT/$DR_NAMESPACE"
}

apply_hpas_if_needed() {
  local overlay="$1" desired observed
  desired="$(desired_hpas "$overlay")"
  observed="$(observed_hpas)"
  DR_MANIFEST_DIGEST="$(render_overlay "$overlay" | shasum -a 256 | awk '{print $1}')"
  if [ "$DR_DRY_RUN" = "true" ]; then
    DR_CAPACITY_CHANGE="dry-run"
    render_overlay "$overlay" | filter_hpas_only |
      kubectl --context "$DR_CONTEXT" apply --dry-run=server -f - >/dev/null || {
        add_check server-dry-run false "API validation failed"; return 1; }
    add_check server-dry-run true "HPA documents accepted"
    if [ "$desired" != "$observed" ]; then
      add_check hpa-capacity false "live differs: [$observed], desired: [$desired]"
      return 1
    fi
    add_check hpa-capacity true "live already converged"
    return 0
  fi
  if [ "$desired" = "$observed" ]; then
    DR_CAPACITY_CHANGE="none"
    add_check hpa-capacity true "already converged: $desired"
    return 0
  fi
  DR_CAPACITY_CHANGE="attempted"
  if ! render_overlay "$overlay" | filter_hpas_only |
      kubectl --context "$DR_CONTEXT" apply -f - >/dev/null; then
    add_check hpa-apply false "apply failed"; return 1
  fi
  observed="$(observed_hpas)"
  if [ "$desired" != "$observed" ]; then
    add_check hpa-convergence false "post-apply live=[$observed], desired=[$desired]"
    return 1
  fi
  DR_CAPACITY_CHANGE="applied"
  add_check hpa-convergence true "$observed"
}

check_rollout() {
  if kubectl --context "$DR_CONTEXT" rollout status deployment --all \
      --namespace "$DR_NAMESPACE" --timeout=5m >/dev/null; then
    add_check rollout true "all deployments rolled out"
  else
    add_check rollout false "rollout status failed or timed out"
    return 1
  fi
}

check_ready_replicas() {
  if kubectl --context "$DR_CONTEXT" wait pod --all --namespace "$DR_NAMESPACE" \
      --for=condition=Ready --timeout=5m >/dev/null; then
    add_check ready-replicas true "all pods Ready"
  else
    add_check ready-replicas false "pod readiness failed or timed out"
    return 1
  fi
}

check_pdbs() {
  local evidence result
  evidence="$(kubectl --context "$DR_CONTEXT" get pdb --namespace "$DR_NAMESPACE" \
    -o 'jsonpath={range .items[*]}{.metadata.name}{" "}{.status.disruptionsAllowed}{" "}{.status.currentHealthy}{" "}{.status.desiredHealthy}{"\n"}{end}')"
  if ! result="$(python3 -c '
import sys
command=sys.argv[1]
expected={"recsys-api-gateway-pdb","recsys-catalog-serving-pdb",
          "recsys-model-serving-pdb","recsys-online-serving-pdb"}
seen={}
for line in sys.stdin:
    p=line.split()
    if len(p)!=4 or p[0] in seen or not all(x.isdigit() for x in p[1:]):
        raise SystemExit("malformed or duplicate PDB evidence")
    seen[p[0]]=tuple(map(int,p[1:]))
if set(seen)!=expected: raise SystemExit("unexpected PDB set")
for name,(allowed,current,desired) in seen.items():
    if current<desired or (command=="promote" and allowed<1):
        raise SystemExit("unhealthy PDB "+name)
print("exact PDB set healthy")
' "$DR_COMMAND" <<<"$evidence")"; then
    add_check pdb-health false "${evidence:-no PDB evidence}"
    return 1
  fi
  add_check pdb-health true "$result: $evidence"
}

check_dependencies() {
  local probe="${DR_DEPENDENCY_PROBE:-}"
  local result
  if [ -z "$probe" ] || [ ! -x "$probe" ]; then
    add_check dependencies false "DR_DEPENDENCY_PROBE is not an executable"
    return 1
  fi
  if ! result="$("$probe" "$DR_CONTEXT" "$DR_REGION" 2>&1)"; then
    add_check dependencies false "$probe: $result"; return 1
  fi
  add_check dependencies true "$probe: $result"
}

check_services() {
  local service port path result
  for service in api-gateway catalog-serving model-serving online-serving; do
    case "$service" in
      api-gateway) port=80; path=/health ;;
      catalog-serving) port=6010; path=/health/ready ;;
      model-serving) port=8080; path=/health/ready ;;
      online-serving) port=7010; path=/health/ready ;;
    esac
    if ! result="$(kubectl --context "$DR_CONTEXT" get --raw \
      "/api/v1/namespaces/$DR_NAMESPACE/services/recsys-$service:$port/proxy$path" \
      --request-timeout=10s)"; then
      add_check "service-$service" false "probe failed"; return 1
    fi
    if ! python3 -c 'import json,sys; assert json.load(sys.stdin).get("status")=="UP"' <<<"$result"; then
      add_check "service-$service" false "$result"; return 1
    fi
    add_check "service-$service" true "$result"
  done
}

check_topology_scheduling() {
  local zones zone_count
  zones="$(kubectl --context "$DR_CONTEXT" get nodes \
    -l "topology.kubernetes.io/region=$DR_REGION" \
    -o 'jsonpath={range .items[?(@.spec.unschedulable!=true)]}{.metadata.labels.topology\.kubernetes\.io/zone}{"\n"}{end}')"
  zone_count="$(printf '%s\n' "$zones" | awk 'NF{seen[$0]=1} END{print length(seen)}')"
  if [ "$zone_count" -lt 2 ]; then
    add_check topology-schedulability false "${zones:-no schedulable zones}"
    return 1
  fi
  add_check topology-schedulability true "$zones"
}

verify_drift() {
  local active base
  active="$(render_overlay "$ACTIVE_OVERLAY" | extract_hpas)"
  base="$(render_overlay "$BASE_OVERLAY" | extract_hpas)"
  DR_MANIFEST_DIGEST="$(render_overlay "$ACTIVE_OVERLAY" | shasum -a 256 | awk '{print $1}')"
  if [ "$active" != "$base" ]; then
    add_check overlay-drift false "active=[$active] base=[$base]"
    echo "DRIFT: active overlay minReplicas differ from base" >&2
    return 1
  fi
  add_check overlay-drift true "$active"
  DR_READY="true"
  echo "OK: active overlay minReplicas match the primary baseline:"
  printf '%s\n' "$active"
}

check_operator_evidence() {
  local parsed writer direction lag traffic health evidence_digest
  local expected_writer expected_direction expected_traffic
  if [ -z "$DR_EVIDENCE_PATH" ] || [ ! -f "$DR_EVIDENCE_PATH" ]; then
    add_check operator-evidence false "missing --evidence JSON file"; return 1
  fi
  if ! parsed="$(python3 - "$DR_EVIDENCE_PATH" <<'PY'
import datetime,json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
required={"source","observedAt","writerIdentity","replicationDirection","lagStatus",
          "rpoAccepted","trafficTarget","health","manifestDigest"}
if set(d)!=required or not isinstance(d["source"],str) or not d["source"].strip():
    raise SystemExit("malformed evidence schema/source")
t=datetime.datetime.fromisoformat(d["observedAt"].replace("Z","+00:00"))
now=datetime.datetime.now(datetime.timezone.utc)
if t.tzinfo is None or not (datetime.timedelta(0) <= now-t <= datetime.timedelta(minutes=15)):
    raise SystemExit("stale or future evidence")
if d["rpoAccepted"] is not True: raise SystemExit("RPO not accepted")
for k in ("writerIdentity","replicationDirection","lagStatus","trafficTarget","health","manifestDigest"):
    if not isinstance(d[k],str) or not d[k]: raise SystemExit("invalid "+k)
print("\t".join(d[k] for k in ("writerIdentity","replicationDirection","lagStatus","trafficTarget","health","manifestDigest")))
PY
)"; then
    add_check operator-evidence false "malformed, stale, future, or incomplete"; return 1
  fi
  IFS=$'\t' read -r writer direction lag traffic health evidence_digest <<<"$parsed"
  if [ "$DR_COMMAND" = "cutover-check" ]; then
    expected_writer="us-west-2"; expected_direction="west-to-east"; expected_traffic="us-west-2"
  else
    expected_writer="us-east-1"; expected_direction="east-to-west"; expected_traffic="us-east-1"
  fi
  if [ "$writer" != "$expected_writer" ]; then
    add_check writer-identity false "$writer (expected $expected_writer)"
    return 1
  fi
  add_check writer-identity true "$writer"
  if [ "$direction" != "$expected_direction" ]; then
    add_check replication-direction false "$direction (expected $expected_direction)"
    return 1
  fi
  add_check replication-direction true "$direction"
  if [ "$lag" != "accepted" ]; then
    add_check replication-lag false "$lag"
    return 1
  fi
  add_check replication-lag true "$lag"
  if [ "$traffic" != "$expected_traffic" ]; then
    add_check traffic-target false "$traffic (expected $expected_traffic)"
    return 1
  fi
  add_check traffic-target true "$traffic"
  if [ "$health" != "healthy" ]; then
    add_check evidence-health false "$health"
    return 1
  fi
  add_check evidence-health true "$health"
  if [ "$evidence_digest" != "$DR_MANIFEST_DIGEST" ]; then
    add_check evidence-manifest-digest false "$evidence_digest"; return 1
  fi
  add_check evidence-manifest-digest true "$evidence_digest"
  if [ "$(desired_hpas "$ACTIVE_OVERLAY")" != "$(observed_hpas)" ]; then
    add_check application-capacity false "live HPA min/max differ from active overlay"; return 1
  fi
  add_check application-capacity true "independently observed exact live min/max"
  check_topology_scheduling
  check_services
  check_dependencies
}

[ -n "$DR_COMMAND" ] || usage
shift || true
while [ $# -gt 0 ]; do
  case "$1" in
    --context) [ $# -ge 2 ] || usage; DR_CONTEXT="$2"; shift 2 ;;
    --region) [ $# -ge 2 ] || usage; DR_REGION="$2"; shift 2 ;;
    --report) [ $# -ge 2 ] || usage; DR_REPORT_PATH="$2"; shift 2 ;;
    --evidence) [ $# -ge 2 ] || usage; DR_EVIDENCE_PATH="$2"; shift 2 ;;
    --dry-run) DR_DRY_RUN="true"; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done

require_command kubectl
require_command python3

case "$DR_COMMAND" in
  verify)
    [ -z "$DR_CONTEXT" ] || { echo "verify is offline and does not accept --context" >&2; exit 2; }
    verify_drift
    ;;
  promote|demote)
    [ -n "$DR_CONTEXT" ] && [ -n "$DR_REGION" ] || usage
    validate_context_region
    acquire_lock
    validate_image_identity
    if [ "$DR_COMMAND" = "promote" ]; then overlay="$ACTIVE_OVERLAY"; else overlay="$STANDBY_OVERLAY"; fi
    apply_hpas_if_needed "$overlay"
    check_rollout
    check_ready_replicas
    check_pdbs
    check_topology_scheduling
    check_services
    check_dependencies
    [ "$(desired_hpas "$overlay")" = "$(observed_hpas)" ] || {
      add_check final-hpa-convergence false "capacity changed during readiness checks"; exit 1; }
    add_check final-hpa-convergence true "exact desired min/max retained"
    DR_READY="true"
    ;;
  cutover-check|failback-check)
    [ -n "$DR_CONTEXT" ] && [ -n "$DR_REGION" ] || usage
    [ "$DR_DRY_RUN" = "false" ] || { echo "--dry-run is not meaningful for read-only checks" >&2; exit 2; }
    validate_context_region
    validate_image_identity
    check_operator_evidence
    DR_READY="true"
    ;;
  -h|--help) usage ;;
  *) echo "Unknown command: $DR_COMMAND" >&2; usage ;;
esac

write_report
