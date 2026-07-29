#!/usr/bin/env bash
# Empirically demonstrate the eviction invariant that k8s/base/redis-cluster.yaml and
# scripts/set-elasticache-parameters.sh exist to enforce — with no AWS account.
#
# This repo is a research/demo system, so the ElastiCache-side claim ("volatile-lru protects
# the keys that have no TTL") would otherwise be asserted in prose and never executed. This
# runs it against a real redis-server on a throwaway port and reports what actually happens.
#
# Four scenarios under identical memory pressure:
#
#   1. volatile-lru + TTL'd pressure  — the shipped config. Authoritative keys must survive.
#   2. allkeys-lru  + TTL'd pressure  — the config before this change. Shows the same
#                                       authoritative keys being evicted, which is the bug:
#                                       an evicted shard:topology is silently recreated at
#                                       version 1 by ShardTopologyStore.bootstrap.
#   3. volatile-lru + untl'd pressure — the deliberate trade (02_Caching sharp edge 6):
#                                       with nothing evictable, writes are refused with OOM
#                                       instead of state being dropped.
#   4. the same writes issued inside ONE Lua script — Redis evaluates the OOM state once, at
#                                       script dispatch, and does not re-check per redis.call,
#                                       so a script overshoots maxmemory instead of being
#                                       refused. The mechanism is real: the Flink sinks in
#                                       OnlineFeatureStreamingJob write through Lua. But the
#                                       15.8 MB figure below comes from this script's synthetic
#                                       3000-key EVAL, which no sink issues — those scripts touch
#                                       5 keys or top-k members (default 10) per invocation, so
#                                       the practical overshoot is kilobytes, not a reason to
#                                       resize maxmemory.
#
# Pressure is applied as ordinary client commands (redis-cli reading a command file), because
# that is what the serving path does and what maxmemory is enforced against — see scenario 4
# for why generating the load inside a script would measure something else entirely.
#
# What this does NOT simulate: the ElastiCache control plane (parameter groups, Multi-AZ
# failover, Global Datastore). See docs/runbooks/elasticache-local.md.
#
# Usage:
#   ./scripts/simulate-elasticache-eviction.sh [--port 6399] [--maxmemory 8mb]
set -euo pipefail

PORT=6399
MAXMEMORY=8mb
DURABLE_EMBEDDINGS=50
FILLER_KEYS=3000
VALUE_BYTES=4096

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --maxmemory) MAXMEMORY="$2"; shift 2 ;;
    *) echo "Usage: $0 [--port N] [--maxmemory SIZE]" >&2; exit 2 ;;
  esac
done

command -v redis-server >/dev/null || { echo "ERROR: redis-server not on PATH" >&2; exit 2; }
command -v redis-cli >/dev/null || { echo "ERROR: redis-cli not on PATH" >&2; exit 2; }

TMP="$(mktemp -d)"
REDIS_PID=""
cleanup() {
  [ -n "$REDIS_PID" ] && kill "$REDIS_PID" 2>/dev/null || true
  rm -rf "$TMP"
}
trap cleanup EXIT

cli() { redis-cli -p "$PORT" "$@"; }
PAYLOAD="$(head -c "$VALUE_BYTES" /dev/zero | tr '\0' 'x')"

start_redis() { # start_redis <policy>
  if [ -n "$REDIS_PID" ]; then
    kill "$REDIS_PID" 2>/dev/null || true
    wait "$REDIS_PID" 2>/dev/null || true
  fi
  redis-server --port "$PORT" --maxmemory "$MAXMEMORY" --maxmemory-policy "$1" \
    --save '' --appendonly no --dir "$TMP" >"$TMP/redis.log" 2>&1 &
  REDIS_PID=$!
  for _ in $(seq 1 50); do
    if cli ping >/dev/null 2>&1; then return 0; fi
    sleep 0.1
  done
  echo "ERROR: redis-server did not come up on :$PORT" >&2
  cat "$TMP/redis.log" >&2
  exit 1
}

# The keys this system writes with NO TTL — the authoritative ones.
seed_authoritative_keys() {
  cli set shard:topology '{"version":7,"shardCount":8,"vnodes":150}' >/dev/null
  for i in $(seq 1 "$DURABLE_EMBEDDINGS"); do
    echo "set i2vEmb:$i ${PAYLOAD:0:256}"
  done >"$TMP/seed.txt"
  cli <"$TMP/seed.txt" >/dev/null
}

surviving_authoritative() {
  cli eval "
    local n = 0
    if redis.call('EXISTS','shard:topology') == 1 then n = n + 1 end
    for i=1,tonumber(ARGV[1]) do
      if redis.call('EXISTS','i2vEmb:'..i) == 1 then n = n + 1 end
    end
    return n" 0 "$DURABLE_EMBEDDINGS"
}

topology_survived() { # the key the whole argument rests on
  if [ "$(cli exists shard:topology)" = "1" ]; then echo "yes"; else echo "NO"; fi
}

evicted_keys() { cli info stats | awk -F: '/^evicted_keys:/ {gsub(/\r/,"",$2); print $2}'; }
used_memory_mb() { cli info memory | awk -F: '/^used_memory:/ {gsub(/\r/,"",$2); printf "%.1f", $2/1048576}'; }

# Ordinary client commands: each is dispatched separately, so maxmemory is enforced per
# command — eviction runs, and a write that cannot be satisfied is refused with OOM.
apply_pressure() { # apply_pressure ttl|nottl -> echoes the number of OOM rejections
  {
    for i in $(seq 1 "$FILLER_KEYS"); do
      if [ "$1" = "ttl" ]; then
        echo "setex filler:$i 300 $PAYLOAD"
      else
        echo "set durablefill:$i $PAYLOAD"
      fi
    done
  } >"$TMP/pressure.txt"
  cli <"$TMP/pressure.txt" 2>&1 | grep -c "OOM" || true
}

# The same writes, but issued from inside a single Lua script.
apply_pressure_via_lua() {
  cli eval "for i=1,tonumber(ARGV[1]) do redis.call('SET','luafill:'..i,ARGV[2]) end return 1" \
    0 "$FILLER_KEYS" "$PAYLOAD" >/dev/null 2>&1 || true
}

TOTAL=$((DURABLE_EMBEDDINGS + 1))
FAILURES=0
printf '\n=== ElastiCache eviction simulation (maxmemory=%s, %d authoritative keys) ===\n\n' \
  "$MAXMEMORY" "$TOTAL"
printf '%-38s %8s %7s %9s %8s  %s\n' scenario evicted refused "authorit." "used MB" outcome
printf '%-38s %8s %7s %9s %8s  %s\n' "--------------------------------------" -------- ------- --------- -------- -------

# --- 1. volatile-lru + TTL'd pressure: the shipped config ---------------------------------
start_redis volatile-lru
seed_authoritative_keys
REFUSED1="$(apply_pressure ttl)"
EVICTED1="$(evicted_keys)"; SURV1="$(surviving_authoritative)"; MEM1="$(used_memory_mb)"
if [ "$SURV1" -eq "$TOTAL" ] && [ "$EVICTED1" -gt 0 ]; then RESULT1="protected"
else RESULT1="UNEXPECTED"; FAILURES=$((FAILURES + 1)); fi
printf '%-38s %8s %7s %9s %8s  %s\n' "1. volatile-lru, TTL'd pressure" \
  "$EVICTED1" "$REFUSED1" "$SURV1/$TOTAL" "$MEM1" "$RESULT1"

# --- 2. allkeys-lru + TTL'd pressure: the config this change replaced ----------------------
start_redis allkeys-lru
seed_authoritative_keys
REFUSED2="$(apply_pressure ttl)"
EVICTED2="$(evicted_keys)"; SURV2="$(surviving_authoritative)"; MEM2="$(used_memory_mb)"
TOPOLOGY2="$(topology_survived)"
if [ "$SURV2" -lt "$TOTAL" ]; then RESULT2="LOST STATE"; else RESULT2="survived this run"; fi
printf '%-38s %8s %7s %9s %8s  %s\n' "2. allkeys-lru, TTL'd pressure" \
  "$EVICTED2" "$REFUSED2" "$SURV2/$TOTAL" "$MEM2" "$RESULT2"

# --- 3. volatile-lru + untl'd pressure: the deliberate OOM trade ---------------------------
start_redis volatile-lru
seed_authoritative_keys
REFUSED3="$(apply_pressure nottl)"
EVICTED3="$(evicted_keys)"; SURV3="$(surviving_authoritative)"; MEM3="$(used_memory_mb)"
if [ "$REFUSED3" -gt 0 ] && [ "$SURV3" -eq "$TOTAL" ]; then RESULT3="writes refused (OOM)"
else RESULT3="UNEXPECTED"; FAILURES=$((FAILURES + 1)); fi
printf '%-38s %8s %7s %9s %8s  %s\n' "3. volatile-lru, untl'd pressure" \
  "$EVICTED3" "$REFUSED3" "$SURV3/$TOTAL" "$MEM3" "$RESULT3"

# --- 4. the same writes inside one Lua script ---------------------------------------------
start_redis volatile-lru
seed_authoritative_keys
apply_pressure_via_lua
EVICTED4="$(evicted_keys)"; SURV4="$(surviving_authoritative)"; MEM4="$(used_memory_mb)"
LIMIT_MB="$(cli config get maxmemory | tail -1 | awk '{printf "%.1f", $1/1048576}')"
if awk -v u="$MEM4" -v l="$LIMIT_MB" 'BEGIN{exit !(u > l)}'; then RESULT4="OVERSHOT limit ${LIMIT_MB}MB"
else RESULT4="stayed within limit"; fi
printf '%-38s %8s %7s %9s %8s  %s\n' "4. volatile-lru, untl'd via Lua" \
  "$EVICTED4" "n/a" "$SURV4/$TOTAL" "$MEM4" "$RESULT4"

cat <<SUMMARY

Reading this:
  1 is the shipped config. Pressure evicted $EVICTED1 TTL'd keys, refused $REFUSED1 writes, and
    every authoritative key survived — the invariant volatile-lru buys.
  2 is what shipped before. The same pressure destroyed $((TOTAL - SURV2)) authoritative key(s);
    shard:topology itself survived: $TOPOLOGY2. Losing it means bootstrap silently recreates it
    at version 1, addressing a resharded cluster's data under the wrong key prefix.
  3 is the trade (02_Caching sharp edge 6). With nothing evictable, Redis refused $REFUSED3
    writes rather than dropping state. Loud beats silent for authoritative data, but it makes
    headroom something to watch: redis_cache_used_memory_bytes vs _max_memory_bytes.
  4 is a caveat neither policy fixes. maxmemory is enforced when a command is dispatched, not
    per redis.call inside a script, so one script ran to completion and left Redis at ${MEM4}MB
    against a ${LIMIT_MB}MB limit. The Flink sinks write through Lua (SET_IF_NEWER_WITH_LINEAGE,
    ATOMIC_TOPK), but those scripts touch 5 keys or top-k members (default 10) per invocation,
    not the 3000 keys this script writes in one EVAL — so the ${MEM4}MB figure is this script's
    synthetic worst case, not a magnitude those sinks reach; it does not justify resizing
    maxmemory.
SUMMARY

if [ "$FAILURES" -ne 0 ]; then
  echo
  echo "$FAILURES scenario(s) did not behave as documented." >&2
  exit 1
fi
