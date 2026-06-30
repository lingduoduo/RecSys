#!/usr/bin/env bash
# Gracefully and irreversibly decommission the entire Recsys backend.
# Order: gateway drain -> backend drain -> infra teardown -> manifest delete -> verify.
# All runtime state is treated as disposable; there is NO backup step.
set -euo pipefail

NAMESPACE="recsys"
CONTEXT=""
DRY_RUN=false
ASSUME_YES=false
DRAIN_TIMEOUT=60
KEEP_INFRA=false

GATEWAY_DEPLOY="recsys-api-gateway"
BACKEND_DEPLOYS=(recsys-model-serving recsys-online-serving recsys-catalog-serving)
REDIS_LABEL="app=redis"   # redis-primary + redis-replica StatefulSets

usage() {
  cat <<'EOF'
Usage: retire-backend.sh [options]
  --namespace NS       Kubernetes namespace (default: recsys)
  --context CTX        kubectl context (default: current)
  --dry-run            Print commands without executing
  --yes                Skip the typed confirmation (for automation)
  --drain-timeout SEC  Max seconds to wait for a tier to drain (default: 60)
  --keep-infra         Stop before tearing down Redis/cloud infra
  -h, --help           Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) NAMESPACE="$2"; shift 2 ;;
    --context) CONTEXT="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    --yes) ASSUME_YES=true; shift ;;
    --drain-timeout) DRAIN_TIMEOUT="$2"; shift 2 ;;
    --keep-infra) KEEP_INFRA=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

KCTX=()
[[ -n "$CONTEXT" ]] && KCTX=(--context "$CONTEXT")

run() {
  echo "+ $*"
  if [[ "$DRY_RUN" == false ]]; then
    "$@"
  fi
}

kc() { run kubectl ${KCTX[@]+"${KCTX[@]}"} -n "$NAMESPACE" "$@"; }

log() { echo "[retire] $*"; }

# Wait until a deployment has zero ready pods, bounded by DRAIN_TIMEOUT.
verify_drained() {
  local deploy="$1"
  log "verifying $deploy has drained (timeout ${DRAIN_TIMEOUT}s)"
  if [[ "$DRY_RUN" == true ]]; then
    echo "+ (dry-run) would poll '$deploy' until 0 ready pods or timeout"
    return 0
  fi
  local waited=0
  while true; do
    local ready
    ready="$(kubectl ${KCTX[@]+"${KCTX[@]}"} -n "$NAMESPACE" get deploy "$deploy" \
              -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo 0)"
    ready="${ready:-0}"
    local pods
    pods="$(kubectl ${KCTX[@]+"${KCTX[@]}"} -n "$NAMESPACE" get pods \
             -l app="$deploy" --no-headers 2>/dev/null | wc -l | tr -d ' ')"
    if [[ "$ready" == "0" && "$pods" == "0" ]]; then
      log "$deploy fully drained"
      return 0
    fi
    if (( waited >= DRAIN_TIMEOUT )); then
      echo "[retire] ERROR: $deploy did not drain within ${DRAIN_TIMEOUT}s" \
           "(readyReplicas=$ready, pods=$pods). Aborting — no force-kill." >&2
      exit 1
    fi
    sleep 5
    waited=$(( waited + 5 ))
  done
}

phase0_preflight() {
  log "PHASE 0: pre-flight"
  run kubectl ${KCTX[@]+"${KCTX[@]}"} config current-context
  kc get deploy -o wide || true
  log "All runtime state is DISPOSABLE. This is IRREVERSIBLE."
  if [[ "$ASSUME_YES" == false && "$DRY_RUN" == false ]]; then
    read -r -p "Type the namespace ('$NAMESPACE') to confirm retirement: " answer
    if [[ "$answer" != "$NAMESPACE" ]]; then
      echo "[retire] confirmation mismatch; aborting." >&2
      exit 1
    fi
  fi
}

phase1_gateway() {
  log "PHASE 1: drain gateway ($GATEWAY_DEPLOY)"
  kc scale deploy "$GATEWAY_DEPLOY" --replicas=0
  verify_drained "$GATEWAY_DEPLOY"
  log "Gateway drained — no new external traffic can reach any backend."
}

phase2_backends() {
  log "PHASE 2: drain backends (${BACKEND_DEPLOYS[*]})"
  for d in "${BACKEND_DEPLOYS[@]}"; do
    kc scale deploy "$d" --replicas=0
  done
  for d in "${BACKEND_DEPLOYS[@]}"; do
    verify_drained "$d"
  done
}

phase3_infra() {
  if [[ "$KEEP_INFRA" == true ]]; then
    log "PHASE 3: skipped (--keep-infra)"
    return 0
  fi
  log "PHASE 3: tear down infra (StatefulSets labeled $REDIS_LABEL)"
  log "NOTE: stop the Flink streaming jobs (separate repo Recsys-Streaming-Pipeline)" \
      "BEFORE their Kafka brokers — see runbook."
  kc delete statefulset -l "$REDIS_LABEL" --ignore-not-found
}

phase4_manifests() {
  log "PHASE 4: delete manifests"
  if [[ -d k8s/base ]]; then
    run kubectl ${KCTX[@]+"${KCTX[@]}"} delete -k k8s/base --ignore-not-found
  else
    log "k8s/base not found in CWD; delete manifests manually per runbook."
  fi
  log "Remove Cloud Map registrations / ALB target groups / IRSA per runbook (aws CLI)."
}

phase5_verify() {
  log "PHASE 5: verify clean state"
  kc get all || true
  log "Teardown report complete. Review output above for any remaining resources."
}

main() {
  phase0_preflight
  phase1_gateway
  phase2_backends
  phase3_infra
  phase4_manifests
  phase5_verify
  log "DONE."
}

main "$@"
