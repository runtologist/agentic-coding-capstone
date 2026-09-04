# Packet L7 — Commands + Main (final integration)

**Branch:** `task/09-commands-main` (from `develop`)
**Wave:** 5 (final implementation lane)
**Depends on:** everything — Model/SnapError (L1), Json (J1), Diff/Ot (L2), Codec/Replay (L3),
WorkingTree/RepoIo/Config (L4), HttpServe/HttpFetch (L5), Cli/Render (L6). All merged and green
(400 unit tests) on develop.

## Goal

Wire every command end-to-end so the **28-case YAML acceptance harness passes**. This is the
integration lane: `Commands.scala` implements each command's orchestration over the frozen lower
layers; `Main.scala` becomes the real process entry point (args → presentation → dispatch →
stdout/stderr → exit code).

## Files owned

Primary deliverables (create/modify exclusively):
- `snap/src/main/scala/snap/Commands.scala` (new)
- `snap/src/main/scala/snap/Main.scala` (replace scaffold stub)
- `snap/src/test/scala/snap/CommandsSpec.scala` (new)
- `snap/src/test/scala/snap/MainSpec.scala` (new, thin)

**Conditional cross-module fix permission:** the harness is the final arbiter. If it exposes a
genuine bug in a lower layer (Model/Json/Codec/Replay/Diff/Ot/WorkingTree/RepoIo/Config/Http*/
Cli/Render), you MAY make the minimal fix there — but every such change MUST keep all existing
unit tests green and MUST be listed in your final report with justification. No feature additions
to other modules; bug fixes only. Never touch build.sbt, docs/, or harness/.

## Safety rules (hard)
- Work ONLY inside your assigned worktree. No `rm -rf`/`mv`/`git clean`/`git reset --hard` outside
  your worktree's `snap/` dir and per-test temp dirs you create. If something looks missing, STOP
  and report. No destructive git. You are an implementation worker: do not run orchestrator
  playbooks, do not edit LEDGER.md, do not touch `develop`/`main`, do not spawn subagents, do not
  set timers/sleeps.

## Read first
- `docs/snap/CONTRACT.md` — §1 grammar, §2 exit codes/streams, §3 plain output (byte-exact),
  §4 terminal formats, §5 presentation selection, §7 validation strings, §8 config, §9 working
  tree, §10 diff rendering, §11 replay/merge behaviors (tests 09/10/11/16/17/18/21/22), §12 HTTP,
  §13 commit/revert mechanics + check orderings, §14 determinism/env, §15 rulings.
- `harness/snap/SPEC.md` §7 (all commands), §8, §9, §10.
- YAML tests under `harness/snap/tests/` — especially 01–14, 19, 20, 24–28.
- All frozen sources under `snap/src/main/scala/snap/` (exact APIs below).

## Frozen APIs you consume (verify signatures in source; do not change them)

```scala
Cli.parse(args: Seq[String]): Either[SnapError, Cli.Command]
// Command: Init(Option[String]) | Config(global: Boolean, id: String) | Status | Log
//        | Commit(message) | Diff(oldRaw: Option[String], newRaw: Option[String], repo: Option[String])
//        | Revert(versionRaw) | Merge(repo) | Serve(port: Model.Port) | ShowVersion

Render.resolvePresentation(colorEnv: Option[String], noColorPresent: Boolean,
    stdoutIsTty: Boolean, stderrIsTty: Boolean): Either[SnapError, (Presentation, Presentation)]
Render.successLine(label: SuccessLabel, version: String, p)   // plain: "<version>\n"
Render.versionLine(p); Render.status(version, rows, p); Render.log(entries, p)
Render.warningLine(w: ReplayWarning, p); Render.errorLine(err: SnapError, p)
Render.serveUrlLine(port: Int)                                 // always plain
Render.diff(entries: Seq[Render.DiffEntry], p)
Render.DiffEntry.Text(path, oldPresent, newPresent, oldTokens, edit) | Binary(path, oldPresent, newPresent)
Render.LogEntry(version: Version, author: String, message: String)
Render.SuccessLabel.{InitializedRepository, Committed, Reverted, Merged}
WorkingTree.scan(root): IO[SnapError, Model.Tree]              // fails UnsupportedEntry, sorted/deterministic
WorkingTree.compare(current, working): Vector[(String, Render.StatusKind)]  // sorted A/M/D rows
WorkingTree.isClean(current, working): Boolean
WorkingTree.materialize(root, target): IO[SnapError, Unit]     // ONLY after clean-check passes
RepoIo.discoverRepo(start: Path): UIO[Option[Path]]            // walk up; None outside any repo
RepoIo.loadRepository(root): IO[SnapError, Repository]         // parse + Codec.validateRepository
RepoIo.writeRepositoryAtomic(root, repo): IO[SnapError, Unit]  // same-dir temp + atomic rename
RepoIo.init(target: Path): IO[SnapError, Unit]                 // reinit/inside-repo refusals included
Config.writeLocal(repoRoot, id) / Config.writeGlobal(home, id) // id pre-validated by caller
Config.resolveContributor(repoRoot: Option[Path], home: Option[Path])
    : IO[SnapError, Option[ContributorId]]                     // local-then-global, no fallback on invalid local
Codec.validateChangesAgainstBase(patch, baseTree): Either[SnapError, Unit]
Codec.checkCollision(local, remote): Either[SnapError, Unit]
Codec.joinedFrontier(localFrontier, incomingPatches): Version
Codec.knownVersion(repo, version): Either[SnapError, Unit]     // UnknownVersion(rendered)
Replay.integrationOrder(patches): Either[SnapError, Vector[Patch]]
Replay.dedupePatches(patches): Either[SnapError, Vector[Patch]]
Replay.materialize(patches, target): Either[SnapError, (Tree, Vector[ReplayWarning])]
Diff.canonicalDiff(oldTokens, newTokens): Vector[EditOp]
HttpServe.serveSnapshot(repo, port): ZIO[Scope, SnapError, Int]  // yields actual bound port
HttpFetch.fetchRepository(url): ZIO[Any, SnapError, Repository]  // one GET, no redirects, full validation
HttpFetch.isHttpUrl(operand): Boolean
Json.writeRepository(repo): String                              // canonical pretty bytes for serve snapshot
Model.{tokenize, detokenize, isText, utf8Bytes, applyEdit, validateCommitMessage,
       escapeLogMessage, utf8Compare, treeEqual, emptyTree, Version.parse/empty/join/render/get,
       ContributorId.parse, Patch(...).result}
```

## Main.scala design

- `object Main extends ZIOAppDefault` with `override val bootstrap = Runtime.removeDefaultLoggers`
  (CRITICAL: the pre-L7 baseline leaked ZIO runtime stack traces to stderr/stdout; the harness
  asserts byte-exact streams).
- Read once at startup, pass as values (CONTRACT §14 determinism seams): args (`getArgs`),
  `SNAP_COLOR`/`NO_COLOR`/`HOME` via `System.getenv` (or `zio.System.env`), cwd via
  `Path.of(".")` absolute-normalized, TTY as ONE snapshot `System.console() != null` used for both
  streams (documented raw-JVM exception; tests fake all four combinations via injected values).
- Resolve presentation FIRST. `InvalidSnapColor` → render the error PLAIN
  (`snap: SNAP_COLOR must be auto, always, or never\n`) to stderr, exit 1, before any command.
- `Cli.parse` failure → `Render.errorLine(err, stderrPresentation)` to stderr, exit 1, empty
  stdout (grammar errors must never create files — parse happens before any I/O).
- Dispatch to `Commands.*`, each returning `ZIO[Any, SnapError, CommandResult]` where
  `CommandResult` carries `(stdout: String, stderr: String)` for immediate commands. `--serve` is
  the exception: it prints the URL line + flush, then blocks until signal (see below).
- Error mapping: any `SnapError` → stderr `Render.errorLine`, exit 1, stdout whatever was already
  flushed (for non-serve commands, nothing is flushed before success, so failures leave stdout
  empty as tests require). Unexpected `Throwable` → stderr `snap: internal error\n`, exit 2,
  never a stack trace.
- Exit via `ZIOApp.exit(ExitCode(n))` computed from the folded result so `run` itself never fails.

### Commands seam (testability)

Define an environment record passed into command functions, e.g.:

```scala
final case class CmdEnv(cwd: Path, home: Option[Path], snapColor: Option[String],
                        noColorPresent: Boolean, isTty: Boolean)
```

`Commands.run(command: Cli.Command, env: CmdEnv): ZIO[Any, SnapError, (String, String, ExitCode-ish)]`
— unit tests construct `CmdEnv` over temp dirs with fake tty/home values. Main is a thin adapter:
build CmdEnv from the real process, call Commands, write bytes to System.out/System.err, exit.
For `--serve`, Commands needs real streaming output; an acceptable design is a small `Output`
capability (writeOut/writeErr/flush) injected with a real implementation in Main and a capturing
one in tests — your choice, but keep every command testable without spawning processes.

## Command behaviors (pinned)

### init [path]
Target = `env.cwd.resolve(path.getOrElse("."))`. `RepoIo.init` → on success stdout
`Render.successLine(InitializedRepository, "()", p)`. Reinit/inside-repo errors from RepoIo.
Existing working files preserved (test 02); intermediates created (`init new/repository`).

### config [--global] contributor.id <id>
Validate id via `Model.ContributorId.parse` BEFORE any write → invalid id error, nothing written
(test 03 last step, test 25 bad-id matrix: `two@@x`, `space @x`, `a,b@x`, `a(b)@x`, `a->b@x`).
- `--global`: needs HOME; HOME absent → error (unpinned; use an IoFailure-style detail, note it).
  Write `$HOME/.snapconfig.json` via `Config.writeGlobal`. No repo needed (test 03 step 1 runs
  before any init).
- local: discover repo first (`NotASnapRepository` if none), then `Config.writeLocal`.
  OVERWRITES existing file dropping unknown fields (test 25: pre-existing `unknown:true` gone).
- Success: empty stdout, empty stderr, exit 0.

### status
Discover repo → load+validate (validation errors surface, stdout empty, exit 1 — tests 15/23/27
drive validation via `status`). Scan working tree (UnsupportedEntry fails read-only commands too,
test 08, exact stderr `snap: unsupported working tree entry: <path>\n`). Current tree =
`Replay.materialize(repo.patches, repo.frontier)` (validated repo ⇒ Right). Rows =
`WorkingTree.compare(current, working)`. Stdout = `Render.status(repo.frontier, rows, p)`.
Empty dirs and `.snap/untracked` invisible (test 25).

### log
Load repo; order = `Replay.integrationOrder(repo.patches).reverse`; entries =
`Render.LogEntry(patch.result, patch.author.value, patch.message)`; stdout `Render.log(...)`.
Empty repo → empty stdout, exit 0. Escaping handled by Render (`\` → `\t` → `\n` order).

### commit <message>
Order: discover/load/validate repo → resolve contributor (None → `ContributorIdRequired`, exact
`contributor.id is required; configure it locally or globally`) → `Model.validateCommitMessage`
(empty/too long/forbidden control chars → `InvalidCommitMessage`, exact
`snap: invalid commit message\n`; TAB and LF allowed, test 04) → scan working tree (unsupported
entry fails first if present) → compare vs current tree; clean → `WorkingTreeClean`.
Build ONE patch over the FULL tree diff, changes sorted by unsigned-UTF-8 path:
- added: `isText(newBytes)` → `Change.Text(path, Diff.canonicalDiff(Vector.empty, tokenize))`
  (empty new file → `edit = []`, ruling F); else `Change.Put(path, bytes)`.
- modified: `isText(new) && isText(old)` → `Text` with `Diff.canonicalDiff(oldTokens, newTokens)`;
  otherwise `Put` (covers text→binary, binary→text, binary→binary).
- deleted: `Change.Del(path)`.
`revision = frontier.get(author) + 1`, `base = frontier`, `result = patch.result`.
Defense: `Codec.validateChangesAgainstBase(patch, currentTree)`.
Insert patch keeping `patches` sorted by (author unsigned-UTF-8, revision) — NOT blind append
(test 27 pins sorted storage; multi-author histories interleave). Commit replaces ONLY
repository.json (working files already in place). Stdout `successLine(Committed, result.render)`.

### diff
Three modes, all read-only (local repo NEVER mutated; test 26 asserts repository.json + tree
unchanged after cross-repo diff):
1. `snap diff` — current tree vs working scan; entries for changed paths.
2. `snap diff <old> <new>` — `Version.parse` each (→ `InvalidVersion`), `Codec.knownVersion`
   (→ `UnknownVersion(rendered)`, test 19 pins `snap: unknown version: (a@x->2)`), materialize
   both, diff the trees.
3. `... --repo <repository>` — old resolves locally, new in the other repository. Operand:
   `HttpFetch.isHttpUrl` → `HttpFetch.fetchRepository(url)` (exactly one GET, no redirects,
   full validation; tests 13/26); else local path resolved against cwd, treated as a repo ROOT
   (`RepoIo.loadRepository(operandPath)`; missing `.snap` → `NotASnapRepository`).
   Cross-repo: `Codec.checkCollision(local.patches, remote.patches)` BEFORE any output
   (test 16: `patch collision: a@x revision 1`). `knownVersion` for old in local repo, new in
   remote repo.
Entry construction per changed path (union of paths, sorted): both sides text →
`DiffEntry.Text(path, oldPresent, newPresent, oldTokens, Diff.canonicalDiff(oldTokens, newTokens))`;
either side non-text (or bytes with NUL) → `DiffEntry.Binary`. Absent side = `/dev/null` header.
No differences → empty stdout, exit 0 (test 05 identical version pair). Render handles hunk
headers, `\ No newline at end of file`, binary lines, terminal coloring.

### revert <version>
Check order (CONTRACT §13, pinned by tests 14/19): discover/load/validate repo → `Version.parse`
(InvalidVersion) → `Codec.knownVersion` (UnknownVersion — BEFORE contributor check, test 14) →
scan working tree + clean check (dirty → `WorkingTreeDirty`; unsupported entry → its error) →
resolve contributor (`ContributorIdRequired`; test 19: HOME null with local config present works,
without local config fails) → materialize target tree → if `treeEqual(target, current)` →
`TargetTreeAlreadyCurrent` → build patch: message `s"revert to ${target.render}"`, changes =
current→target diff using commit's change-selection rules, base = frontier, revision+1 →
`WorkingTree.materialize(root, targetTree)` FIRST (files), then `writeRepositoryAtomic`
(metadata); handles file↔directory transitions (test 07) and target `()` emptying the tree
(test 19). Stdout `successLine(Reverted, newVersion.render)` — the NEW version. Additive only:
never remove patches, never move frontier backward.

### merge <repository>
No contributor required. Order per CONTRACT §15 ruling 8 (all pre-write steps are non-mutating,
satisfying test 20's no-mutation asserts):
1. discover/load/validate LOCAL repo.
2. load remote: `HttpFetch.isHttpUrl(operand)` → `fetchRepository`, else `RepoIo.loadRepository`
   on the operand path (repo root). Full validation applies (test 26: unknown field on remote →
   error, zero mutation, exactly one GET per HTTP attempt).
3. `Codec.checkCollision(local.patches, remote.patches)` (test 16: fails before mutation).
4. union = `Replay.dedupePatches(local.patches ++ remote.patches)` (structurally-equal duplicates
   collapse, test 26); joined = `Codec.joinedFrontier(local.frontier, remote.patches)`.
5. `(joinedTree, joinedWarnings) = Replay.materialize(union, joined)`;
   `(localTree, localWarnings) = Replay.materialize(local.patches, local.frontier)`.
6. dirty/unsupported check: scan working tree; unsupported entry → its error; not clean vs
   localTree → `WorkingTreeDirty` (test 20; nothing imported).
7. No-op case (union == local patch set and joined == local frontier): print
   `successLine(Merged, joined.render)`, empty stderr, NO writes (re-merge, tests 09/10).
8. Otherwise: `WorkingTree.materialize(root, joinedTree)` → `writeRepositoryAtomic` with
   patches sorted by (author, revision) → warnings = joinedWarnings minus localWarnings compared as
   `(path, reason)` pairs (already sorted by `ReplayWarning.byPathThenReason`) → stderr
   `Render.warningLine` for each → stdout `successLine(Merged, joined.render)`.
Goldens: test 09 (line OT, `base\nright\nleft\n`, zero warnings, both directions converge,
re-merge no-op), test 10 (delete-wins / put-wins / later-put-wins exact stderr order + final
bytes), test 11 (namespace-wins both directions), test 17 (later-create-wins both directions,
winner = canonically later author), test 13 (HTTP merge prints `(remote@x->1)\n`, materializes
`file.txt` = `remote\n`), test 21 (joined frontier `(a@x->2,b@x->2)`, final `base\nB1\nB2\nA2\n`).

### --serve [port]
Port already typed by Cli (`Port.default` = 8765 when absent; invalid rejected at parse).
Discover/load/validate repo BEFORE anything is printed (invalid repo → exit 1, empty stdout,
`snap: <detail>` on stderr — test 12 final step). Snapshot body = `Json.writeRepository(repo)`
taken once; later commits must not change served bytes. In a `ZIO.scoped`:
`HttpServe.serveSnapshot` yields the actual bound port → print `Render.serveUrlLine(boundPort)`
and FLUSH stdout (always plain, even under `SNAP_COLOR=always`, ruling I) → install
`sun.misc.Signal` handlers for `TERM` and `INT` (documented raw-JVM exception) that complete a
`Promise` → await it → scope closes (graceful server shutdown) → exit 0 with stdout exactly the
URL line and empty stderr (test 12; harness kills the process group). Handlers print nothing.

### --version
Stdout `Render.versionLine(p)` (plain: `snap 1.0.0\n`). No repo discovery, works anywhere.

## Stream/exit discipline (global)
- Results → stdout; warnings + errors → stderr (CONTRACT §2). Every failing command: exit 1,
  empty stdout, exactly one `snap: <detail>\n` line on stderr unless a test pins more.
- NO log noise anywhere: no ZIO default logging (bootstrap), no zio-http/Netty startup banners to
  stdout/stderr (spike: verify `Server.serve`/`install` emits nothing; silence via config or
  slf4j-nop if needed — if adding a dependency is required, STOP and report instead).
- UTF-8 output, LF endings. stdin never read.

## Tests (TDD; red first)

`CommandsSpec.scala` (≥45 tests) exercising commands through `Commands.run` over temp dirs:
- init: happy, reinit, inside-repo, intermediates, preserves files, exact `()` output.
- config: local/global writes exact canonical JSON bytes; invalid-id matrix from test 25;
  precedence (local wins while global malformed); invalid local blocks fallback; trailing-garbage
  global accepted; HOME absent → global unavailable.
- status: clean, A/M/D rows sorted (unicode order), unsupported entry exact error, invalid repo
  errors with empty stdout.
- log: reverse canonical order, tab-separated fields, escaping order pinned by test 04.
- commit: happy path stores exact patch shape (sorted changes, correct change variant per
  text/binary/empty rules), version bump, clean-tree error, missing-contributor error, invalid
  messages, multi-author sorted patch storage.
- diff: working-tree mode golden (test 05 shapes), version-pair both directions (test 21),
  unknown/invalid versions, cross-repo local, collision error (test 16), read-only guarantee.
- revert: full test-07 scenario incl. file↔dir, `()` target, already-current, dirty, check order
  (unknown version before missing contributor — test 14).
- merge: tests 09/10/11/17 scenarios at command level incl. exact warning lines and no-op
  re-merge; dirty/unsupported refusal with zero mutation (test 20); HTTP remote via an in-test
  served snapshot; malformed remote no-mutation.
`MainSpec.scala` (thin): arg vector → exit code/stream table for `--version`, invalid SNAP_COLOR,
grammar errors, not-a-repo; presentation resolution wiring with fake tty flags.

## Gates & acceptance (the real definition of done)

1. Unit gate: `sbt --client shutdown` (ignore failure); then
   `source scripts/env.sh && cd snap && sbt --client "compile; test; assembly; scalafmtCheckAll"`
   — all green (400 pre-existing + your new tests), then `sbt --client shutdown`.
2. **Acceptance harness** (the goal): from the worktree root,
   `source scripts/env.sh && bash harness/snap/run_tests --lang scala --implementation-root "$PWD/snap"`.
   Target **28/28 passing**. Iterate on failures; use your conditional cross-module fix permission
   when a failure roots in a lower layer (report each). If a test appears to conflict with
   CONTRACT.md, CONTRACT wins over your interpretation — re-read §15 rulings before assuming the
   harness is wrong; if still stuck, report the specific case and stop rather than hacking the
   harness (NEVER modify harness files).
3. Commit on `task/09-commands-main` (identity preconfigured; NEVER add Co-Authored-By trailers),
   `git push -u origin task/09-commands-main`. Never push develop/main.
4. Final report: commit SHA; harness result line (X/28); files changed incl. any cross-module
   fixes with justification; unit test counts; rulings on unpinned choices (HOME-absent global
   write error text, any others); deviations; remaining risks.
