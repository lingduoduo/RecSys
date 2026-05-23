#!/usr/bin/env sh
set -eu

mkdir -p logs
pids=""

cleanup() {
  if [ -n "$pids" ]; then
    echo "Stopping microservice processes:$pids"
    kill $pids 2>/dev/null || true
  fi
}
trap cleanup INT TERM EXIT

start_service() {
  name="$1"
  shift
  log_file="logs/${name}.log"
  echo "Starting ${name}; logs -> ${log_file}"
  "$@" > "$log_file" 2>&1 &
  pids="${pids} $!"
}

start_service recsys-serving env PORT=6010 \
  sh scripts/run-with-jvm-tuning.sh recsys-serving -- \
  mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer

start_service model-serving env SERVER_PORT=8080 \
  sh scripts/run-with-jvm-tuning.sh model-serving -- \
  mvn spring-boot:run

start_service online-serving env ONLINE_DEMO_PORT=7010 \
  sh scripts/run-with-jvm-tuning.sh online-serving -- \
  mvn exec:java -Dexec.mainClass=com.recsys.streaming.OnlinePredictionServer

sleep "${GATEWAY_START_DELAY_SECONDS:-10}"

start_service api-gateway env GATEWAY_PORT=8010 \
  sh scripts/run-with-jvm-tuning.sh api-gateway -- \
  mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer

echo "Microservices are starting. Gateway: http://localhost:8010/health"
wait
