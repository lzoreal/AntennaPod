#!/usr/bin/env bash
# 场景：高亮与跟随（一条 cue 一行、整块高亮、双语行）。
# 颜色三档与居中效果依赖像素，脚本只做结构断言并留截图人工确认。
# 单独运行: bash scripts/transcript-tests/scenarios/10_highlight_follow.sh [选项]

tt_scenario_highlight() {
  local xml="$COMMON_XML"
  tt_pause_playback
  if ! dump_ui "$xml"; then
    record_fail "高亮：转录页 UI dump 为空（暂停后仍失败）"
    return
  fi

  local rows
  rows="$(ui_nodes "$xml" | grep -c "resource-id=\"${PKG}:id/content\"" || true)"
  if [ "${rows:-0}" -ge 1 ] && [ "${rows:-0}" -le 3 ]; then
    record_pass "一条 cue 一行（可见行数 $rows ≤ 3）"
  else
    record_fail "可见行数 $rows 不符合一条 cue 一行（期望 1-3）：可能回退成了逐句拆行"
  fi

  local first
  first="$(ui_nodes "$xml" | grep "resource-id=\"${PKG}:id/content\"" | head -1 | grep -oE 'text="[^"]*"')"
  if [ -n "$first" ] && printf '%s' "$first" | grep -qE '[A-Za-z]' \
      && printf '%s' "$first" | grep -vqE '^text="[ -~]*"$'; then
    record_pass "首行是双语 cue（拉丁 + CJK）"
  else
    record_fail "首行不是双语 cue，检查语言模式/解析结果"
  fi

  capture_screen "$WORK/highlight_01.png" || return
  record_pass "高亮截图已保存（全列表统一字号、块内已播/当前/未播三档，人工确认 $WORK/highlight_01.png）"
}

if [ "${TT_SOURCED:-0}" != "1" ]; then
  SCENARIO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  source "$SCENARIO_DIR/../lib.sh"
  tt_run_standalone tt_scenario_highlight "$@"
fi
