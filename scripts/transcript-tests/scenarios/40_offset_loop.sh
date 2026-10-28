#!/usr/bin/env bash
# 场景：时间偏移控制条（重置为 0、+30 秒、再重置；「对齐到本句」新建分段、分段内 +30 只影响本段）。
# 单独运行: bash scripts/transcript-tests/scenarios/40_offset_loop.sh [选项]

tt_offset_chip_seconds() {
  ui_nodes "$1" | grep 'resource-id="[^"]*:id/txtOffsetValue"' \
    | grep -o 'text="[^"]*"' | head -1 \
    | grep -oE '[+-]?[0-9]+([.][0-9])?' | head -1
}

tt_offset_pref_value() {
  # SharedPreferences 把字符串存成元素文本（<string name="k">v</string>），不是 value 属性
  MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-out run-as "$PKG" \
    cat "shared_prefs/${PKG}_preferences.xml" 2>/dev/null | tr -d '\r' \
    | grep -oE '<string name="prefTranscriptTimeOffsets">[^<]*</string>' | head -1
}

tt_offset_pref_has_anchor() {
  printf '%s' "$1" | grep -qE '((&quot;)|")p((&quot;)|"):'
}

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

  # 对齐到本句：以当前句为起点新建一段（存成锚点数组），之后 ± 只改这一段
  tap_node "$WORK/offset_03_panel.xml" 'resource-id="[^"]*:id/btnOffsetSync"' \
    "对齐到本句" || return
  sleep 1
  dump_ui "$WORK/offset_08_sync.xml" || { record_fail "偏移：对齐到本句后 dump 为空"; return; }
  local synced adjusted pref
  synced="$(tt_offset_chip_seconds "$WORK/offset_08_sync.xml")"
  [ -n "$synced" ] || { record_fail "偏移：对齐到本句后读不到偏移胶囊"; return; }
  record_pass "对齐到本句后偏移胶囊=$synced"
  pref="$(tt_offset_pref_value)"
  if tt_offset_pref_has_anchor "$pref"; then
    record_pass "偏移按分段锚点持久化"
  else
    record_fail "偏移未写入分段锚点：$pref"
  fi

  tap_node "$WORK/offset_08_sync.xml" 'resource-id="[^"]*:id/btnOffsetForward30"' \
    "分段内 +30 秒" || return
  sleep 1
  dump_ui "$WORK/offset_09_sync30.xml" || { record_fail "偏移：分段内 +30 秒后 dump 为空"; return; }
  adjusted="$(tt_offset_chip_seconds "$WORK/offset_09_sync30.xml")"
  if awk "BEGIN{d=${adjusted:-0}-(${synced}+30); exit !(d < 0.11 && d > -0.11)}"; then
    record_pass "分段内 +30 秒只改当前段（$synced → $adjusted）"
  else
    record_fail "分段内 +30 秒结果不符：$synced → $adjusted"
  fi

  tt_tap_and_wait "$WORK/offset_03_panel.xml" 'resource-id="[^"]*:id/btnOffsetReset"' \
    "再次重置偏移" 'text="(偏移：0 秒|Offset: 0 s)"' 4 || return
  record_pass "偏移重置为 0"
  pref="$(tt_offset_pref_value)"
  if tt_offset_pref_has_anchor "$pref"; then
    record_fail "重置后仍留有分段锚点：$pref"
  else
    record_pass "重置清空分段锚点"
  fi
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
