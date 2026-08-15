#!/usr/bin/env sh
# 构建白泽原生扫描引擎与深度不可变快照引擎。
#
# 此前只编 arm64-v8a，导致 armeabi-v7a 的 32 位设备（恰恰是最需要清理
# 垃圾的那批老机器）在 native-scan.sh 里直接 exit 8 用不了。
# 现在默认编 arm64-v8a / armeabi-v7a / x86_64 三个 ABI。
#
# 可用 BAIZE_ABIS 覆盖，例如只编 arm64：BAIZE_ABIS=arm64-v8a sh build-native.sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD="$ROOT/build/native"
ENGINE_SOURCE="$ROOT/native/baize_engine_42_4.c"
DEEP_SOURCE="$ROOT/native/baize_deep_snapshot.c"
API=${ANDROID_API:-26}
ABIS=${BAIZE_ABIS:-"arm64-v8a armeabi-v7a x86_64"}

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

# ABI 到 clang 目标三元组的映射。
abi_triple() {
  case "$1" in
    arm64-v8a)   printf 'aarch64-linux-android' ;;
    armeabi-v7a) printf 'armv7a-linux-androideabi' ;;
    x86_64)      printf 'x86_64-linux-android' ;;
    x86)         printf 'i686-linux-android' ;;
    *) return 1 ;;
  esac
}

NDK=$(find_ndk) || { echo "未找到 Android NDK" >&2; exit 1; }
HOST_TAG=linux-x86_64
[ "$(uname -s)" = "Darwin" ] && HOST_TAG=darwin-x86_64
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
STRIP="$TOOLCHAIN/llvm-strip"

[ -f "$ENGINE_SOURCE" ] || { echo "未找到白泽原生扫描引擎源码：$ENGINE_SOURCE" >&2; exit 1; }
[ -f "$DEEP_SOURCE" ] || { echo "未找到深度不可变快照源码：$DEEP_SOURCE" >&2; exit 1; }
[ -x "$STRIP" ] || { echo "未找到 llvm-strip：$STRIP" >&2; exit 1; }

COMMON_FLAGS='-std=c11 -O2 -fPIE -pie -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wall -Wextra -Wformat=2 -Wshadow -Wconversion'

built=0
for abi in $ABIS; do
  triple=$(abi_triple "$abi") || { echo "不支持的 ABI：$abi" >&2; exit 1; }
  CC="$TOOLCHAIN/${triple}${API}-clang"
  if [ ! -x "$CC" ]; then
    echo "跳过 $abi：未找到编译器 $CC" >&2
    continue
  fi
  OUT="$BUILD/$abi"
  mkdir -p "$OUT"
  # shellcheck disable=SC2086
  "$CC" $COMMON_FLAGS "$ENGINE_SOURCE" -o "$OUT/baize_engine"
  # shellcheck disable=SC2086
  "$CC" $COMMON_FLAGS "$DEEP_SOURCE" -o "$OUT/baize_deep_snapshot"
  "$STRIP" --strip-unneeded "$OUT/baize_engine" "$OUT/baize_deep_snapshot"
  chmod 0755 "$OUT/baize_engine" "$OUT/baize_deep_snapshot"
  file "$OUT/baize_engine" "$OUT/baize_deep_snapshot"
  echo "已生成 $abi 引擎：$OUT"
  built=$((built + 1))
done

[ "$built" -gt 0 ] || { echo "没有成功构建任何 ABI" >&2; exit 1; }

# arm64 是必须产物，缺失直接失败，避免悄悄发出一个不含主力架构的包。
[ -x "$BUILD/arm64-v8a/baize_engine" ] || {
  echo "arm64-v8a 引擎缺失，拒绝继续" >&2
  exit 1
}
echo "共构建 $built 个 ABI"
