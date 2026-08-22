#!/usr/bin/env bash
# 端到端跑一次归类：验证分类落盘、撤销记录格式、多用户覆盖、
# 以及索引扩展名对齐后 .m4a/.opus/.epub 这类文件确实能被归类。
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
command -v base64 >/dev/null 2>&1 || { echo "  [skip] 缺少 base64"; exit 0; }

T=${TMPDIR:-/tmp}/baize-org-e2e
rm -rf "$T"; mkdir -p "$T/module/config" "$T/state/index" "$T/media"

cp "$ROOT/v2/module/organizer-worker.sh" "$T/module/"
# 索引已经由测试直接写好，这里放一个只做校验的桩，避免依赖真实索引器
cat > "$T/module/storage-index.sh" <<'STUB'
#!/bin/sh
# 测试桩：索引由测试夹具预先写好，直接成功返回
exit 0
STUB
chmod +x "$T/module/storage-index.sh"
cp "$ROOT/config/organizer-categories.conf" "$T/module/config/"
cat > "$T/state/config.conf" <<'CONF'
organizer_conflict_policy=1
organizer_undo_retention=10
organizer_media_scan=0
CONF

# user 0 与 user 10（工作资料）各放一批文件
mk() { mkdir -p "$(dirname "$T/media/$1")"; printf 'x%.0s' $(seq 1 "${2:-8}") > "$T/media/$1"; }
for u in 0 10; do
  mk "$u/Download/photo.jpg"
  mk "$u/Download/song.m4a"        # 旧索引漏收的扩展名
  mk "$u/Download/voice.OPUS"      # 大小写混合 + 旧索引漏收
  mk "$u/Download/book.epub"       # 旧索引漏收
  mk "$u/Download/clip.webm"       # 旧索引漏收
  mk "$u/Download/notes.txt"
  mk "$u/Download/skip.tmp"        # 应跳过
  mk "$u/Pictures/album.jpg"       # 相册不是归类来源
done

# 索引：把所有文件喂进去，模拟共享索引已经收全
find "$T/media" -type f -print0 > "$T/state/index/organizer-files.nul"

run_out=$(cd "$T" && env \
  BAIZE_STATE_DIR="$T/state" BAIZE_MEDIA_ROOT="$T/media" \
  bash ./module/organizer-worker.sh organize manual e2e 2>&1)
code=$?

fail=0
say_fail() { echo "  [FAIL] $1"; fail=$((fail + 1)); }

[ "$code" = 0 ] || say_fail "退出码 $code：$run_out"

echo "归类端到端"
echo
echo "— 两个用户的文件都应被归类（含工作资料 user 10）—"
for u in 0 10; do
  for pair in "图片/photo.jpg" "音频/song.m4a" "音频/voice.OPUS" "电子书/book.epub" \
              "视频/clip.webm" "文档/notes.txt"; do
    [ -f "$T/media/$u/BaiZe归类/$pair" ] || say_fail "user $u 缺少 BaiZe归类/$pair"
  done
done

echo "— 不该动的文件必须留在原地 —"
for u in 0 10; do
  [ -f "$T/media/$u/Download/skip.tmp" ] || say_fail "user $u 的 .tmp 不应被移动"
  [ -f "$T/media/$u/Pictures/album.jpg" ] || say_fail "user $u 的相册文件不应被移动"
done

echo "— 撤销记录 —"
undo="$T/state/organizer-last.json"
[ -s "$undo" ] || say_fail "未生成撤销记录"
if [ -s "$undo" ]; then
  python3 - "$undo" <<'PY' || fail=$((fail + 1))
import base64, json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
moves = data["moves"]
assert moves, "撤销记录为空"
need = {"sourceB64","destinationB64","destinationFingerprint",
        "sourceUid","sourceGid","sourceMode","collisionAction"}
users = set()
for m in moves:
    missing = need - set(m)
    assert not missing, f"撤销条目缺字段 {missing}"
    src = base64.b64decode(m["sourceB64"]).decode()
    dst = base64.b64decode(m["destinationB64"]).decode()
    assert "/BaiZe归类/" in dst, f"目标不在归类目录: {dst}"
    assert isinstance(m["sourceMode"], int), "sourceMode 应为十进制整数"
    assert m["destinationFingerprint"].count(":") == 3, "指纹格式应为 dev:ino:size:mtime"
    users.add(src.split("/media/")[1].split("/")[0])
assert users == {"0", "10"}, f"撤销记录应覆盖两个用户，实际 {users}"
print(f"    撤销记录 {len(moves)} 条，覆盖用户 {sorted(users)}")
PY
fi

echo "— 兜底索引不得覆写共享索引 —"
[ -s "$T/state/index/organizer-files.nul" ] || say_fail "共享索引被清空了"

echo
if [ "$fail" -eq 0 ]; then echo "全部通过"; else echo "$fail 项失败"; exit 1; fi
