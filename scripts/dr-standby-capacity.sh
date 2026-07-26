#!/usr/bin/env bash
# Idempotent, HPA-only preparation and read-only evidence checks for the us-west-2 DR region.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTIVE_OVERLAY="$REPO_ROOT/k8s/eks-us-west-2-active"
STANDBY_OVERLAY="$REPO_ROOT/k8s/eks-us-west-2"
BASE_OVERLAY="$REPO_ROOT/k8s/base"
DR_NAMESPACE="recsys"
DR_COMMAND="${1:-}"
DR_CONTEXT=""
DR_REGION=""
DR_REPORT_PATH=""
DR_DRY_RUN="false"
DR_CAPACITY_CHANGE="none"
DR_READY="false"
DR_MANIFEST_DIGEST="unknown"
DR_REPORT_WRITTEN="false"
DR_CHECK_NAMES=()
DR_CHECK_RESULTS=()
DR_CHECK_OBSERVED=()

usage() {
  cat >&2 <<EOF
Usage: $0 promote|demote --context NAME --region us-west-2 [--report FILE] [--dry-run]
       $0 cutover-check|failback-check --context NAME --region us-west-2 [--report FILE]
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
    if "kind: HorizontalPodAutoscaler" not in doc: continue
    name = re.search(r"(?m)^\s+name:\s+(\S+)", doc)
    minimum = re.search(r"(?m)^\s+minReplicas:\s+(\d+)", doc)
    if name and minimum: out[name.group(1)] = minimum.group(1)
for name in sorted(out): print(name, out[name])
'
}

filter_hpas_only() {
  python3 -c '
import re, sys
docs = [d for d in re.split(r"(?m)^---\s*$", sys.stdin.read())
        if "kind: HorizontalPodAutoscaler" in d]
sys.stdout.write("---".join(docs))
'
}

extract_images() {
  python3 -c '
import re, sys
images = sorted(set(re.findall(r"(?m)^\s*image:\s*[\"'\'']?([^\"'\'']+\S)", sys.stdin.read())))
for image in images: print(image)
'
}

render_overlay() {
  kubectl kustomize "$1"
}

validate_context_region() {
  local current
  if [ "$DR_REGION" != "us-west-2" ] || [[ "$DR_CONTEXT" != *"$DR_REGION"* ]]; then
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
  local active_images standby_images
  active_images="$(render_overlay "$ACTIVE_OVERLAY" | extract_images)"
  standby_images="$(render_overlay "$STANDBY_OVERLAY" | extract_images)"
  if [ -z "$active_images" ] ||
     printf '%s\n' "$active_images" | grep -Eq 'sha256:0{64}([[:space:]]|$)' ||
     [ "$active_images" != "$standby_images" ]; then
    add_check image-identity false "active=$active_images standby=$standby_images"
    echo "Rendered DR overlays have a placeholder, missing, or inconsistent image identity" >&2
    return 1
  fi
  add_check image-identity true "$active_images"
}

desired_hpas() {
  render_overlay "$1" | extract_hpas
}

observed_hpas() {
  kubectl --context "$DR_CONTEXT" get hpa --namespace "$DR_NAMESPACE" \
    -o 'jsonpath={range .items[*]}{.metadata.name}{" "}{.spec.minReplicas}{"\n"}{end}' |
    LC_ALL=C sort
}

apply_hpas_if_needed() {
  local overlay="$1" desired observed
  desired="$(desired_hpas "$overlay")"
  observed="$(observed_hpas)"
  DR_MANIFEST_DIGEST="$(render_overlay "$overlay" | shasum -a 256 | awk '{print $1}')"
  if [ "$desired" = "$observed" ]; then
    DR_CAPACITY_CHANGE="none"
    add_check hpa-capacity true "already converged: $desired"
    return 0
  fi
  if [ "$DR_DRY_RUN" = "true" ]; then
    DR_CAPACITY_CHANGE="dry-run"
    add_check hpa-capacity true "would change from [$observed] to [$desired]"
    return 0
  fi
  DR_CAPACITY_CHANGE="applied"
  render_overlay "$overlay" | filter_hpas_only |
    kubectl --context "$DR_CONTEXT" apply -f - >/dev/null
  add_check hpa-capacity true "changed from [$observed] to [$desired]"
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
  local evidence
  evidence="$(kubectl --context "$DR_CONTEXT" get pdb --namespace "$DR_NAMESPACE" \
    -o 'jsonpath={range .items[*]}{.metadata.name}{" "}{.status.disruptionsAllowed}{" "}{.status.currentHealthy}{" "}{.status.desiredHealthy}{"\n"}{end}')"
  if [ -z "$evidence" ] ||
     ! awk -v require_disruption="$([ "$DR_COMMAND" = promote ] && printf 1 || printf 0)" \
       'NF != 4 || $3 < $4 || (require_disruption && $2 < 1) {bad=1} END {exit bad}' <<<"$evidence"; then
    add_check pdb-health false "${evidence:-no PDB evidence}"
    return 1
  fi
  add_check pdb-health true "$evidence"
}

check_dependencies() {
  local health="${DR_DEPENDENCY_HEALTH:-unknown}"
  if [ "$health" != "healthy" ]; then
    add_check dependencies false "$health"
    return 1
  fi
  add_check dependencies true "$health"
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
  local writer="${DR_WRITER_IDENTITY:-unknown}"
  local direction="${DR_REPLICATION_DIRECTION:-unknown}"
  local lag="${DR_REPLICATION_LAG_STATUS:-unknown}"
  local traffic="${DR_TRAFFIC_TARGET:-unknown}"
  local capacity="${DR_CAPACITY_READY:-unknown}"
  local expected_writer expected_direction expected_traffic
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
  if [ "$capacity" != "ready" ]; then
    add_check application-capacity false "$capacity"
    return 1
  fi
  add_check application-capacity true "$capacity"
  check_dependencies
}

[ -n "$DR_COMMAND" ] || usage
shift || true
while [ $# -gt 0 ]; do
  case "$1" in
    --context) [ $# -ge 2 ] || usage; DR_CONTEXT="$2"; shift 2 ;;
    --region) [ $# -ge 2 ] || usage; DR_REGION="$2"; shift 2 ;;
    --report) [ $# -ge 2 ] || usage; DR_REPORT_PATH="$2"; shift 2 ;;
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
    validate_image_identity
    if [ "$DR_COMMAND" = "promote" ]; then overlay="$ACTIVE_OVERLAY"; else overlay="$STANDBY_OVERLAY"; fi
    apply_hpas_if_needed "$overlay"
    check_rollout
    check_ready_replicas
    check_pdbs
    check_dependencies
    DR_READY="true"
    ;;
  cutover-check|failback-check)
    [ -n "$DR_CONTEXT" ] && [ -n "$DR_REGION" ] || usage
    [ "$DR_DRY_RUN" = "false" ] || { echo "--dry-run is not meaningful for read-only checks" >&2; exit 2; }
    validate_context_region
    check_operator_evidence
    DR_READY="true"
    ;;
  -h|--help) usage ;;
  *) echo "Unknown command: $DR_COMMAND" >&2; usage ;;
esac

write_report
