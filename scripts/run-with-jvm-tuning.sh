#!/usr/bin/env sh
set -eu

usage() {
  cat <<'USAGE'
Usage:
  sh scripts/run-with-jvm-tuning.sh <profile> -- <maven command...>

Profiles:
  recsys-serving     Jetty Recommendation Serving API, port 6010
  model-serving      Spring Boot ONNX model service, port 8080
  online-serving     Jetty Online Prediction Server, port 7010
  offline-embedding  Local offline embedding / Spark driver runs

Examples:
  sh scripts/run-with-jvm-tuning.sh recsys-serving -- mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
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
  model-serving)
    opts_file="config/jvm/model-serving.jvmopts"
    ;;
  online-serving)
    opts_file="config/jvm/online-serving.jvmopts"
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
