#!/usr/bin/env bash
set -euo pipefail
cat >> v2/.gitignore <<'EOF'
!build/
!build/release-prep/
!build/release-prep/**
EOF
python3 v2/scripts/prepare-release-v2.5.6-stage.py
