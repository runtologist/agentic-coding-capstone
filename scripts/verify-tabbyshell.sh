#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKSHOP_ROOT="$(cd "$REPO_ROOT/.." && pwd)"

source "$SCRIPT_DIR/env.sh"

exec "$WORKSHOP_ROOT/capstones/tabbyshell/verify" \
  --lang scala \
  --implementation-root "$REPO_ROOT/tabbyshell" \
  "$@"
