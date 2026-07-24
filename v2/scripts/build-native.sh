#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/build/native/arm64-v8a"
ENGINE_SOURCE="$ROOT/native/baize_engine_42_4.c"
DEEP_SOURCE="$ROOT/native/baize_deep_snapshot.c"
API=${ANDROID_API:-26}

find_ndk() {
  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    printf '%s\n' "$ANDROID_NDK_HOME"
    return 0
  fi
  if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
    find "$ANDROID_SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1
    return 0
  fi
  return 1
}

NDK=$(find_ndk) || { echo "未找到 Android NDK" >&2; exit 1; }
HOST_TAG=linux-x86_64
[ "$(uname -s)" = "Darwin" ] && HOST_TAG=darwin-x86_64
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
STRIP="$TOOLCHAIN/llvm-strip"

[ -x "$CC" ] || { echo "未找到编译器：$CC" >&2; exit 1; }
[ -f "$ENGINE_SOURCE" ] || { echo "未找到白泽原生扫描引擎源码：$ENGINE_SOURCE" >&2; exit 1; }
[ -f "$DEEP_SOURCE" ] || { echo "未找到深度不可变快照源码：$DEEP_SOURCE" >&2; exit 1; }
mkdir -p "$OUT"

COMMON_FLAGS='-std=c11 -O2 -fPIE -pie -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wall -Wextra -Wformat=2 -Wshadow -Wconversion'
# shellcheck disable=SC2086
"$CC" $COMMON_FLAGS "$ENGINE_SOURCE" -o "$OUT/baize_engine"
# shellcheck disable=SC2086
"$CC" $COMMON_FLAGS "$DEEP_SOURCE" -o "$OUT/baize_deep_snapshot"
"$STRIP" --strip-unneeded "$OUT/baize_engine" "$OUT/baize_deep_snapshot"
chmod 0755 "$OUT/baize_engine" "$OUT/baize_deep_snapshot"
file "$OUT/baize_engine" "$OUT/baize_deep_snapshot"
echo "已生成白泽 ARM64 扫描引擎与深度不可变快照引擎：$OUT"
