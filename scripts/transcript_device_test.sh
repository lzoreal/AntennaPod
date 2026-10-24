#!/usr/bin/env bash
# 兼容入口：等价于 scripts/transcript-tests/run.sh all。
# 需要只跑某个功能时用:
#   bash scripts/transcript-tests/run.sh layout
#   bash scripts/transcript-tests/scenarios/30_layout_panel.sh
# 详见 docs/transcript-handover.md §5.2。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$SCRIPT_DIR/transcript-tests/run.sh" all "$@"
