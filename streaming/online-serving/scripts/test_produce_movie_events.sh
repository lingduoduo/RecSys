#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PRODUCER_SCRIPT="$SCRIPT_DIR/produce_movie_events.sh"
FIXTURE_DIR="$(mktemp -d)"
trap 'rm -rf "$FIXTURE_DIR"' EXIT

cat > "$FIXTURE_DIR/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$DOCKER_CALLS"
if [[ "$*" == *"kafka-topics --bootstrap-server kafka:9092 --describe"* ]]; then
  printf 'Topic: movie_events_v2\tTopicId: fixture\tPartitionCount: %s\tReplicationFactor: 1\n' "$FIXTURE_PARTITIONS"
elif [[ "$*" == *"kafka-console-producer"* ]]; then
  cat > "$PRODUCED_RECORDS"
fi
EOF
chmod +x "$FIXTURE_DIR/docker"

INPUT_FILE="$FIXTURE_DIR/events.ndjson"
printf '%s\n' '{  "user_id" : "user_9223372036854775807", "timestamp_ms":9223372036854775807, "note" : "spacing retained"  }' > "$INPUT_FILE"

export PATH="$FIXTURE_DIR:$PATH"
export DOCKER_CALLS="$FIXTURE_DIR/docker.calls"
export PRODUCED_RECORDS="$FIXTURE_DIR/produced.records"

export FIXTURE_PARTITIONS=12
if bash "$PRODUCER_SCRIPT" "$INPUT_FILE" >/dev/null 2>&1; then
  echo "expected existing 12-partition topic to be rejected" >&2
  exit 1
fi
if [[ -e "$PRODUCED_RECORDS" ]]; then
  echo "producer was invoked after partition mismatch" >&2
  exit 1
fi

: > "$DOCKER_CALLS"
export FIXTURE_PARTITIONS=24
bash "$PRODUCER_SCRIPT" "$INPUT_FILE" >/dev/null

IFS=$'\t' read -r key value < "$PRODUCED_RECORDS"
[[ "$key" == "9223372036854775807" ]]
[[ "$value" == "$(< "$INPUT_FILE")" ]]
