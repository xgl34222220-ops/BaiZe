#!/usr/bin/env bash
# 在宿主机上编译并运行 C 引擎的单元测试与端到端测试。
# 不需要 Android NDK，用宿主 cc 即可，供 CI 和本地提交前使用。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
CC=${CC:-cc}
BUILD=${TMPDIR:-/tmp}/baize-native-tests
rm -rf "$BUILD"; mkdir -p "$BUILD"

echo "==> 编译深度风险判定单元测试"
"$CC" -std=c11 -O1 -Wall -Wextra -Werror \
  -o "$BUILD/test-deep-risk" "$ROOT/v2/tests/test-deep-risk-classification.c"
"$BUILD/test-deep-risk"

echo
echo "==> 编译扫描引擎（宿主机构建，仅用于测试）"
"$CC" -std=c11 -O1 -Wall -Wextra -Wno-unused-parameter \
  -o "$BUILD/baize_engine" "$ROOT/v2/native/baize_engine_42_4.c"

echo
echo "==> 端到端：风险分级与用户覆盖"
bash "$ROOT/v2/tests/test-deep-risk-engine.sh" "$BUILD/baize_engine"

echo
echo "==> deep_max_auto_risk 边界"
bash "$ROOT/v2/tests/test-deep-risk-ceiling.sh"

echo
echo "原生测试全部通过"
