#!/usr/bin/env bash
# Ad detection end-to-end: local feed, fingerprinting, skip and undo.
# Needs a device that allows test APK installs and the debug app with ad-detection built in.
set -euo pipefail
cd "$(dirname "$0")/.."
source ./lib.sh

BUILD="$PWD/build"
say "== 10_skip_undo"
require_device

bash ./gen_fixtures.sh "$BUILD"
adb reverse tcp:8123 tcp:8123 >/dev/null
python -m http.server 8123 --directory "$BUILD" >/dev/null 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT

adb shell input keyevent KEYCODE_WAKEUP >/dev/null
adb shell am start -a android.intent.action.VIEW \
  -d "http://127.0.0.1:8123/feed.xml" -t "application/rss+xml" "$PKG" >/dev/null
record "INFO feed intent sent"

feed_subscribed() {
  db_query "select count(*) from Feeds where download_url like '%feed.xml%'" | grep -q '^[1-9]'
}
if wait_until 180 feed_subscribed; then
  record "PASS feed subscribed"
else
  record "FAIL feed not subscribed"
  exit 1
fi

say "请完成以下两步（脚本随后自动断言）："
say "  1. 打开该 feed 的下载，确保两集都已下载"
say "  2. 在 feed 设置中打开 '检测并跳过广告'，保持屏幕常亮"
say "等待 AdScanWorker 完成（最多 10 分钟）..."

has_segments() {
  db_query "select count(*) from ad_segments where tier = 0" | grep -q '^[1-9]'
}
if wait_until 600 has_segments; then
  record "PASS ad segments stored"
else
  record "FAIL no auto ad segments"
  exit 1
fi

rows=$(db_query "select media_id, start_ms, end_ms, kind, tier from ad_segments order by start_ms")
say "$rows"
record "INFO segments: $(echo "$rows" | tr '\n' ';')"

preroll=$(db_query "select count(*) from ad_segments where start_ms < 90000 and tier = 0")
if [ "$preroll" -ge 1 ]; then
  record "PASS preroll segment present"
else
  record "FAIL preroll segment missing"
  exit 1
fi

say "手动步骤：播放第二集，等待出现 '已跳过 ... 秒广告' 提示条，然后点击 '撤销'。"
adb logcat -c
if wait_until 300 bash -c "adb logcat -d | grep -q 'Skipped ad to'"; then
  record "PASS playback skip observed in logcat"
else
  record "FAIL playback skip not observed"
  exit 1
fi

say "再手动点击一次提示条的 '撤销'，脚本会检查 DB state。"
state() {
  db_query "select count(*) from ad_segments where state = 1" | grep -q '^[1-9]'
}
if wait_until 120 state; then
  record "PASS undo persisted (state=1)"
else
  record "FAIL undo not persisted"
  exit 1
fi

record "SUCCESS 10_skip_undo"
