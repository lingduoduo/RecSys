#!/usr/bin/env bash
# Tests scripts/set-elasticache-parameters.sh against a fake `aws` on PATH.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT/scripts/set-elasticache-parameters.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"

cat >"$TMP/bin/aws" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_AWS_LOG"
if [ "${2:-}" = "describe-cache-parameters" ]; then
  printf '%s\n' "${FAKE_POLICY:-volatile-lru}"
fi
FAKE
chmod +x "$TMP/bin/aws"
export PATH="$TMP/bin:$PATH"

FAILURES=0
check() { # check <name> <expected> <actual>
  if [ "$2" = "$3" ]; then
    echo "ok   - $1"
  else
    echo "FAIL - $1: expected '$2', got '$3'"
    FAILURES=$((FAILURES + 1))
  fi
}

check_contains() { # check_contains <name> <pattern> <file>
  if grep -q "$2" "$3"; then
    echo "ok   - $1"
  else
    echo "FAIL - $1 (no '$2' in $3)"
    FAILURES=$((FAILURES + 1))
  fi
}

run() { # run <mode> [env assignments already exported]; echoes exit code
  local status=0
  FAKE_AWS_LOG="$TMP/aws.log" "$SCRIPT" "$1" >"$TMP/out" 2>&1 || status=$?
  echo "$status"
}

# --- verify: a compliant group passes -----------------------------------------
: >"$TMP/aws.log"
export PARAMETER_GROUP=recsys-redis7 AWS_REGION=us-east-1
export FAKE_POLICY=volatile-lru
check "verify accepts volatile-lru" "0" "$(run verify)"

# --- verify: a drifted group fails --------------------------------------------
: >"$TMP/aws.log"
export FAKE_POLICY=allkeys-lru
check "verify rejects allkeys-lru" "1" "$(run verify)"
check_contains "verify reports the offending policy" "allkeys-lru" "$TMP/out"

# --- verify: noeviction is acceptable (it evicts nothing at all) --------------
: >"$TMP/aws.log"
export FAKE_POLICY=noeviction
check "verify accepts noeviction" "0" "$(run verify)"

# --- apply: issues the modify call with the right parameter -------------------
: >"$TMP/aws.log"
export FAKE_POLICY=volatile-lru
check "apply succeeds" "0" "$(run apply)"
check_contains "apply calls modify-cache-parameter-group" \
  "modify-cache-parameter-group" "$TMP/aws.log"
check_contains "apply sets maxmemory-policy=volatile-lru" \
  "ParameterName=maxmemory-policy,ParameterValue=volatile-lru" "$TMP/aws.log"

# --- apply: refuses an AWS-managed default parameter group --------------------
# AWS rejects edits to default.* groups; failing early with a clear message beats
# surfacing an opaque API error.
: >"$TMP/aws.log"
export PARAMETER_GROUP=default.redis7
check "apply refuses a default.* group" "2" "$(run apply)"
if [ -s "$TMP/aws.log" ]; then
  echo "FAIL - apply called aws despite the default.* group"; FAILURES=$((FAILURES + 1))
else
  echo "ok   - apply makes no AWS call for a default.* group"
fi

# --- missing configuration is a usage error, not a silent no-op ---------------
: >"$TMP/aws.log"
unset PARAMETER_GROUP
check "missing PARAMETER_GROUP is a usage error" "2" "$(run verify)"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All set-elasticache-parameters tests passed."
else
  echo "$FAILURES test(s) failed."
  exit 1
fi
