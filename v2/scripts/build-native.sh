#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/build/native/arm64-v8a"
SOURCE="$ROOT/native/baize_engine.c"
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
mkdir -p "$OUT"
"$CC" -std=c11 -O2 -fPIE -pie -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
  -Wall -Wextra -Werror -Wformat=2 -Wshadow -Wconversion \
  "$SOURCE" -o "$OUT/baize_engine"
"$STRIP" --strip-unneeded "$OUT/baize_engine"
chmod 0755 "$OUT/baize_engine"
file "$OUT/baize_engine"
echo "已生成原生扫描器：$OUT/baize_engine"
