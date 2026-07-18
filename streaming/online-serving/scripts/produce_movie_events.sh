#!/usr/bin/env bash
set -euo pipefail

DATA_FILE="${1:-streaming/online-serving/data/movie_events.ndjson}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-recsys-kafka-demo}"
KAFKA_TOPIC="${KAFKA_TOPIC:-movie_events_v2}"
KAFKA_PARTITIONS="${KAFKA_PARTITIONS:-24}"
JQ_FILTER="$(dirname "$0")/key_movie_events.jq"

if [[ "$KAFKA_PARTITIONS" != "24" ]]; then
  echo "KAFKA_PARTITIONS must equal 24 for movie_events_v2" >&2
  exit 1
fi

KEYED_EVENTS="$(mktemp)"
trap 'rm -f "$KEYED_EVENTS"' EXIT

jq -r -f "$JQ_FILTER" "$DATA_FILE" > "$KEYED_EVENTS"
if ! awk -F '\t' 'NF != 2 || $1 !~ /^[1-9][0-9]*$/ { exit 1 }' "$KEYED_EVENTS"; then
  echo "Every event must have a positive normalized user ID" >&2
  exit 1
fi

docker exec "$KAFKA_CONTAINER" kafka-topics \
  --bootstrap-server kafka:9092 \
  --create \
  --if-not-exists \
  --topic "$KAFKA_TOPIC" \
  --partitions "$KAFKA_PARTITIONS" \
  --replication-factor 1 >/dev/null

docker exec -i "$KAFKA_CONTAINER" kafka-console-producer \
  --bootstrap-server kafka:9092 \
  --topic "$KAFKA_TOPIC" \
  --property parse.key=true \
  --property key.separator=$'\t' < "$KEYED_EVENTS"

echo "Produced $(wc -l < "$DATA_FILE") demo events into Kafka topic $KAFKA_TOPIC"
