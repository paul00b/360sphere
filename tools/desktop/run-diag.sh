#!/usr/bin/env bash
# Diagnostic bas niveau du recalage. Usage : tools/desktop/run-diag.sh <session> [options]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SESSION="$1"; shift
MAIN=care.primary.sphere360.desktop.StitchDiag "$ROOT/tools/desktop/run-harness.sh" "$SESSION" /dev/null "$@"
