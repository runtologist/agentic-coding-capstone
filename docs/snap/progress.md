# Snap (Scala/ZIO) — Progress Log

## L7 — Commands & Main — 2026-07-03T15:35Z (agent: claude-opus-4-7)

- Implemented `snap/src/main/scala/snap/Commands.scala`: all nine commands
  (init/config/status/log/commit/diff/revert/merge/serve) over a `CmdEnv`
  snapshot + injectable `Output` seam, honoring CONTRACT §13/§15 check
  orderings (commit: load→contributor→message→scan→dirty; revert: version
  parse→known→clean→contributor; merge: local→remote→collision→replay→scan).
- Rewrote `snap/src/main/scala/snap/Main.scala`: `ZIOAppDefault` with
  `Runtime.removeDefaultLoggers`, one-shot env snapshot (cwd/HOME/SNAP_COLOR/
  NO_COLOR/single TTY probe), delegates to `Commands.run`, exits via
  `ZIOApp.exit(ExitCode)` — 0 success, 1 SnapError, 2 unexpected defect
  (`snap: internal error`, no stack trace).
- Serve: validates repo before any output, serves startup snapshot
  (Json.writeRepository once), prints flushed plain URL line, then blocks on a
  Promise completed by `sun.misc.Signal` TERM/INT handlers installed via
  `acquireReleaseExit` (documented raw-JVM exception; previous handlers
  restored on scope close) → exit 0.
- Added `CommandsSpec` (72 tests, incl. serve-over-HTTP and SIGTERM) and thin
  `MainSpec` (9 tests).

### Cross-module fixes (harness-proven bugs)
- `Codec.checkPrefixConflicts`: only paths present in the authored result
  (Text/Put) can conflict; deletions are excluded. Without this, test 07's
  legal file→directory transition (`delete a` + `create a/b` in one patch) was
  rejected on load as `tree paths conflict`.
- `Main`: set `io.netty.transport.noNative=true` at startup so Netty's native
  probe (System.loadLibrary) never emits JDK restricted-method warnings to
  stderr (tests 12/13 assert empty stderr). NIO loopback is sufficient.

### Gate
- `sbt "compile; test; assembly; scalafmtCheckAll"` — 481 tests, 0 failed.
- `bash harness/snap/run_tests --lang scala --implementation-root $PWD/snap` —
  **28/28 passed** (~88s).

### Rulings recorded
- HOME unset + `config --global` → `snap: cannot write global config: HOME is not set` (exit 1).
- Diff cross-repo: old operand resolved locally, new operand against remote;
  collision check runs before version-lookups on the remote side.
- `serve` prints the URL only after the server is bound; signal handler
  completes a Promise, scope closes, exit 0.
