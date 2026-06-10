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
  mvn exec:java -Dexec.mainClass=com.recsys.online.serving.OnlinePredictionServer

sleep "${GATEWAY_START_DELAY_SECONDS:-10}"

# LLM routes (llm, llm-explanation) are omitted by default — they only activate when
# LLM_SERVICE_URL / LLM_EXPLANATION_SERVICE_URL are explicitly set in the environment.
# To enable: export LLM_SERVICE_URL=http://localhost:11434 before running this script
# (requires Ollama: brew install ollama && ollama serve).
start_service api-gateway env GATEWAY_PORT=8010 \
  USER_PROFILE_SERVICE_URL="${USER_PROFILE_SERVICE_URL:-http://localhost:6010}" \
  MOVIE_METADATA_SERVICE_URL="${MOVIE_METADATA_SERVICE_URL:-http://localhost:6010}" \
  FEATURE_SERVICE_URL="${FEATURE_SERVICE_URL:-http://localhost:7010}" \
  RECOMMENDATION_RETRIEVAL_SERVICE_URL="${RECOMMENDATION_RETRIEVAL_SERVICE_URL:-http://localhost:8080}" \
  RANKING_SERVICE_URL="${RANKING_SERVICE_URL:-http://localhost:8080}" \
  AGENT_WORKFLOW_SERVICE_URL="${AGENT_WORKFLOW_SERVICE_URL:-http://localhost:8080}" \
  OBSERVABILITY_SERVICE_URL="${OBSERVABILITY_SERVICE_URL:-http://localhost:8080}" \
  sh scripts/run-with-jvm-tuning.sh api-gateway -- \
  mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer

echo "Microservices are starting. Gateway: http://localhost:8010/health"
wait
