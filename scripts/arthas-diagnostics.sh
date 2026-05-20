#!/usr/bin/env sh
set -eu

die() {
  echo "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

require_pid() {
  case "${1:-}" in
    *[!0-9]*|'') echo "PID must be a number: ${1:-}" >&2; exit 64 ;;
  esac
  if ! kill -0 "$1" >/dev/null 2>&1; then
    echo "Process is not running or not accessible: $1" >&2
    exit 69
  fi
}

repo_root() {
  script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
  CDPATH= cd -- "$script_dir/.." && pwd
}

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
  ARTHAS_BOOT_JAR                 Path to arthas-boot.jar. Default: <repo>/tools/arthas/arthas-boot.jar

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

require_command java
require_pid "$pid"

root_dir=$(repo_root)
arthas_boot="${ARTHAS_BOOT_JAR:-$root_dir/tools/arthas/arthas-boot.jar}"

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

arthas_log_dir="$root_dir/logs/arthas"
mkdir -p "$arthas_log_dir"

run_arthas() {
  java -jar "$arthas_boot" -c "$1" "$pid"
}

require_identifier() {
  case "$1" in
    ''|*' '*|*';'*|*'|'*|*'&'*|*'`'*|*'$('*) echo "Invalid class or method pattern: $1" >&2; exit 64 ;;
  esac
}

profiling=0
profile_out=""
stop_profiler() {
  if [ "$profiling" -eq 1 ]; then
    run_arthas "profiler stop --format html --file ${profile_out}" >/dev/null 2>&1 || true
  fi
}
trap stop_profiler INT TERM EXIT

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
    if [ "$seconds" -eq 0 ]; then
      echo "Duration must be greater than zero" >&2
      exit 64
    fi
    profile_out="$arthas_log_dir/cpu-hotspot-${pid}-$(date +%Y%m%d-%H%M%S).html"
    run_arthas "profiler start --event cpu"
    profiling=1
    echo "Recording CPU hotspot profile for ${seconds}s..."
    sleep "$seconds"
    run_arthas "profiler stop --format html --file ${profile_out}"
    profiling=0
    echo "CPU hotspot flame graph: ${profile_out}"
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
    require_identifier "$class_name"
    require_identifier "$method_name"
    expr="${3:-'{params,returnObj,throwExp,#cost}'}"
    run_arthas "watch ${class_name} ${method_name} ${expr} -x 3 -n 5"
    ;;
  trace)
    if [ "$#" -lt 2 ]; then
      usage
    fi
    require_identifier "$1"
    require_identifier "$2"
    run_arthas "trace $1 $2 -n 5 --skipJDKMethod false"
    ;;
  jad)
    if [ "$#" -lt 1 ]; then
      usage
    fi
    require_identifier "$1"
    run_arthas "jad $1"
    ;;
  *)
    echo "Unknown command: $action" >&2
    usage
    ;;
esac
