#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
ORGANIZER = ROOT / "v2/module/organizer-worker.sh"
TESTS = ROOT / "v2/tests/test-organizer-transactions.sh"
text = ORGANIZER.read_text()


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one literal match, found {count}: {old[:80]!r}")
    text = text.replace(old, new, 1)


def regex_once(pattern: str, replacement: str) -> None:
    global text
    text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"expected one regex match, found {count}: {pattern[:100]!r}")

replace_once(
'''uint_config() {
  uc_value=$(config_value "$1"); uc_default=$2; uc_min=$3; uc_max=$4
  case "$uc_value" in ''|*[!0-9]*) uc_value=$uc_default ;; esac
  [ "$uc_value" -lt "$uc_min" ] && uc_value=$uc_min
  [ "$uc_value" -gt "$uc_max" ] && uc_value=$uc_max
  echo "$uc_value"
}
proc_start_ticks()''',
'''uint_config() {
  uc_value=$(config_value "$1"); uc_default=$2; uc_min=$3; uc_max=$4
  case "$uc_value" in ''|*[!0-9]*) uc_value=$uc_default ;; esac
  [ "$uc_value" -lt "$uc_min" ] && uc_value=$uc_min
  [ "$uc_value" -gt "$uc_max" ] && uc_value=$uc_max
  echo "$uc_value"
}
limit_value() {
  lv_value=$1; lv_default=$2; lv_min=$3; lv_max=$4
  case "$lv_value" in ''|*[!0-9]*) lv_value=$lv_default ;; esac
  [ "$lv_value" -lt "$lv_min" ] && lv_value=$lv_min
  [ "$lv_value" -gt "$lv_max" ] && lv_value=$lv_max
  echo "$lv_value"
}
proc_start_ticks()'''
)

regex_once(
    r'''append_tree_files\(\) \{.*?\n\}\n\nappend_known_app_roots\(\) \{''',
'''append_tree_files() {
  at_root=$1
  [ -d "$at_root" ] || return 0
  if [ "${AUTO_TRIGGER:-0}" = 1 ] && command -v timeout >/dev/null 2>&1; then
    timeout "${AUTO_ROOT_SCAN_SECONDS:-20}" find "$at_root" -xdev -mindepth 1 -maxdepth 12 \
      \( -type d \( \
        -iname cache -o -iname code_cache -o -iname no_backup -o \
        -iname databases -o -iname shared_prefs -o -iname lib -o \
        -iname tmp -o -iname temp -o -iname thumbnails -o -iname .thumbnails -o \
        -iname stickers -o -iname emoji -o -iname crash -o -iname crashes -o \
        -iname assets -o -iname resources -o -iname res -o -iname textures -o \
        -iname sprites -o -iname shaders -o -iname bundles -o -iname streamingassets \
      \) -prune \) -o \( -type f -print0 \) 2>/dev/null >>"$INDEX_FILE" || true
  else
    find "$at_root" -xdev -mindepth 1 -maxdepth 12 \
      \( -type d \( \
        -iname cache -o -iname code_cache -o -iname no_backup -o \
        -iname databases -o -iname shared_prefs -o -iname lib -o \
        -iname tmp -o -iname temp -o -iname thumbnails -o -iname .thumbnails -o \
        -iname stickers -o -iname emoji -o -iname crash -o -iname crashes -o \
        -iname assets -o -iname resources -o -iname res -o -iname textures -o \
        -iname sprites -o -iname shaders -o -iname bundles -o -iname streamingassets \
      \) -prune \) -o \( -type f -print0 \) 2>/dev/null >>"$INDEX_FILE"
  fi
}

append_known_app_roots() {'''
)

regex_once(
    r'''append_known_app_roots\(\) \{.*?\n\}\n\nbuild_fallback_index\(\) \{''',
'''append_known_app_roots() {
  user_root=$1
  for candidate in \
    "$user_root/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv" \
    "$user_root/Android/data/com.tencent.mobileqq/files/QQfile_recv" \
    "$user_root/Android/data/com.tencent.tim/Tencent/TIMfile_recv" \
    "$user_root/Android/data/com.tencent.tim/files/TIMfile_recv"; do
    append_tree_files "$candidate"
  done
  for package in \
    com.android.chrome com.chrome.beta com.chrome.dev org.mozilla.firefox org.mozilla.fenix \
    com.microsoft.emmx com.sec.android.app.sbrowser com.heytap.browser com.coloros.browser \
    com.oplus.browser com.mi.globalbrowser com.android.browser com.quark.browser com.UCMobile \
    com.kiwibrowser.browser com.brave.browser; do
    package_root="$user_root/Android/data/$package"
    for suffix in Download Downloads files/Download files/Downloads files/download files/downloads private/received files/received; do
      append_tree_files "$package_root/$suffix"
    done
  done
  for package in com.google.android.gm com.tencent.androidqqmail com.microsoft.office.outlook com.android.email com.netease.mail com.netease.mobimail; do
    package_root="$user_root/Android/data/$package"
    for suffix in attachments Attachments files/attachments files/Attachments data/attachments files/download files/Download; do
      append_tree_files "$package_root/$suffix"
    done
  done
}

build_fallback_index() {'''
)

regex_once(
    r'''build_fallback_index\(\) \{.*?\n\}\n\nb64\(\) \{''',
'''build_fallback_index() {
  mkdir -p "${INDEX_FILE%/*}"
  : >"$INDEX_FILE"
  for fb_user_root in "$MEDIA_ROOT"/[0-9]*; do
    [ -d "$fb_user_root" ] || continue
    find "$fb_user_root" -xdev -mindepth 1 -maxdepth 1 -type f -print0 2>/dev/null >>"$INDEX_FILE"
    for fb_public in \
      "$fb_user_root/Download" "$fb_user_root/Downloads" "$fb_user_root/Documents" \
      "$fb_user_root/Bluetooth" "$fb_user_root/Tencent/QQfile_recv" "$fb_user_root/Tencent/TIMfile_recv"; do
      append_tree_files "$fb_public"
    done
    append_known_app_roots "$fb_user_root"
  done
  chmod 0600 "$INDEX_FILE" 2>/dev/null || true
}

b64() {'''
)

replace_once(
'''MEDIA_SCAN=$(uint_config organizer_media_scan 1 0 1)
RENAMED=0
DEDUPLICATED=0
rm -f "$STOP_FILE" "$RESULT_FILE" "$MEDIA_QUEUE"
write_running "正在建立增量共享索引" 0 0 "" 1

if ! BAIZE_STATE_DIR="$STATE_DIR" BAIZE_MEDIA_ROOT="$MEDIA_ROOT" "$SHELL_BIN" "$INDEXER" ensure organizer-detached >>"$LOG_FILE" 2>&1; then
  echo "共享索引失败，切换独立 Root 安全兜底索引" >>"$LOG_FILE"
fi
[ -s "$ORGANIZER_INDEX" ] && INDEX_FILE="$ORGANIZER_INDEX" || INDEX_FILE="$ALL_INDEX"
if [ ! -s "$INDEX_FILE" ]; then
  INDEX_FILE="$ALL_INDEX"
  write_running "共享索引为空，正在执行安全兜底发现" 0 0 "$MEDIA_ROOT" 1
  build_fallback_index
fi
''',
'''MEDIA_SCAN=$(uint_config organizer_media_scan 1 0 1)
AUTO_TRIGGER=0
case "$TRIGGER" in scheduler:*) AUTO_TRIGGER=1 ;; esac
AUTO_MAX_SECONDS=$(limit_value "${BAIZE_ORGANIZER_AUTO_MAX_SECONDS:-180}" 180 30 1800)
AUTO_MAX_FILES=$(limit_value "${BAIZE_ORGANIZER_AUTO_MAX_FILES:-2000}" 2000 1 20000)
AUTO_ROOT_SCAN_SECONDS=$(limit_value "${BAIZE_ORGANIZER_AUTO_ROOT_SCAN_SECONDS:-20}" 20 2 120)
[ "$AUTO_TRIGGER" = 1 ] && MEDIA_SCAN=0
RENAMED=0
DEDUPLICATED=0
rm -f "$STOP_FILE" "$RESULT_FILE" "$MEDIA_QUEUE"

if [ "$AUTO_TRIGGER" = 1 ]; then
  INDEX_FILE="$ORGANIZER_INDEX"
  write_running "正在快速检查下载与接收目录" 0 0 "" 1
  build_fallback_index
else
  write_running "正在建立增量共享索引" 0 0 "" 1
  if ! BAIZE_STATE_DIR="$STATE_DIR" BAIZE_MEDIA_ROOT="$MEDIA_ROOT" "$SHELL_BIN" "$INDEXER" ensure organizer-detached >>"$LOG_FILE" 2>&1; then
    echo "共享索引失败，切换独立 Root 安全兜底索引" >>"$LOG_FILE"
  fi
  [ -s "$ORGANIZER_INDEX" ] && INDEX_FILE="$ORGANIZER_INDEX" || INDEX_FILE="$ALL_INDEX"
  if [ ! -s "$INDEX_FILE" ]; then
    INDEX_FILE="$ALL_INDEX"
    write_running "共享索引为空，正在执行安全兜底发现" 0 0 "$MEDIA_ROOT" 1
    build_fallback_index
  fi
fi
'''
)

replace_once(
'''REQUESTED=0; MOVED=0; SKIPPED=0; FAILED=0; BYTES=0; CURRENT=0
UNDO_TMP="$UNDO_DIR/.${TASK_ID}.tmp.$$"
''',
'''REQUESTED=0; MOVED=0; SKIPPED=0; FAILED=0; BYTES=0; CURRENT=0; AUTO_LIMIT_REACHED=0
AUTO_DEADLINE=$((STARTED + AUTO_MAX_SECONDS))
UNDO_TMP="$UNDO_DIR/.${TASK_ID}.tmp.$$"
'''
)

replace_once(
'''while IFS= read -r -d '' FILE_PATH; do
  CURRENT=$((CURRENT + 1))
  [ -f "$STOP_FILE" ] && break
''',
'''while IFS= read -r -d '' FILE_PATH; do
  if [ "$AUTO_TRIGGER" = 1 ]; then
    auto_now=$(date +%s)
    if [ "$CURRENT" -ge "$AUTO_MAX_FILES" ] || [ "$auto_now" -ge "$AUTO_DEADLINE" ]; then
      AUTO_LIMIT_REACHED=1
      break
    fi
  fi
  CURRENT=$((CURRENT + 1))
  [ -f "$STOP_FILE" ] && break
  if [ $((CURRENT % 200)) -eq 0 ]; then
    write_running "正在检查可归类文件" "$CURRENT" "$TOTAL" "$FILE_PATH"
  fi
'''
)

replace_once(
'''write_running "文件归类收尾中" "$TOTAL" "$TOTAL" "" 1
if [ -f "$STOP_FILE" ]; then write_result "文件归类已停止" 0 1 "$REQUESTED" "$MOVED" "$SKIPPED" "$FAILED" "$BYTES"; exit 9; fi
''',
'''if [ "$AUTO_LIMIT_REACHED" = 1 ]; then
  write_running "本轮自动归类达到安全上限，正在保存结果" "$CURRENT" "$TOTAL" "" 1
  write_result "本轮自动归类已完成，剩余文件下次继续" 1 0 "$REQUESTED" "$MOVED" "$SKIPPED" "$FAILED" "$BYTES"
  echo "自动归类达到安全上限：处理 $CURRENT/$TOTAL，移动 $MOVED 个，剩余文件下次继续" >>"$LOG_FILE"
  exit 0
fi
write_running "文件归类收尾中" "$TOTAL" "$TOTAL" "" 1
if [ -f "$STOP_FILE" ]; then write_result "文件归类已停止" 0 1 "$REQUESTED" "$MOVED" "$SKIPPED" "$FAILED" "$BYTES"; exit 9; fi
'''
)

ORGANIZER.write_text(text)

auto_test = ROOT / "v2/tests/test-organizer-auto-budget.sh"
auto_test.write_text(r'''#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-organizer-auto-budget
rm -rf "$T"
mkdir -p "$T/module" "$T/media/0/Download" "$T/media/0/DCIM/Camera" "$T/state"
cp "$ROOT/v2/module/organizer-worker.sh" "$T/module/organizer-worker.sh"
cat >"$T/module/storage-index.sh" <<'SH'
#!/bin/sh
touch "${BAIZE_STATE_DIR}/full-index-was-called"
sleep 10
exit 0
SH
chmod 0755 "$T/module/storage-index.sh" "$T/module/organizer-worker.sh"
printf one >"$T/media/0/Download/one.pdf"
printf two >"$T/media/0/Download/two.pdf"
for i in $(seq 1 200); do printf ignored >"$T/media/0/DCIM/Camera/ignored-$i.jpg"; done
cat >"$T/state/config.conf" <<'CONF'
enabled=1
organizer_conflict_policy=1
organizer_undo_retention=2
organizer_media_scan=1
CONF
start=$(date +%s)
BAIZE_STATE_DIR="$T/state" \
BAIZE_MEDIA_ROOT="$T/media" \
BAIZE_CONFIG_PATH="$T/state/config.conf" \
BAIZE_SHELL_BIN=/usr/bin/busybox \
BAIZE_ORGANIZER_AUTO_MAX_FILES=1 \
BAIZE_ORGANIZER_AUTO_MAX_SECONDS=30 \
busybox ash "$T/module/organizer-worker.sh" organize scheduler:interval auto-budget
elapsed=$(( $(date +%s) - start ))
[ "$elapsed" -lt 8 ] || { echo "automatic organizer took too long: ${elapsed}s" >&2; exit 1; }
[ ! -e "$T/state/full-index-was-called" ] || { echo "automatic organizer incorrectly invoked full shared index" >&2; exit 1; }
grep -q '^success=1$' "$T/state/organizer-result.env"
grep -q '^phase=本轮自动归类已完成，剩余文件下次继续$' "$T/state/organizer-result.env"
grep -q '^moved=1$' "$T/state/organizer-result.env"
[ "$(find "$T/media/0/Download" -maxdepth 1 -type f | wc -l)" -eq 1 ]
[ "$(find "$T/media/0/BaiZe归类/文档" -maxdepth 1 -type f | wc -l)" -eq 1 ]
[ "$(find "$T/media/0/DCIM/Camera" -type f | wc -l)" -eq 200 ]
echo 'automatic organizer budget: ok'
''')

test_text = TESTS.read_text()
footer = "echo 'organizer transactions: ok'\n"
if test_text.count(footer) != 1:
    raise SystemExit("organizer transaction footer mismatch")
TESTS.write_text(test_text.replace(footer, 'bash "$ROOT/v2/tests/test-organizer-auto-budget.sh"\n' + footer, 1))

print("automatic organizer performance fix applied")
