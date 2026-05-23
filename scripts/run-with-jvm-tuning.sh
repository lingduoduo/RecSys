#!/usr/bin/env sh
set -eu

usage() {
  cat <<'USAGE'
Usage:
  sh scripts/run-with-jvm-tuning.sh <profile> -- <maven command...>

Profiles:
  recsys-serving       Jetty Recommendation Serving API, G1 GC, port 6010
  recsys-serving-zgc   Jetty Recommendation Serving API, ZGC, port 6010
  model-serving        Spring Boot ONNX model service, G1 GC, port 8080
  model-serving-zgc    Spring Boot ONNX model service, ZGC, port 8080
  online-serving       Jetty Online Prediction Server, G1 GC, port 7010
  online-serving-zgc   Jetty Online Prediction Server, ZGC, port 7010
  api-gateway          Jetty microservice API gateway, G1 GC, port 8010
  offline-embedding    Local offline embedding / Spark driver runs, G1 GC

Examples:
  sh scripts/run-with-jvm-tuning.sh recsys-serving -- mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
  sh scripts/run-with-jvm-tuning.sh api-gateway -- mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer
USAGE
}

if [ "$#" -lt 3 ] || [ "$2" != "--" ]; then
  usage >&2
  exit 64
fi

profile="$1"
shift 2

case "$profile" in
  recsys-serving)
    opts_file="config/jvm/recsys-serving.jvmopts"
    ;;
  recsys-serving-zgc)
    opts_file="config/jvm/recsys-serving-zgc.jvmopts"
    ;;
  model-serving)
    opts_file="config/jvm/model-serving.jvmopts"
    ;;
  model-serving-zgc)
    opts_file="config/jvm/model-serving-zgc.jvmopts"
    ;;
  online-serving)
    opts_file="config/jvm/online-serving.jvmopts"
    ;;
  online-serving-zgc)
    opts_file="config/jvm/online-serving-zgc.jvmopts"
    ;;
  api-gateway)
    opts_file="config/jvm/api-gateway.jvmopts"
    ;;
  offline-embedding)
    opts_file="config/jvm/offline-embedding.jvmopts"
    ;;
  *)
    echo "Unknown JVM tuning profile: $profile" >&2
    usage >&2
    exit 64
    ;;
esac

if [ ! -r "$opts_file" ]; then
  echo "Cannot read JVM options file: $opts_file" >&2
  exit 66
fi

mkdir -p logs

jvm_opts=""
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    ''|'#'*) continue ;;
    *) jvm_opts="${jvm_opts} ${line}" ;;
  esac
done < "$opts_file"

export MAVEN_OPTS="${MAVEN_OPTS:-}${jvm_opts}"

echo "Using JVM tuning profile '$profile' from $opts_file"
echo "MAVEN_OPTS=${MAVEN_OPTS}"
exec "$@"
