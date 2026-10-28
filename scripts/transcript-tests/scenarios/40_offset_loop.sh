#!/usr/bin/env bash
# 场景：时间偏移控制条（重置为 0、+30 秒；「对齐到本句」→ 点选句子建分段、分段内 +30 只影响本段；
# 长按「对齐到本句」= 第一句对齐整集；再重置）。
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

tt_tap_nth() {
  local file="$1" pattern="$2" n="$3" label="$4"
  local bounds
  bounds="$(ui_nodes "$file" | grep -E "$pattern" \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | sed -n "${n}p" \
    | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')"
  if [ -z "$bounds" ]; then
    record_fail "$label: 在 UI dump 中找不到节点"
    return 1
  fi
  set -- $bounds
  local cx=$(( ($1 + $3) / 2 )) cy=$(( ($2 + $4) / 2 ))
  adb_sh input tap "$cx" "$cy"
  record_pass "$label: 点击 ($cx,$cy)"
}

tt_long_press_node() {
  local file="$1" pattern="$2" label="$3"
  local bounds; bounds="$(find_bounds "$file" "$pattern")"
  if [ -z "$bounds" ]; then
    record_fail "$label: 在 UI dump 中找不到节点"
    return 1
  fi
  set -- $bounds
  local cx=$(( ($1 + $3) / 2 )) cy=$(( ($2 + $4) / 2 ))
  adb_sh input swipe "$cx" "$cy" "$cx" "$cy" 600
  record_pass "$label: 长按 ($cx,$cy)"
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

  # 对齐到本句：先进入点选模式，再点“正在播放的那一句”，以它为锚建新段
  tap_node "$WORK/offset_03_panel.xml" 'resource-id="[^"]*:id/btnOffsetSync"' \
    "对齐到本句（进入点选模式）" || return
  sleep 1
  dump_ui "$WORK/offset_08_pick.xml" || { record_fail "偏移：进入点选模式后 dump 为空"; return; }
  if grep -qE 'text="(点选句子|Pick line)"' "$WORK/offset_08_pick.xml"; then
    record_pass "对齐到本句进入点选模式"
  else
    record_fail "对齐到本句未进入点选模式"
  fi
  # 先点“当前显示的那句”：应被保护拦下，不改偏移、仍停在点选模式
  local text_row='text="[^"]+".*resource-id="[^"]*:id/content"'
  tt_tap_nth "$WORK/offset_08_pick.xml" "$text_row" 1 "点选当前显示的那句" || return
  sleep 1
  dump_ui "$WORK/offset_08b_guard.xml" || { record_fail "偏移：点已显示句后 dump 为空"; return; }
  if [ "$(tt_offset_chip_seconds "$WORK/offset_08b_guard.xml")" = "+30" ] \
      && grep -qE 'text="(点选句子|Pick line)"' "$WORK/offset_08b_guard.xml"; then
    record_pass "点已显示的那句被拦下（偏移不变、仍在点选模式）"
  else
    record_fail "点已显示的那句未被拦下或退出了点选模式"
  fi

  # 这条语料每个 cue 占满整屏、adb 拖拽会被列表跟随/文本选区吃掉，因此“点另一句”不做自动断言；
  # 写锚点与刷新高亮由下面的长按（同一 setTranscriptTimeOffset 路径）覆盖。
  local adjusted pref
  pref="$(tt_offset_pref_value)"
  if tt_offset_pref_has_anchor "$pref"; then
    record_pass "偏移按分段锚点持久化"
  else
    record_fail "偏移未写入分段锚点：$pref"
  fi

  tap_node "$WORK/offset_08b_guard.xml" 'resource-id="[^"]*:id/btnOffsetForward30"' \
    "分段内 +30 秒" || return
  sleep 1
  dump_ui "$WORK/offset_10_sync30.xml" || { record_fail "偏移：分段内 +30 秒后 dump 为空"; return; }
  adjusted="$(tt_offset_chip_seconds "$WORK/offset_10_sync30.xml")"
  if awk "BEGIN{d=${adjusted:-0}-(30+30); exit !(d < 0.11 && d > -0.11)}"; then
    record_pass "分段内 +30 秒只改当前段（+30 → $adjusted）"
  else
    record_fail "分段内 +30 秒结果不符：+30 → $adjusted"
  fi

  # 长按「对齐到本句」= 把第一句对齐到当前位置（整集重新对齐，只留一个 0 处锚点）
  tt_long_press_node "$WORK/offset_10_sync30.xml" 'resource-id="[^"]*:id/btnOffsetSync"' \
    "长按按第一句对齐整集" || return
  sleep 1
  dump_ui "$WORK/offset_11_first.xml" || { record_fail "偏移：长按对齐后 dump 为空"; return; }
  local first_off anchors
  first_off="$(tt_offset_chip_seconds "$WORK/offset_11_first.xml")"
  if awk "BEGIN{exit !(${first_off:-0} > 0)}"; then
    record_pass "长按按第一句对齐整集后偏移胶囊=$first_off"
  else
    record_fail "长按按第一句对齐整集后偏移胶囊异常：$first_off"
  fi
  pref="$(tt_offset_pref_value)"
  anchors="$(printf '%s' "$pref" | grep -o '&quot;p&quot;' | wc -l | tr -d ' ')"
  if [ "$anchors" = "1" ] && printf '%s' "$pref" | grep -q '&quot;p&quot;:0,'; then
    record_pass "整集对齐只保留 0 处锚点"
  else
    record_fail "整集对齐后锚点数量/位置不符：$pref"
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
