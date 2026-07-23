#!/usr/bin/env bash
set -euo pipefail

[ "$#" -eq 1 ] || { echo "usage: $0 <signed.jar>" >&2; exit 2; }
JAR=$1
[ -s "$JAR" ] || { echo "signed JAR missing: $JAR" >&2; exit 2; }

export LC_ALL=C
LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT
STATUS=0
jarsigner -verify -strict -certs "$JAR" >"$LOG" 2>&1 || STATUS=$?
cat "$LOG"

grep -Fq 'jar verified' "$LOG" || {
  echo "jarsigner did not verify the archive" >&2
  exit 1
}

if grep -Eqi 'jar is unsigned|unsigned entr|signature.*(invalid|tampered)|digest error|disabled algorithm|certificate.*(expired|not yet valid|revoked)' "$LOG"; then
  echo "signed JAR contains a forbidden signature error" >&2
  exit 1
fi

case "$STATUS" in
  0)
    ;;
  4)
    # Android application signing certificates are intentionally self-signed. jarsigner -strict
    # reports status 4 because there is no public CA chain, even though every archive entry is
    # signed and the exact certificate fingerprint is checked separately by the workflow.
    grep -Fq 'certificate chain is invalid' "$LOG"
    grep -Fq 'signer certificate is self-signed' "$LOG"
    ;;
  *)
    echo "unexpected jarsigner status: $STATUS" >&2
    exit "$STATUS"
    ;;
esac
