#!/usr/bin/env sh
set -eu

die() {
  echo "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

repo_root() {
  script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
  CDPATH= cd -- "$script_dir/.." && pwd
}

absolute_path() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s/%s\n' "$(pwd)" "$1" ;;
  esac
}

usage() {
  cat >&2 <<'USAGE'
Usage:
  sh scripts/mat-heap-analysis.sh <command> [args...]

Commands:
  dump <pid> [file]              Create a live heap dump with jcmd GC.heap_dump.
  dump-all <pid> [file]          Create a full heap dump including unreachable objects.
  histogram <pid>                Print top live classes by retained instances/bytes.
  report <hprof> [out-dir]       Run Eclipse MAT leak suspects, top components, and overview reports.

Environment:
  MAT_PARSE_HEAP_DUMP            Path to MAT ParseHeapDump. Default: ParseHeapDump from PATH

Examples:
  sh scripts/mat-heap-analysis.sh dump 12345
  sh scripts/mat-heap-analysis.sh histogram 12345
  MAT_PARSE_HEAP_DUMP=/opt/mat/ParseHeapDump sh scripts/mat-heap-analysis.sh report logs/heap-dump-model-serving.hprof
USAGE
  exit 64
}

if [ "$#" -lt 1 ]; then
  usage
fi

action="$1"
shift
root_dir=$(repo_root)
heap_dump_dir="$root_dir/logs/heap-dumps"
mat_report_dir="$root_dir/logs/mat"
mkdir -p "$heap_dump_dir" "$mat_report_dir"

require_pid() {
  if [ "$#" -lt 1 ]; then
    usage
  fi
  case "$1" in
    *[!0-9]*|'') echo "PID must be a number: $1" >&2; exit 64 ;;
  esac
  if ! kill -0 "$1" >/dev/null 2>&1; then
    echo "Process is not running or not accessible: $1" >&2
    exit 69
  fi
}

case "$action" in
  dump|dump-all)
    require_command jcmd
    require_pid "$@"
    pid="$1"
    if [ "$#" -ge 2 ]; then
      out="$(absolute_path "$2")"
    else
      out="$heap_dump_dir/heap-${pid}-$(date +%Y%m%d-%H%M%S).hprof"
    fi
    mkdir -p "$(dirname -- "$out")"
    if [ -e "$out" ]; then
      echo "Refusing to overwrite existing heap dump: $out" >&2
      exit 73
    fi
    if [ "$action" = "dump-all" ]; then
      jcmd "$pid" GC.heap_dump -all "$out"
    else
      jcmd "$pid" GC.heap_dump "$out"
    fi
    if [ ! -s "$out" ]; then
      echo "Heap dump was not created or is empty: $out" >&2
      exit 74
    fi
    echo "Heap dump: $out"
    ;;
  histogram)
    require_command jcmd
    require_pid "$@"
    jcmd "$1" GC.class_histogram | head -n 80
    ;;
  report)
    if [ "$#" -lt 1 ]; then
      usage
    fi
    hprof="$(absolute_path "$1")"
    if [ "$#" -ge 2 ]; then
      out_dir="$(absolute_path "$2")"
    else
      out_dir="$mat_report_dir/$(basename "$hprof" .hprof)"
    fi
    parse_heap_dump="${MAT_PARSE_HEAP_DUMP:-ParseHeapDump}"
    if [ ! -r "$hprof" ]; then
      echo "Cannot read heap dump: $hprof" >&2
      exit 66
    fi
    if ! command -v "$parse_heap_dump" >/dev/null 2>&1 && [ ! -x "$parse_heap_dump" ]; then
      cat >&2 <<EOF
Cannot execute MAT ParseHeapDump: $parse_heap_dump

Install Eclipse MAT, then set:
  MAT_PARSE_HEAP_DUMP=/path/to/mat/ParseHeapDump

Useful report names:
  org.eclipse.mat.api:suspects
  org.eclipse.mat.api:top_components
  org.eclipse.mat.api:overview
EOF
      exit 69
    fi
    case "$parse_heap_dump" in
      */*) parse_heap_dump_abs="$parse_heap_dump" ;;
      *) parse_heap_dump_abs="$(command -v "$parse_heap_dump")" ;;
    esac
    mkdir -p "$out_dir"
    (
      cd "$out_dir"
      "$parse_heap_dump_abs" "$hprof" \
        org.eclipse.mat.api:suspects \
        org.eclipse.mat.api:top_components \
        org.eclipse.mat.api:overview
    )
    echo "MAT reports: $out_dir"
    ;;
  *)
    echo "Unknown command: $action" >&2
    usage
    ;;
esac
