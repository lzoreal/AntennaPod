#!/usr/bin/env bash
# 场景：词级高亮（JSON 词时间轴，TMLT sidecar）。
# 推荐：bash scripts/transcript-tests/run.sh word --media-id 8878 --position 5000
# （8878 = 已下载的 844 音频；没有本地音频时加 --local-audio 推送静音 MP3）
# 当前词无背景，只用文字亮度/字重区分：脚本断言解析/下载/播放位置前进，截图人工确认词态。
# 单独运行: bash scripts/transcript-tests/scenarios/70_word_highlight.sh [选项]

tt_scenario_word_highlight() {
  if [ "${TRANSCRIPT_TYPE:-}" != "application/json" ]; then
    record_fail "词级场景需要 --vtt-file 指向词级 JSON（当前 MIME: ${TRANSCRIPT_TYPE:-}）"
    return
  fi
  local xml="$COMMON_XML"
  # 播放中 uiautomator 拿不到 idle：先暂停取稳定的文本证据，后面再恢复播放
  tt_pause_playback
  if ! dump_ui "$xml"; then
    record_fail "词级：暂停后仍拿不到转录页 UI dump"
    return
  fi
  capture_screen "$COMMON_PNG" || true

  if grep -q "FATAL EXCEPTION" "$WORK/transcript.log" 2>/dev/null; then
    record_fail "词级：出现崩溃，见 $WORK/transcript.log"
  else
    record_pass "词级：无崩溃"
  fi

  local rows
  rows="$(ui_nodes "$xml" | grep -c "resource-id=\"${PKG}:id/content\"" || true)"
  if [ "${rows:-0}" -ge 1 ]; then
    record_pass "词级 JSON 已解析并显示（可见行数 $rows）"
  else
    record_fail "词级 JSON 未显示：确认 MIME 为 application/json 且解析器未返回 null"
  fi

  if ui_nodes "$xml" | grep "resource-id=\"${PKG}:id/content\"" \
      | grep -qiE "parole|prison"; then
    record_pass "可见文本来自 844.json（包含 parole/prison）"
  else
    record_fail "可见文本不是 844.json 的内容，检查服务目录与文件"
  fi

  if grep -q "Done Downloading transcript" "$WORK/transcript.log"; then
    record_pass "日志确认从本地服务下载了 JSON 转录"
  else
    record_fail "日志中没有 Done Downloading transcript（可能命中了旧的本地文件）"
  fi

  tt_resume_playback
  wake_device quiet || return
  local pos1 pos2
  pos1="$(media_position_ms "$WORK/word_02_dumpsys.xml")"
  if [ "${pos1:-0}" -le 5000 ]; then
    # 还没开播才点；已在播放时点一下会把播放暂停。复用暂停时的 dump 取按钮坐标
    tap_node "$xml" 'resource-id="[^"]*:id/butPlay"' "点击播放" || return
    sleep 2
    pos1="$(media_position_ms "$WORK/word_03_pos1.xml")"
  fi
  if [ "${pos1:-0}" -le 5000 ]; then
    record_fail "播放位置未前进：确认当前媒体有本地音频（或用 --local-audio）"
    return
  fi
  record_pass "播放中（$((pos1 / 1000))s）"

  capture_screen "$WORK/word_01.png" || return
  # MediaSession 的 PlaybackState 约 3 秒推送一次；间隔太短会读到同一个位置
  sleep 4
  pos2="$(media_position_ms "$WORK/word_04_pos2.xml")"
  capture_screen "$WORK/word_02.png" || return
  if [ "${pos2:-0}" -gt "${pos1:-0}" ]; then
    record_pass "播放继续前进 $((pos1 / 1000))s → $((pos2 / 1000))s（词级高亮随播放移动）"
  else
    record_fail "播放未继续前进（$((pos1 / 1000))s → ${pos2:-?}ms），无法验证词级移动"
  fi
  record_pass "词级截图已保存（当前词加粗、已读/未读词态看 $WORK/word_01.png、$WORK/word_02.png）"
}

media_position_ms() {
  local dump="$1"
  adb_sh dumpsys media_session > "$dump" 2>/dev/null || true
  awk 'index($0, "de.danoeh.antennapod.debug") { seen = 1 }
       seen && match($0, /position=[0-9]+/) {
         print substr($0, RSTART + 9, RLENGTH - 9); exit
       }' "$dump"
}

if [ "${TT_SOURCED:-0}" != "1" ]; then
  SCENARIO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  source "$SCENARIO_DIR/../lib.sh"
  VTT_DIR="${WORD_VTT_DIR:-D:/TMLT/docs/timings}"
  VTT_FILE="${WORD_VTT_FILE:-844.json}"
  tt_run_standalone tt_scenario_word_highlight "$@"
fi
