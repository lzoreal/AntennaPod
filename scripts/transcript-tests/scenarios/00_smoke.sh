#!/usr/bin/env bash
# 场景：转录页冒烟（打开、无崩溃、双语行、本地下载）。
# 单独运行: bash scripts/transcript-tests/scenarios/00_smoke.sh [选项]

tt_scenario_smoke() {
  local xml="$COMMON_XML"
  # uiautomator 在播放/动画中常拿不到 idle；VTT 场景不验证播放，先暂停再取稳定 dump
  tt_pause_playback
  if ! dump_ui "$xml"; then
    record_fail "冒烟：转录页 UI dump 为空（暂停后仍失败）"
    return
  fi
  capture_screen "$COMMON_PNG" || true

  if grep -q "FATAL EXCEPTION" "$WORK/transcript.log" 2>/dev/null; then
    record_fail "转录页出现崩溃，见 $WORK/transcript.log"
  else
    record_pass "转录页无崩溃"
  fi

  local rows
  rows="$(ui_nodes "$xml" | grep -c "resource-id=\"${PKG}:id/content\"" || true)"
  if [ "${rows:-0}" -ge 1 ]; then
    record_pass "转录页已打开，可见行数: $rows"
  else
    record_fail "转录页未打开：确认用的是 --ez 而不是 -e、二次 start 走 onNewIntent，且没有传 episode_id"
  fi

  local bilingual
  bilingual="$(LC_ALL=C; ui_nodes "$xml" \
    | grep "resource-id=\"${PKG}:id/content\"" | grep -oE 'text="[^"]*"' \
    | grep -E '[A-Za-z]' | grep -vE '^text="[ -~]*"$' | grep -c . || true)"
  if [ "${bilingual:-0}" -ge 1 ]; then
    record_pass "可见双语行（同一行含拉丁与 CJK 文本）: $bilingual"
  else
    record_fail "没有看到双语行：确认语言模式与逐句拆分生效"
  fi

  if grep -q "Done Downloading transcript" "$WORK/transcript.log"; then
    record_pass "日志确认从本地服务下载了转录"
  else
    record_fail "日志中没有 Done Downloading transcript（可能命中了旧的本地文件）"
  fi
}

if [ "${TT_SOURCED:-0}" != "1" ]; then
  SCENARIO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  source "$SCENARIO_DIR/../lib.sh"
  tt_run_standalone tt_scenario_smoke "$@"
fi
