#!/usr/bin/env sh
set -eu

usage() {
  cat >&2 <<'USAGE'
Usage:
  sh scripts/arthas-diagnostics.sh <pid> <command> [args...]

Commands:
  session                         Attach an interactive Arthas session.
  thread                          Show top busy threads and blocking threads.
  cpu [seconds]                   Record CPU hotspot flame graph, default 60 s.
  classloader                     Show classloader tree and loaded-class stats.
  watch <class> <method> [expr]   Watch method params/return/throw, default expression prints params/returnObj/throwExp/cost.
  trace <class> <method>          Trace method call path and cost.
  jad <class>                     Decompile a loaded class.

Environment:
  ARTHAS_BOOT_JAR                 Path to arthas-boot.jar. Default: tools/arthas/arthas-boot.jar

Examples:
  sh scripts/arthas-diagnostics.sh 12345 thread
  sh scripts/arthas-diagnostics.sh 12345 cpu 45
  sh scripts/arthas-diagnostics.sh 12345 classloader
  sh scripts/arthas-diagnostics.sh 12345 watch com.recsys.modelbased.model.service.RankingService rank
  sh scripts/arthas-diagnostics.sh 12345 trace com.recsys.modelbased.model.service.RecommendationService recommend
  sh scripts/arthas-diagnostics.sh 12345 jad com.recsys.modelbased.model.service.RecommendationCache
USAGE
  exit 64
}

if [ "$#" -lt 2 ]; then
  usage
fi

pid="$1"
action="$2"
shift 2

case "$pid" in
  *[!0-9]*|'') echo "PID must be a number: $pid" >&2; exit 64 ;;
esac

arthas_boot="${ARTHAS_BOOT_JAR:-tools/arthas/arthas-boot.jar}"

if [ ! -r "$arthas_boot" ]; then
  cat >&2 <<EOF
Cannot read Arthas boot jar: $arthas_boot

Download Arthas once, then rerun:
  mkdir -p tools/arthas
  curl -L -o tools/arthas/arthas-boot.jar https://arthas.aliyun.com/arthas-boot.jar

Or set ARTHAS_BOOT_JAR=/path/to/arthas-boot.jar
EOF
  exit 66
fi

mkdir -p logs/arthas

run_arthas() {
  java -jar "$arthas_boot" -c "$1" "$pid"
}

case "$action" in
  session)
    java -jar "$arthas_boot" "$pid"
    ;;
  thread)
    echo "== Top busy threads =="
    run_arthas "thread -n 10"
    echo
    echo "== Blocking/deadlock candidates =="
    run_arthas "thread -b"
    ;;
  cpu)
    seconds="${1:-60}"
    case "$seconds" in
      *[!0-9]*|'') echo "Duration must be seconds: $seconds" >&2; exit 64 ;;
    esac
    out="logs/arthas/cpu-hotspot-${pid}-$(date +%Y%m%d-%H%M%S).html"
    run_arthas "profiler start --event cpu"
    echo "Recording CPU hotspot profile for ${seconds}s..."
    sleep "$seconds"
    run_arthas "profiler stop --format html --file ${out}"
    echo "CPU hotspot flame graph: ${out}"
    ;;
  classloader)
    echo "== Classloader tree =="
    run_arthas "classloader -t"
    echo
    echo "== Classloader statistics =="
    run_arthas "classloader -l"
    ;;
  watch)
    if [ "$#" -lt 2 ]; then
      usage
    fi
    class_name="$1"
    method_name="$2"
    expr="${3:-'{params,returnObj,throwExp,#cost}'}"
    run_arthas "watch ${class_name} ${method_name} ${expr} -x 3 -n 5"
    ;;
  trace)
    if [ "$#" -lt 2 ]; then
      usage
    fi
    run_arthas "trace $1 $2 -n 5 --skipJDKMethod false"
    ;;
  jad)
    if [ "$#" -lt 1 ]; then
      usage
    fi
    run_arthas "jad $1"
    ;;
  *)
    echo "Unknown command: $action" >&2
    usage
    ;;
esac
