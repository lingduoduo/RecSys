#!/usr/bin/env bash
# Pin (or check) the ElastiCache eviction policy that the EKS regions depend on.
#
# In EKS the in-cluster Redis StatefulSets are scaled to 0 and ElastiCache serves instead,
# so the `volatile-lru` pinned in k8s/base/redis-cluster.yaml does not apply there. This
# script is how that same invariant reaches production.
#
# Why it is an invariant and not tuning: every cache-like key in this system sets an
# explicit TTL (Flink features via SETEX, top-K via EXPIRE, the registry via PX, sharded
# records via EXPIRE), so the keys *without* a TTL are exactly the authoritative ones —
# shard:topology and the classpath-seeded embeddings. Under an allkeys-* policy those are
# eviction candidates, and an evicted shard:topology is silently recreated at version 1 by
# ShardTopologyStore.bootstrap, resetting a resharded cluster's generation.
#
# Follows the same out-of-band convention as scripts/create-cdn-distribution.sh and
# docs/runbooks/waf-webacl.md — this repo has no IaC.
#
# Usage:
#   PARAMETER_GROUP=recsys-redis7 AWS_REGION=us-east-1 ./scripts/set-elasticache-parameters.sh verify
#   PARAMETER_GROUP=recsys-redis7 AWS_REGION=us-east-1 ./scripts/set-elasticache-parameters.sh apply
#
# Run it once per region: Global Datastore replicates data, not parameter groups, so the
# us-west-2 DR group must be set independently (it is promoted to primary on failover).
#
# maxmemory-policy is a dynamic parameter — it takes effect without a cluster reboot.
set -euo pipefail

REQUIRED_POLICY="volatile-lru"
MODE="${1:-}"

usage() {
  cat >&2 <<EOF
Usage: PARAMETER_GROUP=<custom-group> [AWS_REGION=<region>] $0 apply|verify

  apply   set maxmemory-policy=$REQUIRED_POLICY on the parameter group, then verify
  verify  read the policy back; exit 1 if it would evict keys that have no TTL

PARAMETER_GROUP must be a CUSTOM group — AWS rejects edits to default.* groups.
EOF
  exit 2
}

[ -n "${PARAMETER_GROUP:-}" ] || usage
case "$MODE" in
  apply|verify) ;;
  *) usage ;;
esac

aws_elasticache() {
  if [ -n "${AWS_REGION:-}" ]; then
    aws elasticache "$@" --region "$AWS_REGION"
  else
    aws elasticache "$@"
  fi
}

current_policy() {
  aws_elasticache describe-cache-parameters \
    --cache-parameter-group-name "$PARAMETER_GROUP" \
    --query "Parameters[?ParameterName=='maxmemory-policy'].ParameterValue" \
    --output text | tr -d '[:space:]'
}

# Anything that confines eviction to TTL-bearing keys is acceptable; noeviction is stricter
# still (it evicts nothing and fails writes instead), so it also satisfies the invariant.
policy_protects_keys_without_ttl() {
  case "$1" in
    volatile-*|noeviction) return 0 ;;
    *) return 1 ;;
  esac
}

if [ "$MODE" = "apply" ]; then
  case "$PARAMETER_GROUP" in
    default.*)
      echo "ERROR: '$PARAMETER_GROUP' is an AWS-managed default group and cannot be modified." >&2
      echo "       Create a custom group (aws elasticache create-cache-parameter-group)," >&2
      echo "       attach it to the replication group, then re-run with PARAMETER_GROUP set to it." >&2
      exit 2
      ;;
  esac

  echo "Setting maxmemory-policy=$REQUIRED_POLICY on $PARAMETER_GROUP${AWS_REGION:+ (${AWS_REGION})}"
  aws_elasticache modify-cache-parameter-group \
    --cache-parameter-group-name "$PARAMETER_GROUP" \
    --parameter-name-values "ParameterName=maxmemory-policy,ParameterValue=$REQUIRED_POLICY" \
    >/dev/null
fi

POLICY="$(current_policy)"
if [ -z "$POLICY" ]; then
  echo "ERROR: could not read maxmemory-policy from '$PARAMETER_GROUP'." >&2
  exit 1
fi

if policy_protects_keys_without_ttl "$POLICY"; then
  echo "OK: $PARAMETER_GROUP has maxmemory-policy=$POLICY — keys without a TTL cannot be evicted."
  exit 0
fi

cat >&2 <<EOF
FAIL: $PARAMETER_GROUP has maxmemory-policy=$POLICY.

That policy can evict keys that have no TTL — shard:topology and the classpath-seeded
embeddings. An evicted shard:topology is silently recreated at version 1, resetting a
resharded cluster's generation and addressing data under the wrong key prefix.

Fix: PARAMETER_GROUP=$PARAMETER_GROUP${AWS_REGION:+ AWS_REGION=$AWS_REGION} $0 apply
EOF
exit 1
