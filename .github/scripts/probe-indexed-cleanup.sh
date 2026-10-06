#!/usr/bin/env bash
set -uo pipefail
# Independent entry points both report evidence even if one fails its assertions.
status=0
bash .github/scripts/probe-apk-deletion.sh || status=1
bash .github/scripts/probe-storage-deletion.sh || status=1
exit "$status"
