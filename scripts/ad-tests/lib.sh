#!/usr/bin/env bash
set -euo pipefail

PKG="${AD_TEST_PKG:-de.danoeh.antennapod.debug}"
TMP="${TMPDIR:-/tmp}/antennapod-ad-tests"
mkdir -p "$TMP"
RESULT_FILE="${RESULT_FILE:-$PWD/results.txt}"

say() {
  printf '%s\n' "$*"
}

record() {
  printf '%s\n' "$*" | tee -a "$RESULT_FILE"
}

require_device() {
  adb devices | grep -q "device$" || {
    say "no adb device connected"
    exit 1
  }
}

pull_db() {
  adb exec-out run-as "$PKG" cat databases/Antennapod.db > "$TMP/db.sqlite"
}

db_query() {
  pull_db
  python - "$TMP/db.sqlite" "$1" <<'PY'
import sqlite3
import sys
con = sqlite3.connect(sys.argv[1])
for row in con.execute(sys.argv[2]):
    print("|".join("" if v is None else str(v) for v in row))
PY
}

wait_until() {
  local timeout="$1"
  shift
  local start
  start=$(date +%s)
  while ! "$@"; do
    if [ $(( $(date +%s) - start )) -ge "$timeout" ]; then
      return 1
    fi
    sleep 3
  done
}
