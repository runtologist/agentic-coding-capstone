#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKSHOP_ROOT="$(cd "$REPO_ROOT/.." && pwd)"

source "$SCRIPT_DIR/env.sh"

echo "==> Scala/Java version"
java -version

echo "==> TabbyShell harness smoke: --version"
"$WORKSHOP_ROOT/capstones/tabbyshell/run_tests" \
  --lang scala \
  --project-root "$WORKSHOP_ROOT/capstones/tabbyshell" \
  --implementation-root "$REPO_ROOT/tabbyshell" \
  --filter version

echo "==> TabbyShell harness smoke: pwd"
"$WORKSHOP_ROOT/capstones/tabbyshell/run_tests" \
  --lang scala \
  --project-root "$WORKSHOP_ROOT/capstones/tabbyshell" \
  --implementation-root "$REPO_ROOT/tabbyshell" \
  --filter pwd
