#!/usr/bin/env bash
# 场景：Aa 排版面板（默认值、行距/边距调节、恢复默认）。
# 单独运行: bash scripts/transcript-tests/scenarios/30_layout_panel.sh [选项]

tt_scenario_layout() {
  wake_device quiet || return

  tt_ensure_controls_bar "$WORK/layout_01_toolbar.xml" || return
  sleep 1
  dump_ui "$WORK/layout_02_controls.xml" || { record_fail "排版：控制条 UI dump 为空"; return; }
  tap_node "$WORK/layout_02_controls.xml" \
    'content-desc="(声音文字排版|Transcript appearance)"' "打开排版面板" || return
  sleep 1
  dump_ui "$WORK/layout_03_panel.xml" || { record_fail "排版：面板 UI dump 为空"; return; }

  assert_dump_has "$WORK/layout_03_panel.xml" 'text="(字号|Font size)"' "面板包含字号行"
  assert_dump_has "$WORK/layout_03_panel.xml" 'text="(行距|Line spacing)"' "面板包含行距行"
  assert_dump_has "$WORK/layout_03_panel.xml" 'text="(左右边距|Side margins)"' "面板包含左右边距行"
  assert_dump_has "$WORK/layout_03_panel.xml" 'text="(上下留白|Vertical spacing)"' "面板包含上下留白行"
  assert_dump_has "$WORK/layout_03_panel.xml" 'text="(重置|Reset)"' "面板包含恢复默认"
  capture_screen "$WORK/layout_04_panel.png" || return

  # 先重置到默认，避免受设备既有偏好影响
  tap_node "$WORK/layout_03_panel.xml" 'resource-id="[^"]*:id/btnLayoutReset"' "恢复默认" || return
  sleep 1
  dump_ui "$WORK/layout_05_default.xml"
  assert_dump_has "$WORK/layout_05_default.xml" 'text="1×"' "默认行距 1×"
  assert_dump_has "$WORK/layout_05_default.xml" 'text="24 dp"' "默认左右边距 24 dp"
  assert_dump_has "$WORK/layout_05_default.xml" 'text="(标准|Standard)"' "默认上下留白 标准"

  tap_node "$WORK/layout_05_default.xml" \
    'resource-id="[^"]*:id/btnLineSpacingUp"' "行距 +" || return
  sleep 1
  dump_ui "$WORK/layout_06_after.xml"
  assert_dump_has "$WORK/layout_06_after.xml" 'text="1\.15×"' "行距调到 1.15×"

  tap_node "$WORK/layout_06_after.xml" \
    'resource-id="[^"]*:id/btnSideMarginsUp"' "边距 +" || return
  sleep 1
  dump_ui "$WORK/layout_07_after.xml"
  assert_dump_has "$WORK/layout_07_after.xml" 'text="32 dp"' "边距调到 32 dp"

  tap_node "$WORK/layout_07_after.xml" \
    'resource-id="[^"]*:id/btnLayoutReset"' "再次恢复默认" || return
  sleep 1
  dump_ui "$WORK/layout_08_reset.xml"
  assert_dump_has "$WORK/layout_08_reset.xml" 'text="1×"' "重置后行距 1×"
  assert_dump_has "$WORK/layout_08_reset.xml" 'text="24 dp"' "重置后边距 24 dp"
  capture_screen "$WORK/layout_09_reset.png" || return
  tap_node "$WORK/layout_08_reset.xml" \
    'content-desc="(声音文字排版|Transcript appearance)"' "收起排版面板"
  sleep 1
  dump_ui "$WORK/layout_10_closed.xml" || { record_fail "排版：收起后 UI dump 为空"; return; }
  if grep -q 'resource-id="[^"]*:id/btnLayoutReset"' "$WORK/layout_10_closed.xml"; then
    record_fail "收起一级控制条后排版面板仍可见"
  else
    record_pass "收起一级控制条后排版面板同步隐藏"
  fi
  record_pass "排版面板截图已保存（$WORK/layout_04_panel.png / layout_09_reset.png）"
}

if [ "${TT_SOURCED:-0}" != "1" ]; then
  SCENARIO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  source "$SCENARIO_DIR/../lib.sh"
  tt_run_standalone tt_scenario_layout "$@"
fi
