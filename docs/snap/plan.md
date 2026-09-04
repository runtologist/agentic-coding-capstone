# Snap — Work Plan

Layered implementation plan for the Snap content-tracked file tree tool (Scala 3 + ZIO).

## Layer Status

| Layer | Scope | Status | Completed |
|---|---|---|---|
| L1 | Core model: versions, contributors, trees, edits, patches | done | 2026-07-03 |
| L2 | Codec: canonical JSON parse/write | done | 2026-07-03 |
| L3 | Replay: canonical merge, OT transform, validation | done | 2026-07-03 |
| L4 | Working tree scan, repo I/O, config | done | 2026-07-03 |
| L5 | HTTP serve + fetch | done | 2026-07-03 |
| L6 | Rendering: plain + terminal, exact bytes | done | 2026-07-03 |
| L7 | Commands + main + E2E | done | 2026-07-03 |

## Risks & Open Questions

- Resolved (L7): the empty-content working-tree diff case (`text == ""`) must render as a zero-token text hunk `@@ -1,0 +1,0 @@`, not a binary marker. Fixed in `Commands.diffEntries` by treating absent-or-text sides as text.
- Resolved (L7): `Codec.checkPrefixConflicts` originally flagged a legitimate file↔directory transition (`delete a` + `create a/b`) as a prefix conflict, breaking test 07's revert path. Fixed by only prefix-checking paths that remain present after the patch (text/put changes); duplicate same-path changes are still caught by the sorted-unique check.
- Resolved (L7): Netty's native-transport probe (`System.loadLibrary`) emits JDK 25 restricted-method warnings to stderr, corrupting byte-exact assertions. Fixed by setting `io.netty.transport.noNative=true` in `Main` before any zio-http code loads; NIO transport is sufficient for the loopback snapshot server.
- Resolved (L7): HOME-unset global config write uses `snap: cannot write global config: HOME is not set` (not pinned by harness; noted as an unpinned ruling).
- `sun.misc.Signal` is the one documented raw-JVM exception (SPEC §14); handlers are installed via ZIO `acquireReleaseExit` and restored on scope close.

## Progress Log

- 2026-07-03T14:10Z — L1 complete: 118 unit tests.
- 2026-07-03T14:35Z — L2 complete: 184 tests.
- 2026-07-03T14:50Z — L3 complete: 261 tests.
- 2026-07-03T15:00Z — L4 complete: 318 tests.
- 2026-07-03T15:05Z — L5 complete: 339 tests.
- 2026-07-03T15:10Z — L6 complete: 400 tests, harness blocked on missing CLI.
- 2026-07-03T15:16Z — L7 packet dispatched.
- 2026-07-03T15:35Z — L7 complete: Commands.scala (init/config/status/log/commit/diff/revert/merge/serve/version orchestration), Main.scala entry point with `Runtime.removeDefaultLoggers`, exact exit codes (0/1/2), UTF-8 output, snapshot-once serve with TERM/INT shutdown; 81 new unit tests (481 total green); full acceptance harness 28/28.
