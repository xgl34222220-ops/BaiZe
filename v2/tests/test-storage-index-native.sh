#!/usr/bin/env bash
# 验证原生索引器与 shell 退路输出完全一致。
# 这条路径是扫描耗时的大头：旧实现每个文件 fork 约 9 个进程，
# 实测 6000 个文件 51 秒；原生索引器同数据 0.012 秒。
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-storage-index
rm -rf "$T"; mkdir -p "$T/media/0"

CC=${CC:-cc}
command -v "$CC" >/dev/null 2>&1 || { echo "  [skip] 无 C 编译器"; exit 0; }
command -v base64 >/dev/null 2>&1 || { echo "  [skip] 缺少 base64"; exit 0; }

ENGINE="$T/baize_engine"
"$CC" -std=c11 -O1 -o "$ENGINE" "$ROOT/v2/native/baize_engine_42_4.c" 2>/dev/null || {
  echo "  [skip] 引擎编译失败"; exit 0; }

# 构造覆盖各个分桶的样本
mk() { mkdir -p "$(dirname "$T/media/0/$1")"; printf '%*s' "$2" '' > "$T/media/0/$1"; }
mk "Download/app.apk" 100
mk "Download/pkg.APKS" 100          # 大小写混合，必须按大小写不敏感匹配
mk "Download/bundle.xapk" 100
mk "Pictures/a.jpg" 500
mk "Pictures/b.PNG" 500
mk "Movies/clip.mp4" 900
mk "Docs/notes.txt" 10
mk "Docs/empty.dat" 0               # 空文件桶
mk "Docs/big.bin" 2000000           # 大文件桶（配合下面 max_file_mb=1）
mk "Docs/half.part" 50              # 下载中间态，应被跳过
mk "Docs/x.crdownload" 50           # 同上
mk "Docs/no_extension" 41
mk "Docs/unfinished.PART" 51
mk "Docs/plain.dat" 42              # 不进任何扩展名桶
mkdir -p "$T/media/0/带空格 目录"
printf 'x' > "$T/media/0/带空格 目录/中文文件.apk"

cp "$ROOT/v2/module/storage-index.sh" "$ROOT/v2/module/abi-resolve.sh" "$T/"
# 归类分类表是模块的一部分，两条路径都要读它
mkdir -p "$T/config"
cp "$ROOT/config/organizer-categories.conf" "$T/config/"
mkdir -p "$T/bin/$(uname -m)"
cp "$ENGINE" "$T/bin/$(uname -m)/baize_engine" 2>/dev/null || true

run() {
  local out=$1 engine=$2
  rm -rf "$T/state"; mkdir -p "$T/state"
  # 大文件阈值来自 max_file_mb，默认 256 MB；调小以便用小样本覆盖该分桶。
  printf 'max_file_mb=1\n' > "$T/state/config.conf"
  # 环境变量必须由 env 传入：从 "$@" 展开出的 VAR=x 不会被 shell 当作赋值。
  ( cd "$T" && env BAIZE_STATE_DIR="$T/state" BAIZE_MEDIA_ROOT="$T/media" \
      BAIZE_NATIVE_ENGINE="$engine" bash ./storage-index.sh refresh manual >/dev/null 2>&1 )
  rm -rf "$out"
  cp -a "$T/state/index" "$out" 2>/dev/null || { echo "  [FAIL] 索引未生成（engine=$engine）"; return 1; }
}

run "$T/out-native" "$ENGINE" || exit 1
run "$T/out-shell"  /nonexistent-engine || exit 1

# 原生路径必须自己遍历目录，不能再偷偷调用 shell find。
rm -rf "$T/state-no-find" "$T/fake-bin"; mkdir -p "$T/state-no-find" "$T/fake-bin"
printf 'max_file_mb=1\n' > "$T/state-no-find/config.conf"
cat >"$T/fake-bin/find" <<'SH'
#!/bin/sh
echo "native path unexpectedly called find" >&2
exit 99
SH
chmod +x "$T/fake-bin/find"
(
  cd "$T"
  env PATH="$T/fake-bin:$PATH" BAIZE_STATE_DIR="$T/state-no-find" BAIZE_MEDIA_ROOT="$T/media" \
    BAIZE_NATIVE_ENGINE="$ENGINE" bash ./storage-index.sh refresh manual >/dev/null 2>&1
) || { echo "  [FAIL] 原生索引仍依赖 shell find"; exit 1; }
no_find_apk=$(tr -cd '\000' < "$T/state-no-find/index/apk-files.nul" | wc -c | tr -d ' ')
[ "$no_find_apk" = 4 ] || { echo "  [FAIL] 原生单遍索引 APK 桶异常，实际 $no_find_apk"; exit 1; }
grep -Fq 'engine=baize-storage-index-v6-native-one-pass' "$T/state-no-find/index/meta.env" || {
  echo "  [FAIL] 未进入原生单遍索引路径"; exit 1;
}

fail=0
for f in storage-files.nul apk-files.nul empty-files.nul large-files.nul \
         organizer-files.nul duplicate-candidates.tsv; do
  if ! diff -q "$T/out-shell/$f" "$T/out-native/$f" >/dev/null 2>&1; then
    echo "  [FAIL] $f 两条路径输出不一致"
    fail=$((fail + 1))
  fi
done

# coverage 的 files / bytes 列必须一致（第 5、6 列）
n_cov=$(cut -f5,6 "$T/out-native/coverage.tsv")
s_cov=$(cut -f5,6 "$T/out-shell/coverage.tsv")
[ "$n_cov" = "$s_cov" ] || { echo "  [FAIL] coverage 的文件数/字节数不一致"; fail=$((fail+1)); }

# 具体分桶断言，防止两条路径同时错
apk_n=$(tr -cd '\000' < "$T/out-native/apk-files.nul" | wc -c | tr -d ' ')
[ "$apk_n" = 4 ] || { echo "  [FAIL] APK 桶应为 4 条（含大小写混合与中文名），实际 $apk_n"; fail=$((fail+1)); }
empty_n=$(tr -cd '\000' < "$T/out-native/empty-files.nul" | wc -c | tr -d ' ')
[ "$empty_n" = 1 ] || { echo "  [FAIL] 空文件桶应为 1 条，实际 $empty_n"; fail=$((fail+1)); }
large_n=$(tr -cd '\000' < "$T/out-native/large-files.nul" | wc -c | tr -d ' ')
[ "$large_n" = 1 ] || { echo "  [FAIL] 大文件桶应为 1 条，实际 $large_n"; fail=$((fail+1)); }
# .part / .crdownload 不得出现在总索引里
if tr '\000' '\n' < "$T/out-native/storage-files.nul" | grep -qE '\.(part|crdownload)$'; then
  echo "  [FAIL] 下载中间态文件不应进入索引"; fail=$((fail+1))
fi

# 模拟 HyperOS / Android 16：raw media 根不可见，但 public emulated 根可见。
rm -rf "$T/state-public" "$T/public"; mkdir -p "$T/state-public" "$T/public/0/Download"
printf 'max_file_mb=1\n' > "$T/state-public/config.conf"
printf 'fallback' > "$T/public/0/Download/public.apk"
( cd "$T" && env BAIZE_STATE_DIR="$T/state-public" BAIZE_MEDIA_ROOT="$T/missing-media" \
    BAIZE_PUBLIC_MEDIA_ROOT="$T/public" BAIZE_NATIVE_ENGINE="$ENGINE" \
    bash ./storage-index.sh refresh storage-analysis >/dev/null 2>&1 )
public_count=$(tr -cd '\000' < "$T/state-public/index/apk-files.nul" | wc -c | tr -d ' ')
[ "$public_count" = 1 ] || { echo "  [FAIL] raw media 缺失时 public emulated APK 应被索引，实际 $public_count"; fail=$((fail+1)); }

if [ "$fail" -eq 0 ]; then echo "原生索引器与 shell 退路输出一致：ok"; else echo "$fail 项失败"; exit 1; fi
