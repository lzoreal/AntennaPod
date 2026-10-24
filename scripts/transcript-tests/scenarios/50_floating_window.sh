#!/usr/bin/env bash
# 场景：悬浮窗（菜单开关、服务状态、截图）。
# 单独运行: bash scripts/transcript-tests/scenarios/50_floating_window.sh [选项]

tt_scenario_floating() {
  check_floating
}

if [ "${TT_SOURCED:-0}" != "1" ]; then
  SCENARIO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  source "$SCENARIO_DIR/../lib.sh"
  tt_run_standalone tt_scenario_floating "$@"
fi
