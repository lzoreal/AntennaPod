#!/usr/bin/env bash
# 转录真机测试 runner：一次备份/安装/启动，按需运行一个或多个功能场景。
#
#   bash scripts/transcript-tests/run.sh --list
#   bash scripts/transcript-tests/run.sh layout
#   bash scripts/transcript-tests/run.sh smoke layout offset
#   bash scripts/transcript-tests/run.sh all
#
# 只改到某个功能时，直接运行对应场景（也可以单独跑 scenarios/xx.sh）。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TT_USAGE_ENTRY="scripts/transcript-tests/run.sh"
source "$SCRIPT_DIR/lib.sh"

ORDER=(smoke highlight layout offset floating)

declare -A SCENARIO_FILE=(
  [smoke]=00_smoke.sh
  [highlight]=10_highlight_follow.sh
  [layout]=30_layout_panel.sh
  [offset]=40_offset_loop.sh
  [floating]=50_floating_window.sh
)
declare -A SCENARIO_FN=(
  [smoke]=tt_scenario_smoke
  [highlight]=tt_scenario_highlight
  [layout]=tt_scenario_layout
  [offset]=tt_scenario_offset
  [floating]=tt_scenario_floating
)
declare -A SCENARIO_DESC=(
  [smoke]="转录页打开、无崩溃、双语行、本地下载"
  [highlight]="一条 cue 一行、整块高亮截图"
  [layout]="Aa 排版面板：默认值、调节、恢复默认"
  [offset]="偏移控制条：重置、+30 秒"
  [floating]="悬浮窗开关与服务状态"
)

list_scenarios() {
  local s
  for s in "${ORDER[@]}"; do
    printf '  %-10s %s\n' "$s" "${SCENARIO_DESC[$s]}"
  done
}

SELECTED=()
while [ $# -gt 0 ]; do
  if tt_parse_flag "$@"; then
    shift "$TT_CONSUMED"
    continue
  fi
  case "$1" in
    --list)
      echo "可用场景:"
      list_scenarios
      exit 0;;
    all)
      SELECTED=("${ORDER[@]}")
      shift;;
    smoke|highlight|layout|offset|floating)
      SELECTED+=("$1")
      shift;;
    *)
      echo "未知参数或场景: $1" >&2
      tt_usage
      exit 2;;
  esac
done
[ "${#SELECTED[@]}" -gt 0 ] || SELECTED=("${ORDER[@]}")

if [ "$RESTORE_ONLY" = "1" ]; then
  tt_init_env
  run_restore_only
  exit $?
fi

TT_SOURCED=1
for s in "${SELECTED[@]}"; do
  source "$SCRIPT_DIR/scenarios/${SCENARIO_FILE[$s]}"
done
unset TT_SOURCED

tt_setup

for s in "${SELECTED[@]}"; do
  echo
  echo "== 场景: $s =="
  "${SCENARIO_FN[$s]}"
done

tt_teardown
echo
echo "产物目录: $WORK"
ls "$WORK"/*.png 2>/dev/null || true
[ "$FAILED" = "0" ]
