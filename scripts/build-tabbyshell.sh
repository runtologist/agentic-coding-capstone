#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

source "$SCRIPT_DIR/env.sh"

cd "$REPO_ROOT/tabbyshell"

# Per repository convention: after changing sbt configuration, shut down the
# sbt server so it restarts cleanly with the new configuration.
sbt --client shutdown || true
sbt --client compile
sbt --client test
sbt --client assembly
