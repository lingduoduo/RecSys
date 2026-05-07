#!/usr/bin/env bash
set -euo pipefail

DATA_FILE="${1:-streaming/online-serving/data/movie_events.ndjson}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-recsys-kafka-demo}"
KAFKA_TOPIC="${KAFKA_TOPIC:-movie_events}"

docker exec "$KAFKA_CONTAINER" kafka-topics \
  --bootstrap-server kafka:9092 \
  --create \
  --if-not-exists \
  --topic "$KAFKA_TOPIC" \
  --partitions 1 \
  --replication-factor 1 >/dev/null

docker exec -i "$KAFKA_CONTAINER" kafka-console-producer \
  --bootstrap-server kafka:9092 \
  --topic "$KAFKA_TOPIC" < "$DATA_FILE"

echo "Produced $(wc -l < "$DATA_FILE") demo events into Kafka topic $KAFKA_TOPIC"
