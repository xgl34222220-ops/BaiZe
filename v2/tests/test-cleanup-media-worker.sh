#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
BUILD=$(mktemp -d); trap 'rm -rf "$BUILD"' EXIT
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -XX:-UsePerfData -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -d "$BUILD" "$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/CleanupMediaWorker.java" "$ROOT/v2/tests/CleanupMediaWorkerTest.java"
java -XX:-UsePerfData -cp "$BUILD" CleanupMediaWorkerTest "$BUILD/fixtures"
