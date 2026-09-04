# Task F-cmd — Command-layer hygiene (E3-M1, E3-M2/E2-F4, E1-S1 wiring, H2 loops)

**Branch:** `task/13-command-hygiene` · **Worktree:** `/private/tmp/snap-fcmd` · **Base:** main @ 2b49646

## Owned files (modify ONLY these)

- `snap/src/main/scala/snap/Commands.scala`
- `snap/src/main/scala/snap/Main.scala`
- `snap/src/main/scala/snap/Cli.scala`
- `snap/src/main/scala/snap/Render.scala`
- `snap/src/test/scala/snap/CommandsSpec.scala`
- `snap/src/test/scala/snap/MainSpec.scala`
- `snap/src/test/scala/snap/CliSpec.scala`
- `snap/src/test/scala/snap/RenderSpec.scala`

Do NOT touch Model.scala, Json.scala, Codec.scala, Ot.scala, Replay.scala, Diff.scala,
WorkingTree.scala, RepoIo.scala, Config.scala, HttpServe.scala, HttpFetch.scala, SnapError.scala,
or anything under `harness/`. Other lanes already changed some of these and they are merged into your base.

## Findings to fix

### 1. E3-M1 (minor): stderr not explicitly flushed on failure paths
`Commands.Output` exposes only `flushOut`. Error paths (`Commands.run` Left branch, `finish`
failure branch, serve failure) write stderr but never flush it explicitly. Add `flushErr: UIO[Unit]`
to `Output` (live: flush the stderr PrintStream; Captured: `ZIO.unit`), and call it after every
`writeErr` on failure/warning paths so exit-time bytes are deterministic.

### 2. E3-M2 / E2-F4 (minor): SNAP_DEBUG read live from the environment
`Commands.scala` reads `java.lang.System.getenv("SNAP_DEBUG")` inside the defect handler (two sites).
This violates the CmdEnv snapshot policy documented in Main.scala. Add `snapDebug: Boolean` to
`Commands.CmdEnv`, populate it in `Main.cmdEnv` from `getenv("SNAP_DEBUG").isDefined`, update
`MainSpec.cmdEnv` tests, and replace both live reads with `env.snapDebug` (thread `env` into `finish`
and the serve failure path as needed).

### 3. E1-S1 wiring (minor): revision overflow in commit/revert
`Model.nextRevision(current: Long): Either[SnapError, Long]` now exists (added by the F-core lane).
Replace the raw `frontier.get(contributor) + 1` arithmetic in `Commands.commit` (and any other
revision-increment site in Commands.scala) with `Model.nextRevision`, propagating the typed error.
Add CommandsSpec tests constructing a repository whose frontier is at `9007199254740991` for the
contributor: `commit` must fail with the overflow message and exit 1, and must NOT write the repo.

### 4. H2 (human review): idiomatic loop rewrites in owned files
Rewrite the remaining `while` loops in Cli.scala (1 site), Commands.scala (1 site), and Render.scala
(2 sites) using `@tailrec`, `foldLeft`, `zipWithIndex.foreach`, or equivalent — only where behavior
and performance characteristics are preserved. All existing CliSpec/RenderSpec/CommandsSpec goldens
must pass unchanged. If a rewrite hurts clarity/perf, keep the loop with a one-line justification.

## Definition of done

1. Red tests first for items 1–3 (as far as testable): capture failing output in your report.
2. Full gate green in the worktree:
   `source scripts/env.sh && cd snap && sbt --client "compile; test; assembly; scalafmtCheckAll" && sbt --client shutdown`
3. Acceptance harness green from the worktree root:
   `bash harness/snap/run_tests --lang scala --implementation-root $PWD/snap` (expect 28/28).
4. Commit on `task/13-command-hygiene`, author `Snap dev <capstone-dev@local>`, no AI trailers; push to origin.

## Safety rules (strict)

- Work ONLY inside /private/tmp/snap-fcmd (scratch files under /tmp/snap-fcmd-scratch if needed).
- No rm -rf / mv outside your worktree; no destructive git; no harness edits; no new dependencies.
- Kill any process you start (e.g., `snap --serve`).
