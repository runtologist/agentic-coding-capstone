#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKSHOP_ROOT="$(cd "$REPO_ROOT/.." && pwd)"

source "$SCRIPT_DIR/env.sh"

# Prefer the vendored harness inside this repo; fall back to the workshop copy.
if [ -x "$REPO_ROOT/harness/tabbyshell/verify" ]; then
  VERIFY="$REPO_ROOT/harness/tabbyshell/verify"
else
  VERIFY="$WORKSHOP_ROOT/capstones/tabbyshell/verify"
fi

exec "$VERIFY" \
  --lang scala \
  --implementation-root "$REPO_ROOT/tabbyshell" \
  "$@"
