#!/usr/bin/env bash
# Promote/demote the us-west-2 DR warm standby's HPA capacity floor.
#
#   scripts/dr-standby-capacity.sh promote --context <us-west-2-ctx> [--dry-run]
#       Raise standby minReplicas to the PRIMARY baseline (k8s/eks-us-west-2-active).
#   scripts/dr-standby-capacity.sh demote  --context <us-west-2-ctx> [--dry-run]
#       Restore the warm-standby floor 1/1/2/1 (k8s/eks-us-west-2).
#   scripts/dr-standby-capacity.sh verify
#       Offline drift check: the active overlay's minReplicas must equal k8s/base's.
#
# See docs/runbooks/dr-regional-failover.md (promote), dr-failback.md (demote),
# and dr-game-day.md (rehearsal).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTIVE_OVERLAY="$REPO_ROOT/k8s/eks-us-west-2-active"
STANDBY_OVERLAY="$REPO_ROOT/k8s/eks-us-west-2"
BASE_OVERLAY="$REPO_ROOT/k8s/base"

usage() {
  echo "Usage: $0 promote --context <ctx> [--dry-run]   # standby -> primary baseline" >&2
  echo "       $0 demote  --context <ctx> [--dry-run]   # restore warm-standby floor" >&2
  echo "       $0 verify                                # offline drift check vs k8s/base" >&2
  exit 2
}

# Reads `kubectl kustomize` YAML on stdin, prints "<hpa-name> <minReplicas>" per HPA, sorted.
#
# NOTE: `python3 - <<'PY' ... PY` would NOT work here: with `-`, python3 reads its
# *script* from stdin, so the heredoc consumes fd 0 for the script text and
# sys.stdin.read() inside the script then sees EOF -- the piped kubectl output is
# silently discarded (and can even SIGPIPE the upstream `kubectl kustomize` once its
# output exceeds the pipe buffer, since the never-read pipe gets closed). Process
# substitution instead gives python3 the script as a file argument, leaving fd 0
# connected to the actual pipe.
extract_min_replicas() {
  python3 <(cat <<'PY'
import sys, re
docs = re.split(r'(?m)^---\s*$', sys.stdin.read())
out = {}
for d in docs:
    if 'kind: HorizontalPodAutoscaler' not in d:
        continue
    name = re.search(r'(?m)^\s+name:\s+(\S+)', d)
    mr = re.search(r'(?m)^\s+minReplicas:\s+(\d+)', d)
    if name and mr:
        out[name.group(1)] = mr.group(1)
for k in sorted(out):
    print(k, out[k])
PY
)
}

do_apply() {
  local overlay="$1" context="$2" dry_run="$3"
  local args=(--context "$context" apply -k "$overlay")
  if [ "$dry_run" = "true" ]; then
    args+=(--dry-run=server)
  fi
  echo "+ kubectl ${args[*]}" >&2
  kubectl "${args[@]}"
}

verify() {
  local active base
  active="$(kubectl kustomize "$ACTIVE_OVERLAY" | extract_min_replicas)"
  base="$(kubectl kustomize "$BASE_OVERLAY" | extract_min_replicas)"
  if [ "$active" = "$base" ]; then
    echo "OK: active overlay minReplicas match the primary baseline (k8s/base):"
    echo "$active"
  else
    echo "DRIFT: k8s/eks-us-west-2-active minReplicas differ from k8s/base:" >&2
    diff <(echo "$base") <(echo "$active") >&2 || true
    exit 1
  fi
}

MODE="${1:-}"
[ -n "$MODE" ] || usage
shift || true

CONTEXT=""
DRY_RUN="false"
while [ $# -gt 0 ]; do
  case "$1" in
    --context) [ $# -ge 2 ] || usage; CONTEXT="$2"; shift 2 ;;
    --dry-run) DRY_RUN="true"; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done

case "$MODE" in
  promote) [ -n "$CONTEXT" ] || { echo "promote requires --context" >&2; usage; }
           do_apply "$ACTIVE_OVERLAY" "$CONTEXT" "$DRY_RUN" ;;
  demote)  [ -n "$CONTEXT" ] || { echo "demote requires --context" >&2; usage; }
           do_apply "$STANDBY_OVERLAY" "$CONTEXT" "$DRY_RUN" ;;
  verify)  verify ;;
  -h|--help) usage ;;
  *) echo "Unknown mode: $MODE" >&2; usage ;;
esac
