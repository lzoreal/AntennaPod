#!/usr/bin/env bash
# 场景：时间偏移控制条（重置为 0、+30 秒、再重置）。
# 单独运行: bash scripts/transcript-tests/scenarios/40_offset_loop.sh [选项]

tt_scenario_offset() {
  wake_device quiet || return

  tt_ensure_controls_bar "$WORK/offset_01_toolbar.xml" || return
  sleep 1
  dump_ui "$WORK/offset_02_controls.xml" || { record_fail "偏移：控制条 UI dump 为空"; return; }
  tap_node "$WORK/offset_02_controls.xml" \
    'content-desc="(声音文字偏移控制|Transcript offset controls)"' "打开偏移控制条" || return
  sleep 1
  dump_ui "$WORK/offset_03_panel.xml" || { record_fail "偏移：控制条 dump 为空"; return; }
  assert_dump_has "$WORK/offset_03_panel.xml" 'text="(偏移：|Offset: )' "偏移控制条已展开"

  # 先归零，避免受设备上已有的偏移影响
  tt_tap_and_wait "$WORK/offset_03_panel.xml" 'resource-id="[^"]*:id/btnOffsetReset"' \
    "重置偏移" 'text="(偏移：0 秒|Offset: 0 s)"' 4 || return
  record_pass "偏移为 0"

  tt_tap_and_wait "$WORK/offset_03_panel.xml" 'resource-id="[^"]*:id/btnOffsetForward30"' \
    "偏移 +30 秒" 'text="(偏移：\+30 秒|Offset: \+30 s)"' 4 || return
  record_pass "偏移显示 +30"

  tt_tap_and_wait "$WORK/offset_03_panel.xml" 'resource-id="[^"]*:id/btnOffsetReset"' \
    "再次重置偏移" 'text="(偏移：0 秒|Offset: 0 s)"' 4 || return
  record_pass "偏移重置为 0"
  capture_screen "$WORK/offset_07.png" || return
  tap_node "$WORK/offset_03_panel.xml" \
    'content-desc="(声音文字偏移控制|Transcript offset controls)"' "收起偏移控制条"
  record_pass "偏移控制条截图已保存（$WORK/offset_07.png）"
}

if [ "${TT_SOURCED:-0}" != "1" ]; then
  SCENARIO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  source "$SCENARIO_DIR/../lib.sh"
  tt_run_standalone tt_scenario_offset "$@"
fi
