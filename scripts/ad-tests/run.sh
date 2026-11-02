#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "--list" ]; then
  ls scenarios/*.sh | xargs -n1 basename | sed 's/\.sh$//'
  exit 0
fi

if [ -z "${1:-}" ]; then
  echo "usage: $0 <scenario>|--list"
  exit 1
fi

SCENARIO="scenarios/${1%.sh}.sh"
if [ ! -f "$SCENARIO" ]; then
  echo "no such scenario: $1"
  exit 1
fi

export RESULT_FILE="$PWD/results.txt"
bash "$SCENARIO"
