#!/usr/bin/env sh
set -eu

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
  MAT_PARSE_HEAP_DUMP            Path to MAT ParseHeapDump. Default: ParseHeapDump

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
mkdir -p logs/heap-dumps logs/mat

require_pid() {
  if [ "$#" -lt 1 ]; then
    usage
  fi
  case "$1" in
    *[!0-9]*|'') echo "PID must be a number: $1" >&2; exit 64 ;;
  esac
}

case "$action" in
  dump|dump-all)
    require_pid "$@"
    pid="$1"
    if [ "$#" -ge 2 ]; then
      out="$2"
    else
      out="logs/heap-dumps/heap-${pid}-$(date +%Y%m%d-%H%M%S).hprof"
    fi
    if [ "$action" = "dump-all" ]; then
      jcmd "$pid" GC.heap_dump -all "$out"
    else
      jcmd "$pid" GC.heap_dump "$out"
    fi
    echo "Heap dump: $out"
    ;;
  histogram)
    require_pid "$@"
    jcmd "$1" GC.class_histogram | head -n 80
    ;;
  report)
    if [ "$#" -lt 1 ]; then
      usage
    fi
    hprof="$1"
    out_dir="${2:-logs/mat/$(basename "$hprof" .hprof)}"
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
    case "$hprof" in
      /*) hprof_abs="$hprof" ;;
      *) hprof_abs="$(pwd)/$hprof" ;;
    esac
    case "$parse_heap_dump" in
      */*) parse_heap_dump_abs="$parse_heap_dump" ;;
      *) parse_heap_dump_abs="$(command -v "$parse_heap_dump")" ;;
    esac
    mkdir -p "$out_dir"
    (
      cd "$out_dir"
      "$parse_heap_dump_abs" "$hprof_abs" \
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
