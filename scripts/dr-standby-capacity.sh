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
  echo "" >&2
  echo "verify renders overlays locally via 'kubectl kustomize' and never contacts a" >&2
  echo "cluster -- it still requires the kubectl binary on PATH, just no --context/kubeconfig." >&2
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
#
# Both this extractor and filter_hpas_only() below assume kustomize's per-document field
# order (`kind:` near the top, `metadata.name` before `spec.minReplicas`) and key off the
# literal `kind: HorizontalPodAutoscaler` marker -- brittle to future kustomize output
# changes (field reordering, added name prefixes/suffixes, relabeling). Documentation only.
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

# Reads `kubectl kustomize` YAML on stdin, re-emits (joined by `---`) only the documents
# containing `kind: HorizontalPodAutoscaler` -- everything else (Deployments, ConfigMaps,
# Services, the image transformer's output, ...) is dropped. Same process-substitution
# pattern as extract_min_replicas above (see the NOTE there) so fd 0 stays connected to
# the piped kubectl output instead of being consumed by a script heredoc.
filter_hpas_only() {
  python3 <(cat <<'PY'
import sys, re
docs = re.split(r'(?m)^---\s*$', sys.stdin.read())
hpas = [d for d in docs if 'kind: HorizontalPodAutoscaler' in d]
sys.stdout.write('---'.join(hpas))
PY
)
}

# Applies ONLY the HorizontalPodAutoscaler documents rendered by an overlay -- never the
# Deployments/ConfigMaps/Services or the image transformer's output. `kubectl apply -k
# <overlay>` would roll the standby Deployments to whatever image digest is currently
# committed in that overlay's kustomization.yaml, which for k8s/eks-us-west-2* is a
# placeholder (sha256:0000...) pinned out-of-band by scripts/set-eks-image-digest.sh at
# deploy time, not by promote/demote. Scoping to HPAs avoids an ImagePullBackOff footgun
# during failover/failback.
do_apply() {
  local overlay="$1" context="$2" dry_run="$3"
  local args=(--context "$context" apply -f -)
  if [ "$dry_run" = "true" ]; then
    args+=(--dry-run=server)
  fi
  echo "+ kubectl kustomize $overlay | <HPA-only filter> | kubectl ${args[*]}" >&2
  kubectl kustomize "$overlay" | filter_hpas_only | kubectl "${args[@]}"
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
