#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
BUILD=$(mktemp -d); trap 'rm -rf "$BUILD"' EXIT
javac -d "$BUILD" "$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/SecureCacheTree.java" "$ROOT/v2/tests/SecureCacheTreeTest.java"
java -cp "$BUILD" SecureCacheTreeTest
