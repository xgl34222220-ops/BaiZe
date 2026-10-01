#!/usr/bin/env bash
set -uo pipefail
# Each cleanup entrance must leave evidence even when another entrance fails.
status=0
bash .github/scripts/probe-root-cache.sh "$1" || status=1
bash .github/scripts/probe-apk-artwork.sh || status=1
bash .github/scripts/probe-indexed-cleanup.sh || status=1
bash .github/scripts/probe-apk-permissions.sh "$1" || status=1
exit "$status"
