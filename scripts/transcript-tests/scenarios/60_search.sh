#!/usr/bin/env bash
# 场景：转录内搜索（打开搜索条、命中计数、上下条、无匹配、关闭）。
# 单独运行: bash scripts/transcript-tests/scenarios/60_search.sh [选项]

tt_scenario_search() {
  wake_device quiet || return

  tt_ensure_controls_bar "$WORK/search_01_controls.xml" || return
  sleep 1
  dump_ui "$WORK/search_02_controls.xml" || { record_fail "搜索：控制条 UI dump 为空"; return; }
  tap_node "$WORK/search_02_controls.xml" \
    'content-desc="(搜索转录|Search transcript)"' "打开搜索条" || return
  sleep 1
  dump_ui "$WORK/search_03_open.xml" || { record_fail "搜索：搜索条 UI dump 为空"; return; }
  assert_dump_has "$WORK/search_03_open.xml" 'resource-id="[^"]*:id/searchInput"' "搜索输入框已显示"

  tap_node "$WORK/search_03_open.xml" 'resource-id="[^"]*:id/searchInput"' "聚焦搜索输入框" || return
  # 设备可能残留旧转录缓存，盖过脚本注入的 VTT；用通用词保证当前页面有命中。
  adb_sh input text "the"
  if ! wait_dump_text "$WORK/search_04_results.xml" 'text="1/[0-9]+"' 6; then
    record_fail "搜索：输入 the 后未看到 1/N 计数"
    return
  fi
  record_pass "搜索：the 命中计数为 1/N"
  capture_screen "$WORK/search_05_results.png" || return

  tap_node "$WORK/search_04_results.xml" \
    'content-desc="(下一个匹配|Next match)"' "下一条匹配" || return
  if ! wait_dump_text "$WORK/search_06_next.xml" 'text="2/[0-9]+"' 6; then
    record_fail "搜索：下一条后未看到 2/N 计数"
    return
  fi
  record_pass "搜索：下一条计数变为 2/N"
  capture_screen "$WORK/search_07_next.png" || return

  tap_node "$WORK/search_06_next.xml" 'resource-id="[^"]*:id/searchInput"' "聚焦搜索输入框" || return
  adb_sh input keyevent KEYCODE_MOVE_END
  adb_sh input text "zzzz"
  if ! wait_dump_text "$WORK/search_08_no_results.xml" 'text="(无匹配|No matches)"' 6; then
    record_fail "搜索：无匹配时未显示提示"
    return
  fi
  record_pass "搜索：无匹配时显示提示"

  tap_node "$WORK/search_08_no_results.xml" \
    'content-desc="(关闭搜索|Close search)"' "关闭搜索条" || return
  sleep 1
  dump_ui "$WORK/search_09_closed.xml" || { record_fail "搜索：关闭后 UI dump 为空"; return; }
  if grep -q 'resource-id="[^"]*:id/searchInput"' "$WORK/search_09_closed.xml"; then
    record_fail "搜索：关闭后输入框仍可见"
  else
    record_pass "搜索：关闭后输入框已隐藏"
  fi
  capture_screen "$WORK/search_10_closed.png" || return
}

if [ "${TT_SOURCED:-0}" != "1" ]; then
  SCENARIO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  source "$SCENARIO_DIR/../lib.sh"
  tt_run_standalone tt_scenario_search "$@"
fi
