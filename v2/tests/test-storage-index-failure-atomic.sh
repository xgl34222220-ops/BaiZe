#!/usr/bin/env bash
# 回归：原生索引器失败时 refresh 必须中止，不能把 index.lock/tmp 的半成品
# mv 到正式 index 目录覆盖上一份完整索引。
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-index-failure-atomic
rm -rf "$T"; mkdir -p "$T/media/0/Download" "$T/state/index" "$T/fake"
printf 'new-data' > "$T/media/0/Download/new.apk"
printf 'max_file_mb=1\n' > "$T/state/config.conf"

# 旧正式索引：失败 refresh 后必须逐字节保留。
printf 'OLD_INDEX\0' > "$T/state/index/storage-files.nul"
printf 'status\tgroup\tuser\tvolume\tfiles\tbytes\tpath\treason\nOLD\n' > "$T/state/index/coverage.tsv"
printf 'OLD_APK\0' > "$T/state/index/apk-files.nul"
printf 'OLD_EMPTY\0' > "$T/state/index/empty-files.nul"
printf 'OLD_LARGE\0' > "$T/state/index/large-files.nul"
printf 'OLD_ORG\0' > "$T/state/index/organizer-files.nul"
printf '1\tT0xE\n' > "$T/state/index/duplicate-candidates.tsv"
printf 'epoch=1\nfiles=1\nbytes=1\n' > "$T/state/index/meta.env"
cp -a "$T/state/index" "$T/before"

# 模拟“写了一点半成品然后失败”的原生引擎。
cat > "$T/fake/engine" <<'SH'
#!/bin/sh
records=
apk=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --records) records=$2; shift 2 ;;
    --apk) apk=$2; shift 2 ;;
    *) shift ;;
  esac
done
[ -n "$records" ] && printf 'CORRUPT\0' >> "$records"
[ -n "$apk" ] && printf 'CORRUPT\0' >> "$apk"
exit 5
SH
chmod +x "$T/fake/engine"

set +e
( cd "$ROOT/v2/module" && env \
    BAIZE_STATE_DIR="$T/state" \
    BAIZE_MEDIA_ROOT="$T/media" \
    BAIZE_EXTRA_STORAGE_ROOTS="$T/media/0" \
    BAIZE_NATIVE_ENGINE="$T/fake/engine" \
    BAIZE_ORGANIZER_CATEGORIES="$ROOT/config/organizer-categories.conf" \
    bash ./storage-index.sh refresh test >/dev/null 2>&1 )
rc=$?
set -e

[ "$rc" -ne 0 ] || { echo "  [FAIL] 原生索引器失败却返回成功"; exit 1; }

fail=0
for f in storage-files.nul coverage.tsv apk-files.nul empty-files.nul large-files.nul \
         organizer-files.nul duplicate-candidates.tsv meta.env; do
  if ! cmp -s "$T/before/$f" "$T/state/index/$f"; then
    echo "  [FAIL] 失败 refresh 覆盖了旧正式索引：$f"
    fail=$((fail + 1))
  fi
done

# 直接验证 C index-files 的关键错误返回。
CC=${CC:-cc}
if command -v "$CC" >/dev/null 2>&1; then
  ENGINE="$T/baize_engine"
  if "$CC" -std=c11 -O1 -o "$ENGINE" "$ROOT/v2/native/baize_engine_42_4.c" 2>/dev/null; then
    printf '%s\0' "$T/media/0/Download/new.apk" > "$T/list.nul"
    mkdir -p "$T/bad-output-dir"
    set +e
    "$ENGINE" index-files --list "$T/list.nul" --records "$T/bad-output-dir" >/dev/null 2>&1
    io_rc=$?
    : > "$T/stop"
    "$ENGINE" index-files --list "$T/list.nul" --records "$T/direct.nul" --stop "$T/stop" >/dev/null 2>&1
    stop_rc=$?
    set -e
    [ "$io_rc" -ne 0 ] || { echo "  [FAIL] 输出打开失败仍返回 0"; fail=$((fail+1)); }
    [ "$stop_rc" -eq 9 ] || { echo "  [FAIL] stop 应返回 9，实际 $stop_rc"; fail=$((fail+1)); }
  fi
fi

if [ "$fail" -eq 0 ]; then echo "共享索引失败事务与错误传播：ok"; else exit 1; fi
