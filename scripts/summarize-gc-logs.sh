#!/usr/bin/env sh
set -eu

if [ "$#" -eq 0 ]; then
  cat >&2 <<'USAGE'
Usage:
  sh scripts/summarize-gc-logs.sh <gc-log-file> [<gc-log-file> ...]

Summarizes Java unified GC logs, including Minor/Young GC, Full GC, STW pause
events, CMS-era markers when present, G1 events, ZGC events, and safepoints.
USAGE
  exit 64
fi

awk '
function duration_ms(line, token, value) {
  token = ""
  while (match(line, /[0-9][0-9]*(\.[0-9][0-9]*)?(ms|s)/)) {
    token = substr(line, RSTART, RLENGTH)
    line = substr(line, RSTART + RLENGTH)
  }
  if (token == "") {
    return 0
  }
  if (token ~ /ms$/) {
    value = substr(token, 1, length(token) - 2)
    return value + 0
  }
  value = substr(token, 1, length(token) - 1)
  return value * 1000
}
function remember_pause(ms) {
  pauses++
  pause_ms += ms
  if (ms > max_pause_ms) {
    max_pause_ms = ms
  }
}
FNR == 1 {
  if (NR > 1) {
    print_summary()
    reset()
  }
  current_file = FILENAME
}
/Pause Young|GC pause \(young\)|ParNew/ {
  minor_gc++
}
/Pause Full|Full GC|Pause Mark Start.*Full/ {
  full_gc++
}
/Pause|safepoint/ {
  remember_pause(duration_ms($0))
}
/Pause Remark|Pause Cleanup|CMS Initial Mark|CMS Final Remark/ {
  cms_stw++
}
/Concurrent Mark|Concurrent Cycle|Concurrent Undo Cycle/ {
  concurrent_gc++
}
/G1/ {
  g1_events++
}
/ZGC|ZMark|ZRelocate|ZUncommit/ {
  zgc_events++
}
/safepoint/ {
  safepoints++
}
END {
  print_summary()
}
function reset() {
  minor_gc = full_gc = pauses = pause_ms = max_pause_ms = 0
  cms_stw = concurrent_gc = g1_events = zgc_events = safepoints = 0
}
function print_summary(avg) {
  if (current_file == "") {
    return
  }
  avg = pauses == 0 ? 0 : pause_ms / pauses
  printf "%s\n", current_file
  printf "  Minor/Young GC: %d\n", minor_gc
  printf "  Full GC:        %d\n", full_gc
  printf "  STW pauses:     %d\n", pauses
  printf "  Avg pause ms:   %.3f\n", avg
  printf "  Max pause ms:   %.3f\n", max_pause_ms
  printf "  CMS STW marks:  %d\n", cms_stw
  printf "  Concurrent GC:  %d\n", concurrent_gc
  printf "  G1 log events:  %d\n", g1_events
  printf "  ZGC log events: %d\n", zgc_events
  printf "  Safepoints:     %d\n", safepoints
}
' "$@"
