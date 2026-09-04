# Packet L4 — WorkingTree + RepoIo + Config (I/O edge layer)

**Branch:** `task/07-tree-io-config` (from `develop`)
**Wave:** 4 (parallel with L5)
**Depends on:** Model/SnapError (L1), Json thin codecs (J1), Codec+Replay (L3) — all merged on develop.

## Goal

Implement the filesystem/config I/O edge as ZIO effects that map every failure
into the sealed `SnapError` ADT. No raw exceptions may escape these modules.
Pure logic stays in Model/Codec/Replay; these modules only touch disk/env.

## Files owned (exclusive)
- `snap/src/main/scala/snap/WorkingTree.scala` (new)
- `snap/src/main/scala/snap/RepoIo.scala` (new)
- `snap/src/main/scala/snap/Config.scala` (new)
- `snap/src/test/scala/snap/WorkingTreeSpec.scala` (new)
- `snap/src/test/scala/snap/RepoIoSpec.scala` (new)
- `snap/src/test/scala/snap/ConfigSpec.scala` (new)

**Do NOT modify:** Model.scala, SnapError.scala, Json.scala, Codec.scala,
Replay.scala, Diff.scala, Ot.scala, Cli.scala, Render.scala, Main.scala,
build.sbt, docs, harness. If a change is needed there, STOP and report.
**Do NOT modify or push to** develop/main or other task branches.

## Safety rules (hard)
- Work ONLY inside your assigned worktree. All test fixtures must live under
  per-test temp directories (`java.nio.file.Files.createTempDirectory` inside
  `ZIO.acquireRelease`), cleaned up after each test. NEVER delete or move
  anything outside a temp dir you created. No `rm -rf` with absolute paths,
  no `git clean`, no destructive git commands.
- `sbt --client`; `sbt --client shutdown` after the final gate. No interactive sbt.

## Read first
- `docs/snap/CONTRACT.md` §8 (configuration), §9 (working tree semantics),
  §13 (commit/revert mechanics — for how L7 will call you), §14 (determinism),
  §15 (rulings)
- `harness/snap/SPEC.md` §2 (repo & working tree), §7.1, §7.2, §8, §10
- `harness/snap/tests/01-init.yaml`, `02-init-paths.yaml`,
  `03-configuration.yaml`, `04-commit-status-log.yaml`,
  `06-binary-and-empty.yaml`, `07-revert.yaml`, `08-unsupported-entries.yaml`,
  `19-version-boundaries.yaml`, `20-dirty-merge.yaml`,
  `25-config-version-path-boundaries.yaml`, `26-portability-and-failure-safety.yaml`
- Frozen APIs: `Model.Tree`/`sortedPaths`/`treeEqual`/`utf8Compare`,
  `Json.parseRepository`/`parseConfig`/`writeRepository`/`writeConfig`,
  `Codec.validateRepository`, `SnapError` cases (`UnsupportedEntry`,
  `NotASnapRepository`, `RepositoryAlreadyExists`,
  `CannotInitializeInsideRepository`, `InvalidConfigJson`,
  `InvalidContributorId`, `ContributorIdRequired`, `IoFailure`)

---

## Part A — `WorkingTree.scala`

All effects: `ZIO.attemptBlocking` (or zio-nio if you prefer, but no new deps —
build.sbt is frozen), errors mapped to `SnapError`.

```scala
object WorkingTree {
  /** Recursively scan repo root; skip `.snap/` entirely. Only regular files
    * are tracked; bytes read as-is (arbitrary binary). Any symlink, FIFO, or
    * non-regular entry fails the whole scan with UnsupportedEntry(relPath),
    * relPath using `/` separators relative to root. Failing entry is reported
    * deterministically: scan in sorted (unsigned-UTF-8) order and fail on the
    * first unsupported entry found. Empty directories are ignored (not
    * tracked, not errors). */
  def scan(root: java.nio.file.Path): Task-style IO[SnapError, Model.Tree]

  /** Sorted (path, code) rows comparing current tree vs working tree:
    * A = absent→present, M = bytes changed, D = present→absent.
    * Pure — takes two Trees. Order: Model.utf8Compare on paths
    * (test 25: nested/file < z < é < 😀). */
  def compare(current: Model.Tree, working: Model.Tree): Vector[(String, Char)] // or enum Status

  def isClean(current: Model.Tree, working: Model.Tree): Boolean

  /** Make the on-disk tree under `root` exactly equal `target`
    * (used by revert/merge; commit never calls this). Steps:
    * 1. delete tracked files absent from target;
    * 2. remove any file/dir that blocks a required target path
    *    (e.g. file→dir and dir→file transitions, test 07);
    * 3. create parent dirs and write target files (exact bytes);
    * 4. prune directories that became empty as a result of this
    *    materialization. Do NOT delete pre-existing empty dirs unrelated
    *    to the change set (test 25 keeps `empty`, `deep/empty`). */
  def materialize(root: Path, target: Model.Tree): IO[SnapError, Unit]
}
```

Pinned behaviors:
- Unsupported entry stderr is EXACTLY `snap: unsupported working tree entry: <path>\n`
  (tests 08: `link`; 20: `link`) — so `UnsupportedEntry.detail` must render the
  bare relative path; applies to read-only scans (status/diff) too, and no
  mutation may occur before the failure.
- `.snap/` contents never appear in scans or status (test 25: `.snap/untracked`
  invisible).
- File contents are raw bytes; CRLF/NUL/unicode preserved byte-exactly (test 26).

## Part B — `RepoIo.scala`

```scala
object RepoIo {
  /** Walk from `start` up to filesystem root; return the first directory
    * containing `.snap/` (a directory). Works from nested cwds
    * (test 19: repo/sub/deep finds repo). None outside any repo. */
  def discoverRepo(start: Path): UIO[Option[Path]]

  /** Read `<root>/.snap/repository.json`, Json.parseRepository (strict),
    * then Codec.validateRepository. Missing/unreadable file → NotASnapRepository
    * or IoFailure as appropriate. Pure validation — never mutates on failure
    * (tests 15, 20, 23, 26). */
  def loadRepository(root: Path): IO[SnapError, Model.Repository]

  /** Serialize with Json.writeRepository (canonical pretty bytes, trailing LF)
    * and atomically replace: write temp file IN `.snap/` (same directory),
    * then Files.move ATOMIC_MOVE over repository.json (SPEC §10). */
  def writeRepositoryAtomic(root: Path, repo: Model.Repository): IO[SnapError, Unit]

  /** `snap init [path]` core logic (called by L7 after Cli parse):
    * - create target dir + intermediates if absent (`init new/repository`, test 02);
    * - if target already has `.snap` → RepositoryAlreadyExists;
    * - if target is INSIDE an existing repo (any strict ancestor has `.snap`)
    *   → CannotInitializeInsideRepository and create NOTHING (test 02:
    *   repo/child/.snap must not exist after failure);
    * - existing working files are preserved untouched;
    * - write empty repository: {"format":1,"frontier":[],"patches":[]} via
    *   Json.writeRepository(Repository(Version.empty, Vector.empty)). */
  def init(target: Path): IO[SnapError, Unit]
}
```

## Part C — `Config.scala`

```scala
object Config {
  /** Write `.snap/config.json` via Json.writeConfig — OVERWRITES the file and
    * drops unknown fields (test 25: {"contributor":{"id":"old@x"},"unknown":true}
    * becomes exactly {"contributor":{"id":"new@x"}}). Do NOT parse the
    * pre-existing file before writing (J1 note). */
  def writeLocal(repoRoot: Path, id: Model.ContributorId): IO[SnapError, Unit]

  /** Write `$HOME/.snapconfig.json` via Json.writeConfig. */
  def writeGlobal(home: Path, id: Model.ContributorId): IO[SnapError, Unit]

  /** Resolve contributor id, SPEC §8 + CONTRACT §8:
    * 1. Local `<repoRoot>/.snap/config.json` read first. Missing file → fall
    *    through to global. Present: Json.parseConfig (tolerates trailing bytes
    *    after first JSON value; duplicate keys → DuplicateJsonKey; malformed →
    *    InvalidConfigJson(path) containing "invalid JSON"; invalid id →
    *    InvalidContributorId). ANY local parse/validation failure is fatal —
    *    NO fallback to global (test 25: local `not-an-id` + valid global →
    *    `snap: invalid contributor id: ...`).
    * 2. Global `$HOME/.snapconfig.json` (home passed as Option — None when
    *    $HOME absent → global unavailable, NOT an error, test 19 HOME:null).
    *    Missing file → no value. Malformed → InvalidConfigJson. Trailing bytes
    *    tolerated (test 03: `{"contributor":{...}}}}` parses fine).
    * 3. Neither provides an id → Right(None); the calling command (commit/
    *    revert) decides when to fail with ContributorIdRequired. */
  def resolveContributor(repoRoot: Option[Path], home: Option[Path])
      : IO[SnapError, Option[Model.ContributorId]]
}
```

Note: `snap config contributor.id <id>` (the write command) validates the id
via `Model.ContributorId.parse` BEFORE writing (test 03: `bad-id` →
`invalid contributor id`, nothing written). That orchestration is L7's job —
just ensure `ContributorId.parse` is the validation seam and write effects
take an already-validated id.

## Part D — Tests (TDD, red first; zio-test)

Use per-test temp dirs. ≥40 tests across the three specs, including:

WorkingTreeSpec:
- scan of nested tree returns sorted paths with exact bytes (unicode order:
  `nested/file` < `z` < `é` < `😀` per test 25)
- `.snap/` excluded from scan even with files inside
- symlink → `UnsupportedEntry("link")` exact detail; FIFO → `UnsupportedEntry("pipe")`
  (create FIFO via `mkfifo` in the temp dir using a process; if the platform
  cannot, test a second non-regular entry type and note it)
- empty dirs invisible (scan ignores them)
- compare: A/M/D classification, sorted rows, identical trees → empty
- materialize: add/modify/delete round-trip; file→dir transition
  (test 07: delete `node/child`, create file `node`); dir→file and file→dir;
  prunes newly-empty dirs but preserves unrelated pre-existing empty dirs;
  byte-exact for binary content (test 06: `AP+AQUI=` round-trip)

RepoIoSpec:
- discoverRepo from nested cwd (test 19), None outside
- init golden: creates `.snap/repository.json` with EXACT canonical bytes
  `{...pretty 2-space + trailing LF...}` equal to Json.writeRepository of the
  empty repository (test 01 json_equals)
- init creates intermediate dirs (`new/repository`, test 02)
- re-init → RepositoryAlreadyExists; init inside repo →
  CannotInitializeInsideRepository AND no `.snap` created in child (test 02)
- init preserves existing files (test 02)
- loadRepository: valid file parses+validates; malformed JSON → error;
  semantically invalid repo (e.g. cycle) → Codec error propagated; no files
  mutated on failure
- writeRepositoryAtomic: bytes exactly Json.writeRepository output; no temp
  file left behind after success

ConfigSpec:
- writeLocal/writeGlobal produce exactly `{"contributor":{"id":"<id>"}}`
  pretty bytes; unknown fields from a pre-existing file are dropped (test 25)
- resolveContributor: local wins over global (test 03: global is `not json`
  yet local id used); missing local falls to global; invalid local id →
  InvalidContributorId with NO global fallback (test 25); duplicate key in
  local config → DuplicateJsonKey (test 25); malformed global → error
  containing `invalid JSON` (test 03); trailing-garbage global accepted
  (test 03); home=None + no local → Right(None); both missing → Right(None)

## Definition of done

1. Gates: `sbt --client shutdown` (ignore failure), then
   `source scripts/env.sh && cd snap && sbt --client "compile; test; assembly; scalafmtCheckAll"`
   all green (333 pre-existing tests untouched + your new ≥40), then
   `sbt --client shutdown`.
2. Only the six owned files created/modified.
3. Commit on `task/07-tree-io-config`; `git push -u origin task/07-tree-io-config`.
   Identity preconfigured; NEVER add Co-Authored-By trailers. If push fails on
   SSH agent, report it so the integrator can push.
4. Report: files + line counts, test counts, gate tails, rulings on any
   spec-silent choice, deviations, and integration notes for L7 (Commands/Main)
   — especially the exact call sequence for init/config/status/commit/revert/merge.
