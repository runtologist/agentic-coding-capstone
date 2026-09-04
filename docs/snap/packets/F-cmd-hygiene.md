# Task F-cmd: Command-layer hygiene (wave 2 — after wave-1 merges)

**Lane:** F-cmd · **Branch:** `task/13-command-hygiene` (from `main` after wave-1 merges)
**Worktree:** to be created at dispatch time
**Owned files (modify ONLY these):**

- `snap/src/main/scala/snap/Commands.scala`
- `snap/src/main/scala/snap/Cli.scala`
- `snap/src/main/scala/snap/Render.scala`
- `snap/src/test/scala/snap/CommandsSpec.scala`
- `snap/src/test/scala/snap/CliSpec.scala`
- `snap/src/test/scala/snap/RenderSpec.scala`

**Do NOT touch:** Model.scala, Diff.scala, Json.scala, Codec.scala, Ot.scala, Replay.scala,
SnapError.scala, WorkingTree.scala, RepoIo.scala, Config.scala, HttpServe.scala, HttpFetch.scala,
Main.scala (unless a trivial CmdEnv field addition is required for SNAP_DEBUG — coordinate with
the merged wave-1 Main.scala state), anything under `harness/`.

## Context

This is wave 2b of Phase F. Wave 1 (F-utf8, F-diff, F-io-http) and wave 2a (F-core: Model/Json/
Codec/Ot/Replay typed-integers + loop refactor) have already merged into main.
Branch from the current main HEAD at dispatch time. Read the merged state of all files before
starting — especially Model.scala, which may expose new helpers (e.g. `nextRevision`) and a
`PositiveSafeInteger` opaque type.

## Findings to address

### E3-M1 — Flush stderr on error paths (minor)
`Commands.finish` writes the error line to stderr but only calls `flushOut` on the success path.
`Commands.serve` error path similarly. Add `flushErr` to the `Output` trait (and its
implementations: `live` and `Captured`), and call it after every `writeErr` in error/warning
exit paths (`finish`, `serve` failure branch). This is a robustness fix; behavior under the
harness should not change (harness already passes), but it removes a theoretical race where
stderr bytes are lost on abrupt exit.

### E3-M2 / E2-F4 — SNAP_DEBUG via CmdEnv (nit → fix)
`Commands.scala:107` and `:457` read `java.lang.System.getenv("SNAP_DEBUG")` live, bypassing the
CmdEnv snapshot established in Main. Fix: add `snapDebug: Boolean` to `CmdEnv`, populate it in
`Main.cmdEnv` from `getenv("SNAP_DEBUG").isDefined`, and replace the two live reads with
`env.snapDebug`. This is a small, mechanical change.

### E1-S1 wiring — Revision overflow guard in commit/revert (minor)
The wave-2 F-core lane (merged before this one) adds `Model.nextRevision(current: Long):
Either[SnapError, Long]` (or equivalent). Wire it into `Commands.commit` and `Commands.revert` so
that when `frontier.get(contributor) + 1` would exceed 9007199254740991, the command fails with a
typed SnapError (exit 1) instead of silently persisting an unloadable repository. Add CommandsSpec
tests that construct a repository with frontier at max safe integer and assert the error.

### E5-F4 — Exit-2 defect channel test (nit)
Add a CommandsSpec test that forces a defect (e.g., `ZIO.dieMessage("boom")`) through
`Commands.run` and asserts exit code 2 + stderr `snap: internal error\n`. This pins the
exit-2 channel behavior.

### H2 (partial) — while-loops in Cli.scala, Commands.scala, Render.scala
Rewrite remaining `while` loops in these three files to idiomatic Scala (foldLeft, @tailrec,
zipWithIndex.foreach) where behavior is preserved. Currently: Cli.scala:105 (1 loop),
Commands.scala:170 (1 loop), Render.scala:206,213 (2 loops). If a loop is performance-critical
or the rewrite obscures clarity, keep it with a one-line justification comment.

### E3-N1 — Remove guarded cast in Replay.scala
(Only if Replay.scala is NOT owned by a wave-1 lane that already addressed it. Check merged
state first. If already fixed, skip.)

## TDD

For each behavioral change (M1 flush, M2 CmdEnv, S1 overflow, F4 exit-2 test): write the failing
test first, capture red, implement, capture green. For H2 loop rewrites: existing tests must
remain green (behavior-preserving refactor).

## Gates

```bash
source scripts/env.sh && cd snap
sbt --client "compile; test; assembly; scalafmtCheckAll"
sbt --client shutdown
cd ..
bash harness/snap/run_tests --lang scala --implementation-root "$PWD/snap"   # expect 28/28
```

## Safety rules

- Work ONLY inside your assigned worktree. Never rm -rf/mv outside it.
- No destructive git commands. NEVER touch `harness/`.
- Git identity: `Snap dev <capstone-dev@local>`; no Co-Authored-By or AI trailers.
- `sbt --client` only; `sbt --client shutdown` before finishing.

## Finish

Commit on `task/13-command-hygiene`, push to origin, then report (<400 words): lane id, status,
branch, commit SHAs, files changed, tests added/total, gate evidence, findings addressed per ID,
parked items, risks.
