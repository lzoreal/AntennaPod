#!/usr/bin/env bash
# AntennaPod 转录（transcript）功能真机自动化验证。
#
# 流程：备份设备数据库/偏好 -> 本地 VTT 服务 + adb reverse -> 写入测试数据库 ->
# 安装并冷启动播放器转录页 -> 截图/UI dump/logcat 取证 -> 悬浮窗开关取证 -> 恢复设备。
#
# 只在 debug 包（run-as 可用）上工作。任何失败都会先恢复设备再退出。
# 详见 docs/transcript-handover.md。

set -uo pipefail

REPO_ROOT="$(dirname "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")")"

PKG="de.danoeh.antennapod.debug"
SERIAL=""
PORT=8765
VTT_DIR="D:/MyTranscript/site/the-rest-is-history/transcripts/zh"
VTT_FILE="1._Greatness.vtt"
MEDIA_ID=""
POSITION=5000
WORK=""
SKIP_INSTALL=0
KEEP=0
ISOLATE_NETWORK=0
RESTORE_ONLY=0
CAPTURE_WAIT=8

usage() {
  cat <<'USAGE'
用法: scripts/transcript_device_test.sh [选项]

  --vtt-dir DIR      本地 VTT 目录
  --vtt-file NAME    目录内的 VTT 文件名
  --media-id N       目标 FeedMedia.id；留空则取偏好里的 currentlyPlayingMedia
  --position MS      写入测试库的 FeedMedia.position (默认 5000)
  --port N           本地 HTTP 端口 (默认 8765)
  --serial SERIAL    多设备时指定 adb 序列号
  --package PKG      包名 (默认 de.danoeh.antennapod.debug)
  --work DIR         工作目录；恢复时用于定位备份
  --skip-install     跳过安装，复用设备上已装的包
  --isolate-network  测试期间关闭 Wi-Fi/移动数据，结束后按原值恢复
  --keep             结束后不恢复设备（打印恢复命令）
  --restore-only     只执行恢复（需 --work 指向之前的备份目录）
  -h, --help         显示帮助
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --vtt-dir) VTT_DIR="$2"; shift 2;;
    --vtt-file) VTT_FILE="$2"; shift 2;;
    --media-id) MEDIA_ID="$2"; shift 2;;
    --position) POSITION="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --serial) SERIAL="$2"; shift 2;;
    --package) PKG="$2"; shift 2;;
    --work) WORK="$2"; shift 2;;
    --skip-install) SKIP_INSTALL=1; shift;;
    --isolate-network) ISOLATE_NETWORK=1; shift;;
    --keep) KEEP=1; shift;;
    --restore-only) RESTORE_ONLY=1; shift;;
    -h|--help) usage; exit 0;;
    *) echo "未知参数: $1" >&2; usage; exit 2;;
  esac
done

PYTHON="$(command -v python || command -v python3 || true)"
[ -n "$PYTHON" ] || { echo "需要 python/python3 用于 SQLite 与本地 HTTP 服务" >&2; exit 3; }
command -v adb >/dev/null 2>&1 || { echo "PATH 中没有 adb" >&2; exit 3; }

if [ "$RESTORE_ONLY" != "1" ] && [ -z "$WORK" ]; then
  WORK="$HOME/.qoder-cn/tmp/transcript-ondevice/run-$(date +%Y%m%d-%H%M%S)"
fi
[ -d "$WORK" ] || mkdir -p "$WORK" || exit 3

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
adb_sh() { MSYS_NO_PATHCONV=1 "${ADB[@]}" shell "$@"; }

RESULTS="$WORK/results.txt"
: > "$RESULTS"
FAILED=0
HTTP_PID=""
DEVICE_MODIFIED=0
BACKUP_OK=0
record_pass() { printf 'PASS  %s\n' "$1" | tee -a "$RESULTS"; }
record_fail() { printf 'FAIL  %s\n' "$1" | tee -a "$RESULTS"; FAILED=1; }

wake_device() {
  local quiet="${1:-}"
  local before
  before="$(adb_sh dumpsys power 2>/dev/null | tr -d '\r' | grep -m1 -oE 'mWakefulness=[A-Za-z]+' || true)"
  adb_sh input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  sleep 1
  adb_sh wm dismiss-keyguard >/dev/null 2>&1
  local focus
  focus="$(adb_sh dumpsys window 2>/dev/null | tr -d '\r' | grep -m1 'mCurrentFocus' || true)"
  if ! printf '%s' "$focus" | grep -q "$PKG"; then
    # MIUI 的 AOD 只认手势：KEYCODE_WAKEUP 可能只点亮表盘（截图 ~30KB），上滑一次并等动画结束
    local size
    size="$(adb_sh wm size 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1)"
    if [ -n "$size" ]; then
      adb_sh input swipe "$(( ${size%x*} / 2 ))" "$(( ${size#*x} * 3 / 4 ))" \
        "$(( ${size%x*} / 2 ))" "$(( ${size#*x} / 4 ))" 150 >/dev/null 2>&1
      sleep 3
    fi
  fi
  local state
  state="$(adb_sh dumpsys power 2>/dev/null | tr -d '\r' | grep -m1 -oE 'mWakefulness=[A-Za-z]+' || true)"
  if [ "$state" != "mWakefulness=Awake" ]; then
    record_fail "设备无法唤醒（${state:-未知}）：请手动点亮屏幕后重跑"
    return 1
  fi
  if adb_sh dumpsys window 2>/dev/null | tr -d '\r' | grep -q "isKeyguardShowing=true"; then
    record_fail "设备仍在锁屏（isKeyguardShowing=true）：请手动解锁屏幕后重跑"
    return 1
  fi
  [ -n "$quiet" ] || record_pass "设备屏幕已点亮且未锁屏（唤醒前 ${before:-未知}）"
  return 0
}

capture_screen() {
  local out="$1" size
  "${ADB[@]}" exec-out screencap -p > "$out"
  size="$(wc -c < "$out" | tr -d ' ')"
  # 息屏/Doze 只返回全黑帧（~19KB），AOD 表盘 ~30KB，真实界面截图数百 KB 起
  [ "${size:-0}" -ge 60000 ] && return 0
  record_fail "截图全黑（${size:-0} 字节）：$out，屏幕没有真正亮起来，请手动解锁后重跑"
  return 1
}

ui_nodes() { sed 's/></>\n</g' "$1"; }

dump_ui() {
  local out="$1"
  MSYS_NO_PATHCONV=1 "${ADB[@]}" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
  MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-out cat /sdcard/window_dump.xml > "$out" 2>/dev/null || true
  [ -s "$out" ]
}

find_bounds() {
  ui_nodes "$1" | grep -m1 -E "$2" \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1 \
    | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/'
}

tap_node() {
  local file="$1" pattern="$2" label="$3"
  local bounds; bounds="$(find_bounds "$file" "$pattern")"
  if [ -z "$bounds" ]; then
    record_fail "$label: 在 UI dump 中找不到节点"
    return 1
  fi
  set -- $bounds
  local cx=$(( ($1 + $3) / 2 )) cy=$(( ($2 + $4) / 2 ))
  adb_sh input tap "$cx" "$cy"
  record_pass "$label: 点击 ($cx,$cy)"
}

summary() {
  echo
  echo "== 结果 =="
  cat "$RESULTS"
  echo "产物目录: $WORK"
}

restore_device() {
  if [ "$KEEP" = "1" ]; then
    echo "--keep：跳过恢复。之后用以下命令恢复："
    echo "  bash scripts/transcript_device_test.sh --restore-only --work \"$WORK\""
    [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null
    return
  fi
  if [ "$BACKUP_OK" != "1" ]; then
    echo "没有可用备份，跳过设备恢复"
    [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null
    return
  fi
  echo "== 恢复设备 =="
  adb_sh am force-stop "$PKG"
  if [ "$DEVICE_MODIFIED" = "1" ]; then
    MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-in run-as "$PKG" sh -c "cat > databases/Antennapod.db" \
      < "$WORK/Antennapod.db.orig" || record_fail "恢复数据库失败"
    adb_sh run-as "$PKG" rm -f databases/Antennapod.db-journal databases/Antennapod.db-wal \
      databases/Antennapod.db-shm
    local f
    for f in Antennapod.db-journal Antennapod.db-wal Antennapod.db-shm; do
      if [ -f "$WORK/$f.orig" ]; then
        MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-in run-as "$PKG" sh -c "cat > databases/$f" \
          < "$WORK/$f.orig" || record_fail "恢复 $f 失败"
      fi
    done
    MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-in run-as "$PKG" \
      sh -c "cat > shared_prefs/${PKG}_preferences.xml" < "$WORK/prefs.orig.xml" \
      || record_fail "恢复偏好失败"
    adb_sh run-as "$PKG" rm -f "shared_prefs/${PKG}_preferences.xml.bak"
    MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-out run-as "$PKG" cat databases/Antennapod.db \
      > "$WORK/Antennapod.restored.db"
    "$PYTHON" - "$WORK/Antennapod.restored.db" <<'PY' || record_fail "恢复后的数据库校验失败"
import sqlite3, sys
conn = sqlite3.connect(sys.argv[1])
print("restored integrity:", conn.execute("pragma integrity_check").fetchone()[0])
left = conn.execute(
    "select count(*) from FeedItems where podcastindex_transcript_url like '%127.0.0.1%'").fetchone()[0]
print("test urls left:", left)
assert left == 0, "设备上仍残留测试 URL"
PY
    record_pass "数据库与偏好已恢复"
  fi

  if [ -f "$WORK/reverse.added" ] && [ "$(cat "$WORK/reverse.added")" = "1" ]; then
    "${ADB[@]}" reverse --remove "tcp:${PORT}" >/dev/null 2>&1 || true
  fi
  [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null
  if [ "$ISOLATE_NETWORK" = "1" ]; then
    local wifi data
    wifi="$(tr -d '\r' < "$WORK/wifi_on.before")"
    data="$(tr -d '\r' < "$WORK/mobile_data.before")"
    if [ "$wifi" = "1" ]; then adb_sh svc wifi enable; else adb_sh svc wifi disable; fi
    if [ "$data" = "1" ]; then adb_sh svc data enable; else adb_sh svc data disable; fi
  fi
  adb_sh monkey -p "$PKG" 1 >/dev/null 2>&1 || true
}

abort() {
  echo "错误: $1" >&2
  record_fail "$1"
  restore_device
  summary
  exit 1
}

trap 'echo; restore_device; summary; exit 130' INT TERM

preflight() {
  local devices
  devices="$("${ADB[@]}" devices | tail -n +2 | grep -c 'device$' || true)"
  [ "${devices:-0}" -ge 1 ] || abort "没有连接的设备（adb devices 为空）"
  "${ADB[@]}" shell pm list packages | grep -q "package:$PKG" \
    || abort "设备上没有安装 $PKG（release 包不支持 run-as）"
  MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-out run-as "$PKG" ls databases >/dev/null 2>&1 \
    || abort "run-as $PKG 失败：需要 debuggable 构建且设备已授权 USB 调试"
  [ -f "$VTT_DIR/$VTT_FILE" ] || abort "找不到 VTT 文件: $VTT_DIR/$VTT_FILE"
  wake_device || abort "设备屏幕不可用，无法进行 UI 验证"
  record_pass "前置检查: 设备、包、run-as、VTT 文件均就绪"
}

snapshot_state() {
  "${ADB[@]}" reverse --list > "$WORK/reverse.before.txt" 2>/dev/null || : > "$WORK/reverse.before.txt"
  adb_sh settings get global wifi_on | tr -d '\r' > "$WORK/wifi_on.before"
  adb_sh settings get global mobile_data | tr -d '\r' > "$WORK/mobile_data.before"
  echo "wifi_on=$(cat "$WORK/wifi_on.before") mobile_data=$(cat "$WORK/mobile_data.before")"
}

backup_device() {
  adb_sh am force-stop "$PKG"
  MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-out run-as "$PKG" cat databases/Antennapod.db \
    > "$WORK/Antennapod.db.orig" || abort "备份数据库失败"
  [ -s "$WORK/Antennapod.db.orig" ] || abort "备份数据库为空"
  local db_list f
  db_list="$(MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-out run-as "$PKG" ls databases 2>/dev/null | tr -d '\r')"
  for f in Antennapod.db-journal Antennapod.db-wal Antennapod.db-shm; do
    if printf '%s\n' "$db_list" | grep -qx "$f"; then
      MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-out run-as "$PKG" cat "databases/$f" > "$WORK/$f.orig" \
        || abort "备份 $f 失败"
      echo "已备份侧车文件 $f"
    fi
  done
  MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-out run-as "$PKG" \
    cat "shared_prefs/${PKG}_preferences.xml" > "$WORK/prefs.orig.xml" || abort "备份偏好失败"
  BACKUP_OK=1
  if [ -z "$MEDIA_ID" ]; then
    # PlaybackPreferences 的键带包名前缀，例如 de.danoeh.antennapod.preferences.currentlyPlayingMedia
    local key
    for key in currentlyPlayingMedia lastPlayedFeedMediaId; do
      MEDIA_ID="$(grep -oE "name=\"[^\"]*${key}\" value=\"[0-9]+\"" "$WORK/prefs.orig.xml" \
        | sed -E 's/.*value="([0-9]+)".*/\1/' | head -1 || true)"
      [ -n "$MEDIA_ID" ] && { echo "从偏好 $key 推断目标 FeedMedia.id=$MEDIA_ID"; break; }
    done
  fi
  "$PYTHON" - "$WORK/Antennapod.db.orig" <<'PY' || abort "备份数据库无法用 SQLite 打开"
import sqlite3, sys
conn = sqlite3.connect(sys.argv[1])
print("backup integrity:", conn.execute("pragma integrity_check").fetchone()[0])
print("journal_mode:", conn.execute("pragma journal_mode").fetchone()[0])
print("feedmedia rows:", conn.execute("select count(*) from FeedMedia").fetchone()[0])
PY
  record_pass "备份完成: $WORK"
}

prepare_db() {
  local url="http://127.0.0.1:${PORT}/${VTT_FILE}"
  "$PYTHON" - "$WORK/Antennapod.db.orig" "$WORK/Antennapod.test.db" "${MEDIA_ID}" "$url" "$POSITION" <<'PY'
import shutil, sqlite3, sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

src, dst, media_id, url, position = sys.argv[1:6]
shutil.copyfile(src, dst)
conn = sqlite3.connect(dst)
if media_id:
    row = conn.execute("select id from FeedMedia where id = ?", (int(media_id),)).fetchone()
    if row is None:
        sys.exit(f"FeedMedia.id={media_id} 不存在，请用 --media-id 指定")
else:
    row = conn.execute(
        "select fm.id from FeedMedia fm join FeedItems fi on fm.feeditem = fi.id "
        "where fm.position > 0 order by fm.position desc limit 1").fetchone()
    if row is None:
        row = conn.execute("select id from FeedMedia order by id limit 1").fetchone()
    if row is None:
        sys.exit("数据库里没有 FeedMedia")
    media_id = row[0]
before = conn.execute(
    "select fm.id, fi.title, fm.position, fi.podcastindex_transcript_url "
    "from FeedMedia fm join FeedItems fi on fm.feeditem = fi.id where fm.id = ?",
    (media_id,)).fetchone()
conn.execute(
    "update FeedItems set podcastindex_transcript_url = ?, podcastindex_transcript_type = 'text/vtt' "
    "where id = (select feeditem from FeedMedia where id = ?)", (url, media_id))
if position:
    conn.execute("update FeedMedia set position = ? where id = ?", (int(position), media_id))
conn.commit()
after = conn.execute(
    "select fm.id, fi.title, fm.position, fi.podcastindex_transcript_url, "
    "fi.podcastindex_transcript_type from FeedMedia fm join FeedItems fi on fm.feeditem = fi.id "
    "where fm.id = ?", (media_id,)).fetchone()
print("before:", before)
print("after :", after)
integrity = conn.execute("pragma integrity_check").fetchone()[0]
print("integrity:", integrity)
assert integrity == "ok", "测试数据库 integrity_check 未通过"
print("size:", len(open(dst, "rb").read()))
open(dst + ".media_id", "w").write(str(media_id))
PY
  [ $? -eq 0 ] || abort "准备测试数据库失败"
  MEDIA_ID="$(cat "$WORK/Antennapod.test.db.media_id")"
  echo "使用 FeedMedia.id=$MEDIA_ID"
  record_pass "测试数据库已准备（URL 指向 127.0.0.1:$PORT）"
}

start_server() {
  "$PYTHON" -m http.server "$PORT" --bind 127.0.0.1 --directory "$VTT_DIR" \
    > "$WORK/http.log" 2>&1 &
  HTTP_PID=$!
  sleep 1
  kill -0 "$HTTP_PID" 2>/dev/null || abort "本地 HTTP 服务启动失败，见 $WORK/http.log"
  "$PYTHON" - "http://127.0.0.1:${PORT}/${VTT_FILE}" <<'PY' || abort "本地 VTT 无法访问"
import sys, urllib.request
print("served:", urllib.request.urlopen(sys.argv[1], timeout=5).read(60))
PY
  if grep -q "tcp:${PORT}" "$WORK/reverse.before.txt"; then
    echo 0 > "$WORK/reverse.added"
    echo "测试前已存在 tcp:${PORT} 转发，结束后不删除"
  else
    "${ADB[@]}" reverse "tcp:${PORT}" "tcp:${PORT}" || abort "adb reverse 失败"
    echo 1 > "$WORK/reverse.added"
  fi
  if [ "$ISOLATE_NETWORK" = "1" ]; then
    adb_sh svc wifi disable
    adb_sh svc data disable
  fi
  record_pass "本地服务与端口转发就绪 (pid=$HTTP_PID)"
}

push_db() {
  adb_sh am force-stop "$PKG"
  MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-in run-as "$PKG" sh -c "cat > databases/Antennapod.db" \
    < "$WORK/Antennapod.test.db" || abort "写入测试数据库失败"
  DEVICE_MODIFIED=1
  adb_sh run-as "$PKG" rm -f databases/Antennapod.db-journal databases/Antennapod.db-wal \
    databases/Antennapod.db-shm
  MSYS_NO_PATHCONV=1 "${ADB[@]}" exec-out run-as "$PKG" cat databases/Antennapod.db \
    > "$WORK/Antennapod.check.db"
  "$PYTHON" - "$WORK/Antennapod.check.db" "$MEDIA_ID" <<'PY' || abort "回读校验失败"
import sqlite3, sys
conn = sqlite3.connect(sys.argv[1])
print("integrity:", conn.execute("pragma integrity_check").fetchone()[0])
row = conn.execute(
    "select fm.position, fi.podcastindex_transcript_url from FeedMedia fm "
    "join FeedItems fi on fm.feeditem = fi.id where fm.id = ?", (int(sys.argv[2]),)).fetchone()
print("device row:", row)
assert row and row[1] and "127.0.0.1" in row[1], "设备上看不到测试 URL"
PY
  record_pass "设备数据库已写入并校验"
}

install_app() {
  if [ "$SKIP_INSTALL" = "1" ]; then
    echo "--skip-install：跳过安装"
    return
  fi
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" --console=plain :app:installPlayDebug || abort "安装失败"
  record_pass "安装 :app:installPlayDebug 完成"
}

launch_page() {
  local intent=(-a de.danoeh.antennapod.intents.MAIN_ACTIVITY
    -n "$PKG/de.danoeh.antennapod.activity.MainActivity"
    --ez open_player true --ez open_transcript true)
  adb_sh am force-stop "$PKG"
  "${ADB[@]}" logcat -b all -c
  # 安装与备份阶段耗时较长，屏幕可能已重新熄灭；息屏时 am start 与 input 全部无效
  wake_device || return 1
  # 冷启动时播放器还没 attach，底部抽屉处于锁定态，第一次 start 只负责恢复播放上下文
  adb_sh am start "${intent[@]}" > "$WORK/start1.txt" 2>&1 || abort "启动 Activity 失败"
  sleep 6
  # 播放服务只在播放中产生位置事件，冷启动不会自动播放
  adb_sh input keyevent KEYCODE_MEDIA_PLAY
  sleep 2
  # 第二次走 onNewIntent -> handleNavIntent，此时抽屉已解锁，才会真正展开并切到转录页
  adb_sh am start "${intent[@]}" > "$WORK/start2.txt" 2>&1 || abort "二次启动 Activity 失败"
  sleep "$CAPTURE_WAIT"
  capture_screen "$WORK/01_transcript_page.png" || return 1
  dump_ui "$WORK/01_transcript_page.xml" || record_fail "转录页 UI dump 为空"
  "${ADB[@]}" logcat -d -v threadtime -b all > "$WORK/transcript.log"
}

check_page() {
  if grep -q "FATAL EXCEPTION" "$WORK/transcript.log"; then
    record_fail "转录页出现崩溃，见 $WORK/transcript.log"
  else
    record_pass "转录页无崩溃"
  fi
  local rows
  rows="$(ui_nodes "$WORK/01_transcript_page.xml" | grep -c "resource-id=\"${PKG}:id/content\"" || true)"
  if [ "${rows:-0}" -ge 1 ]; then
    record_pass "转录页已打开，可见行数: $rows"
  else
    record_fail "转录页未打开：确认用的是 --ez 而不是 -e、二次 start 走 onNewIntent，且没有传 episode_id"
  fi
  local bilingual
  bilingual="$(LC_ALL=C; ui_nodes "$WORK/01_transcript_page.xml" \
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

floating_service_running() {
  adb_sh dumpsys activity services "$PKG" 2>/dev/null | tr -d '\r' \
    | grep -q "TranscriptFloatingWindowService"
}

check_floating() {
  local op
  op="$(adb_sh appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null | tr -d '\r')"
  echo "SYSTEM_ALERT_WINDOW: $op"
  case "$op" in
    *allow*) ;;
    *) adb_sh appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
       echo "已尝试授予悬浮窗权限；MIUI 等系统可能仍需在设置里手动开启" ;;
  esac

  # uiautomator 只能 dump 当前活动窗口，悬浮窗是另一个 window，所以用服务状态做自动判定
  if floating_service_running; then
    capture_screen "$WORK/04_floating.png" || return 1
    record_pass "悬浮窗服务已在运行（偏好已是开启状态，不再点菜单切换）"
    return
  fi

  # 点击路径要求屏幕点亮，息屏时 input tap 会被丢弃
  wake_device quiet || return
  dump_ui "$WORK/02_floating_before.xml" || { record_fail "悬浮窗：首次 UI dump 为空"; return; }
  tap_node "$WORK/02_floating_before.xml" 'overflow_menu_button|更多选项|More options' "打开溢出菜单" \
    || return
  sleep 1
  dump_ui "$WORK/03_menu_items.xml" || { record_fail "悬浮窗：菜单项 dump 为空"; return; }
  tap_node "$WORK/03_menu_items.xml" 'Floating transcript|悬浮' "开启悬浮转录" || return
  sleep 4
  capture_screen "$WORK/04_floating.png" || return 1
  if floating_service_running; then
    record_pass "悬浮窗服务已启动（文字内容与间距看 $WORK/04_floating.png）"
  else
    record_fail "悬浮窗服务未启动：确认当前媒体有转录、正在播放且已授予悬浮窗权限"
  fi
}

run_restore_only() {
  [ -f "$WORK/Antennapod.db.orig" ] || { echo "$WORK 下没有备份" >&2; exit 4; }
  [ -f "$WORK/reverse.before.txt" ] || : > "$WORK/reverse.before.txt"
  [ -f "$WORK/reverse.added" ] || echo 0 > "$WORK/reverse.added"
  [ -f "$WORK/wifi_on.before" ] || echo 1 > "$WORK/wifi_on.before"
  [ -f "$WORK/mobile_data.before" ] || echo 1 > "$WORK/mobile_data.before"
  BACKUP_OK=1
  DEVICE_MODIFIED=1
  restore_device
  summary
  [ "$FAILED" = "0" ]
}

if [ "$RESTORE_ONLY" = "1" ]; then
  run_restore_only
  exit $?
fi

preflight
snapshot_state
backup_device
prepare_db
start_server
push_db
install_app
launch_page || abort "转录页取证中止"
check_page
check_floating
restore_device
summary
echo
echo "请用 Read 打开截图人工确认双语行、当前句高亮、悬浮窗间距与控件："
ls "$WORK"/*.png
[ "$FAILED" = "0" ]
