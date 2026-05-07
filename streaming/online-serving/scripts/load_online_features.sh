#!/usr/bin/env bash
set -euo pipefail

REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
DATA_FILE="${1:-src/main/java/com/recsys/data/online_features.txt}"

if ! command -v redis-cli >/dev/null 2>&1; then
  echo "redis-cli is required to load the demo online features." >&2
  exit 1
fi

while IFS=, read -r feature_key value updated_at_millis ttl_seconds; do
  if [[ "$feature_key" == "featureKey" ]]; then
    continue
  fi

  case "$feature_key" in
    user:*|movie:*)
      redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" SET "$feature_key" "$value" EX "$ttl_seconds" >/dev/null
      ;;
    topk:*)
      redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" DEL "$feature_key" >/dev/null
      for pair in $value; do
        member="${pair%%:*}"
        score="${pair##*:}"
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" ZADD "$feature_key" "$score" "$member" >/dev/null
      done
      redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" EXPIRE "$feature_key" "$ttl_seconds" >/dev/null
      ;;
  esac
done < "$DATA_FILE"

echo "Loaded demo online features from $DATA_FILE into Redis at $REDIS_HOST:$REDIS_PORT"
