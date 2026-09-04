# Snap — Derived Architecture (Scala 3 + ZIO)

Derived per `GENERIC_CAPSTONE_PLAN.md` §2 from `docs/snap/CONTRACT.md`,
`harness/snap/SPEC.md`, and all 28 public YAML cases.

Revision 2026-09-05 (post human review):
- JSON parsing uses **zio-json** (AST decode with duplicate-key detection;
  exact integer-literal checks; trailing-content tolerance for config files
  only), not a hand-rolled parser.
- HTTP client and server use **zio-http**, not JDK built-ins.
- `Port` is a typed validated value (integer 0..65535), not a raw string/int.
- Repository-validation failures are modeled as individual `SnapError` cases
  with typed fields instead of one free-string `RepositoryInvalid`.
- `Replay.Warning` is a sealed ADT, not a `(String, String)` pair.
- `Version.join` is total over valid versions, so it returns `Version`
  directly rather than `Either` (see note below).
- Exit-code and console-output behavior is integration-tested by the supplied
  YAML harness (every case asserts exit code + exact stdout/stderr at process
  level), plus `CliSpec`/`RenderSpec` unit tests.

## Delivery shape

A **process-level CLI** (`snap`): every invocation is a fresh JVM process that
reads `.snap/repository.json` + the working tree, performs one command, prints
byte-exact output, and exits 0/1/2. One long-running mode (`--serve`) exposes
an immutable HTTP snapshot and must exit 0 on SIGTERM/SIGINT. There is no
persistent daemon, no stdin reading, and no wall-clock dependence anywhere —
all behavior is determined by repository contents, working-tree bytes, and the
`SNAP_COLOR`/`NO_COLOR`/`HOME` environment.

Consequences:

- Pure core (versions, diff, OT, replay, validation, rendering) is directly
  unit-testable and carries all the hard semantics.
- The I/O edge is thin: filesystem walk/materialize, JSON file read/write,
  one-shot HTTP GET (zio-http client), one-resource HTTP server (zio-http),
  signal handling.
- Byte-exactness matters more than abstraction elegance: renderers produce the
  final bytes, tested with golden strings.

## Module map

All sources in package `snap` under `snap/src/main/scala/snap/`.
Tests in `snap/src/test/scala/snap/` (zio-test).

| Module | File(s) | Responsibility | Purity |
|---|---|---|---|
| Errors | `SnapError.scala` | Sealed error ADT; one case per distinct failure with typed fields; every expected failure renders its exact `snap: <detail>` message; internal failures separate (exit 2) | pure |
| Json | `Json.scala` | zio-json-based parsing to `zio.json.ast.Json` AST with duplicate-key detection, exact integer-literal validation, trailing-tolerance flag for config files; canonical 2-space pretty writer matching `JSON.stringify(v,null,2)+"\n"` byte-for-byte | pure |
| Model | `Model.scala` | `ContributorId`, `Revision`, `Port`, `Version` (parse/render/compare/join/snapOrder), `EditOp`, `Change` (Text/Put/Delete), `Patch` (dot, result), `Repository`, `Tree` (`SortedMap[String, Array[Byte]]` with UTF-8 byte-order keys), text/token classification | pure |
| Codec | `Codec.scala` | JSON ↔ Model for repository/config with full §4.5 schema validation: unknown fields, canonical base64, safe integers, message rules, edit-op shape, path validity, sorting, closure, contiguity, cycles, change-vs-base checks, replay validation | pure |
| Diff | `Diff.scala` | §5 canonical token diff (DP, delete-on-tie, coalesce) → `Vector[EditOp]`; edit-script application; token canonicality checks | pure |
| Ot | `Ot.scala` | §6.3 transform(P, Q) with count splitting, Q-insert priority, trailing inserts, coalesce | pure |
| Replay | `Replay.scala` | §6.1 canonical integration order; §6.2 patch integration (namespace resolution, per-path rules 1–4); §6.4 winner rules + `ReplayWarning` ADT; `materialize(patches, version): (Tree, Vector[ReplayWarning])` | pure |
| WorkingTree | `WorkingTree.scala` | Walk repo dir (skip `.snap`), reject symlinks/FIFOs/unsupported entries with exact path, read file bytes; `materialize(dir, tree)` (remove stale, create dirs, write files, prune empty dirs); dirty comparison vs current tree | I/O edge |
| RepoIo | `RepoIo.scala` | Nearest-repo discovery (walk to root), read/validate `repository.json`, atomic replace via same-dir temp + rename, init logic (create dirs, `.snap/`), local config read/write | I/O edge |
| Config | `Config.scala` | Local-then-global resolution, lenient-trailing config parse, ID validation, `$HOME` handling, exact `contributor.id is required...` failure | I/O edge |
| HttpFetch | `HttpFetch.scala` | One GET of exact URL via **zio-http** client (redirects NEVER followed), status≠200 → `HTTP <status>` error, body → strict JSON → validated repository | I/O edge |
| HttpServe | `HttpServe.scala` | **zio-http** server bound to `127.0.0.1`; GET/HEAD `/repository.json` (exact target match, HEAD zero-body), 404 incl. query strings, 405 + `Allow: GET, HEAD`, snapshot bytes served immutably; SIGTERM/SIGINT → graceful completion → exit 0 | I/O edge |
| Render | `Render.scala` | Plain + terminal rendering for every output family: success lines, status, log, diff blocks, warnings, errors, version banner; `Presentation` value (`Plain`/`Terminal`) resolved once per stream | pure |
| Cli | `Cli.scala` | Argument grammar → `Command` ADT (includes `Serve(port: Port)`); exact `invalid command or arguments` vs `usage: snap diff …` distinction; `Port.parse` (Int, 0..65535); option position/duplication rules | pure |
| Commands | `Commands.scala` | Per-command orchestration (init/config/status/log/commit/diff/revert/merge/serve) wiring pure core to I/O edge in ZIO | ZIO effects |
| Main | `Main.scala` | `ZIOAppDefault`; `SNAP_COLOR`/`NO_COLOR`/TTY resolution (before arg parsing), dispatch, output via `zio.Console`, exit code via `ZIOApp#exit`; suppresses ZIO default error reporter for expected `SnapError`s | ZIO edge |

## Error ADT (frozen shape)

Every distinct failure mode is its own ADT case with typed fields; the pinned
test substring is derived from those fields at render time (one place per
message). Only genuinely free-form context (underlying OS/network exception
text) stays a raw string (`IoFailure`).

```scala
sealed abstract class SnapError(val detail: String) extends RuntimeException(detail)
object SnapError:
  // CLI / grammar
  case object InvalidCommandOrArguments            // "invalid command or arguments"
  final case class DiffUsage(usage: String)        // "usage: snap diff <usage>"
  final case class InvalidPort(input: String)      // "invalid port: <input>"
  case object InvalidSnapColor                     // "SNAP_COLOR must be auto, always, or never"
  // repository discovery / init
  case object NotASnapRepository                   // "not a Snap repository"
  case object RepositoryAlreadyExists              // "repository already exists"
  case object CannotInitializeInsideRepository     // "cannot initialize inside repository"
  // configuration
  final case class InvalidContributorId(reason: String) // "invalid contributor id: <reason>"
  final case class InvalidConfigJson(path: String) // "invalid JSON in <path>"
  final case class DuplicateJsonKey(name: String)  // "duplicate JSON key <name>"
  case object ContributorIdRequired                // "contributor.id is required; configure it locally or globally"
  // versions
  final case class InvalidVersion(reason: String)  // "invalid version: <reason>"
  final case class UnknownVersion(rendered: String)// "unknown version: <rendered>"
  // working tree / commit / revert
  case object WorkingTreeClean                     // "working tree is clean"
  case object WorkingTreeDirty                     // "working tree is dirty"
  final case class UnsupportedEntry(path: String)  // "unsupported working tree entry: <path>"
  case object InvalidCommitMessage                 // "invalid commit message"
  case object TargetTreeAlreadyCurrent             // "target tree is already current"
  // repository / patch validation — one case per distinct failure
  final case class UnknownRepoField(field: String) // "repository has unknown field: <field>"
  final case class UnknownPatchField(field: String, author: String, revision: Long)
  final case class UnknownChangeField(field: String, author: String, revision: Long)
  final case class MissingPatch(author: String, revision: Long) // "missing <author> revision <n>"
  final case class UnreachablePatch(author: String, revision: Long)
  case object CyclicOrIncompleteHistory            // "cyclic or incomplete patch history"
  final case class PatchCollision(author: String, revision: Long) // "patch collision: <author> revision <n>"
  final case class InvalidRepoPath(path: String, reason: String)  // "path is invalid: ..."
  final case class NonCanonicalBase64(path: String)               // "...canonical base64..."
  final case class NotPositiveSafeInteger(context: String)        // "...positive safe integer..."
  final case class EmptyField(scope: String, field: String)       // "...message is empty" / "changes is empty" / "insert is empty"
  case object EditOpWrongArity                     // "...must have one operation..."
  final case class AdjacentSameKindOps(kind: String) // "adjacent <kind> operations..."
  final case class EditNotConsuming(path: String)    // "...does not consume old content..."
  final case class EditOverconsumes(path: String)    // "...consumes beyond old content..."
  final case class NonCanonicalTokens(path: String)  // non-canonical insert/result tokens
  final case class TreePathsConflict(path: String)   // "tree paths conflict: <path>"
  final case class NoOpChange(path: String)          // "no-op change: <path>"
  final case class DeleteOfAbsentPath(path: String)  // "delete of absent path: <path>"
  final case class CreateOfPresentPath(path: String) // "create of present path: <path>"
  final case class TextOverBinaryBase(path: String)  // text change over non-text base
  final case class NonCanonicalFrontier(found: String, expected: String)
  // HTTP / IO / internal
  final case class HttpStatus(status: Int, url: String) // "HTTP <status> ..."
  final case class IoFailure(detail: String)            // filesystem/network failures
  case object InternalError                        // exit 2
```

All `SnapError`s → exit 1, rendered `snap: <detail>` (terminal-mode wrapping in
`Render`). Anything thrown/defective → exit 2. Exact pinned substrings live in
CONTRACT.md §7; each case's `detail` must satisfy the strongest pinned form.

## Key domain types (frozen signatures)

```scala
opaque type ContributorId <: String   // validated: one '@', non-empty sides, no controls/whitespace/comma/parens/"->", ≤254 bytes
opaque type Revision <: Long          // validated positive, ≤ 9007199254740991 at every parse site (CLI, JSON)
opaque type Port <: Int               // validated 0..=65535 at CLI boundary; 0 = OS-assigned

enum CausalOrder { Equal, Before, After, Concurrent }  // all four preserved

final case class Version(components: Vector[(ContributorId, Revision)])  // sorted, unique
object Version:
  def parse(s: String): Either[SnapError, Version]          // canonical-form CLI syntax only
  def render(v: Version): String                            // "()" | "(a@x->1,b@y->2)"
  def compare(a: Version, b: Version): CausalOrder
  def join(a: Version, b: Version): Version                 // total — see note below
  def snapOrder(a: Version, b: Version): Int                // §3.4 total order extending causal
  def knownIn(v: Version, frontier: Version): Boolean       // all dots ≤ v exist

sealed trait EditOp                       // Retain(n) | Delete(n) | Insert(tokens)
sealed trait Change { def path: String }  // Text(path, edit) | Put(path, bytes) | Delete(path)

final case class Patch(author: ContributorId, revision: Revision,
                       base: Version, message: String, changes: Vector[Change]):
  def dot: (ContributorId, Revision)
  def result: Version

final case class Repository(frontier: Version, patches: Vector[Patch])

type Tree = Map[String, Array[Byte]]      // iterated in unsigned-UTF-8 path order

object Replay:
  enum ReplayWarning(val path: String, val reason: String): // renders "auto-resolved <path>: <reason>"
    case DeleteWins(path: String)       extends ReplayWarning(path, "delete-wins")
    case LaterCreateWins(path: String)  extends ReplayWarning(path, "later-create-wins")
    case LaterPutWins(path: String)     extends ReplayWarning(path, "later-put-wins")
    case NamespaceWins(path: String)    extends ReplayWarning(path, "namespace-wins")
    case PutWins(path: String)          extends ReplayWarning(path, "put-wins")

  def materialize(patches: Vector[Patch], target: Version)
      : Either[SnapError, (Tree, Vector[ReplayWarning])]
  def integrationOrder(patches: Vector[Patch]): Either[SnapError, Vector[Patch]]
```

`Version.join` always succeeds for valid inputs: the componentwise max over the
union of two sorted, unique-ID, positive-revision vectors is itself a valid
version, so it returns `Version`, not `Either`. Invalid versions are rejected
at parse/validation boundaries and never reach `join`.

`Revision` positivity and range are validated at every parse site (CLI version
arguments and JSON decoding), never assumed downstream.

## Determinism seams

| Concern | Seam | Rule |
|---|---|---|
| Wall-clock | none needed | Snap has no time-dependent behavior; never read a clock |
| Environment | `zio.System.env` in `Main` only | `SNAP_COLOR`, `NO_COLOR`, `HOME` read once, passed as values |
| TTY detection | `Tty` capability passed into presentation resolution | Live impl: `System.console() != null` snapshot (documented raw-JVM exception per memory; no per-stream isatty on JVM) — unit tests fake all four TTY combinations |
| Filesystem | `WorkingTree`/`RepoIo` via `ZIO.attemptBlocking`, mapped to `SnapError` | all reads/writes confined to repo root |
| Network | `HttpFetch`/`HttpServe` in ZIO effects (zio-http) | no redirects; one GET; loopback-only server |
| Signals | `sun.misc.Signal` handlers installed only for `--serve` (documented raw-JVM exception) | SIGTERM/SIGINT → graceful completion → `exit(ExitCode.success)` |
| Ordering | unsigned UTF-8 byte comparison for all paths/IDs (`Model.utf8Compare`) | no locale/`String.compareTo` for spec orderings |

## Library decisions (with rationale)

- **ZIO 2.1.26** runtime, **zio-json** for JSON parsing, **zio-http** for the
  HTTP client and server (per user direction); zio-test for unit tests. All
  side effects live in ZIO effects and map into the sealed error ADT (memory
  `idiomatic-zio-patterns`).
- **JSON via zio-json**: decode into `zio.json.ast.Json` so we keep control of
  duplicate-key detection, integer-literal exactness (`1.5` → "positive safe
  integer" error), and config-only trailing-byte tolerance; write via our own
  canonical 2-space pretty writer byte-compatible with
  `JSON.stringify(v, null, 2)+"\n"` (needed for `--serve` snapshot exactness).
  Spike: confirm zio-json exposes duplicate-key detection and how it reports
  trailing content; if a gap exists, keep zio-json as the parser and add a thin
  targeted check around it. The harness-visible contract does not change.
- **HTTP via zio-http**: server bound to `127.0.0.1` serving one immutable
  snapshot resource with exact status/header control (`Content-Type:
  application/json; charset=utf-8`, 404 for non-matching targets including
  query strings, 405 + `Allow: GET, HEAD`, zero-body HEAD); client performs one
  GET with redirects disabled. Spike first (L5): verify zio-http version
  compatibility with ZIO 2.1.26/Scala 3.3.8, exact header behavior, and
  assembly merge strategy for Netty. If zio-http cannot meet a byte-exact
  requirement, record a deviation and fall back to JDK HTTP behind the same
  ZIO interface.
- **Exit codes** via memo pattern: compute `ExitCode`, end with
  `ZIOApp#exit(code)`; `run / fork := true` in build.sbt. Exit-code and
  console-output behavior is integration-tested by the vendored YAML harness
  (every case asserts exit code + exact stdout/stderr at process level), plus
  `CliSpec`/`RenderSpec` unit tests.
- **Assembly**: `snap-assembly-0.1.0.jar` (name/version pinned in build.sbt;
  vendored `harness/snap/run_tests` finds `target/scala-*/*-assembly-*.jar`).
  zio-http pulls Netty — extend the assembly merge strategy as needed.

## File-ownership table (lane boundaries for Phase C/F)

| Lane | Production files (exclusive) | Test files (exclusive) |
|---|---|---|
| L1 model+errors | `Model.scala`, `SnapError.scala` | `ModelSpec.scala`, `SnapErrorSpec.scala` |
| L2 json+diff+ot | `Json.scala`, `Diff.scala`, `Ot.scala` | `JsonSpec.scala`, `DiffSpec.scala`, `OtSpec.scala` |
| L3 codec+replay | `Codec.scala`, `Replay.scala` | `CodecSpec.scala`, `ReplaySpec.scala` |
| L4 tree+io | `WorkingTree.scala`, `RepoIo.scala`, `Config.scala` | `WorkingTreeSpec.scala`, `RepoIoSpec.scala`, `ConfigSpec.scala` |
| L5 http | `HttpFetch.scala`, `HttpServe.scala` | `HttpSpec.scala` |
| L6 render+cli | `Render.scala`, `Cli.scala` | `RenderSpec.scala`, `CliSpec.scala` |
| L7 commands+main | `Commands.scala`, `Main.scala` | `CommandsSpec.scala`, `MainSpec.scala` |

Dependency order: L1 → (L2, L6) → L3 → (L4, L5) → L7. L1 is complete
(commit `14be7c4`) but `Json.scala` is rewritten in L2 for zio-json and
`SnapError.scala` gains the refined validation cases. L2∥L6 run as parallel
worktree lanes; L4∥L5 likewise; L3 and L7 are sequential.

## Verification plan

1. Per-lane gates: `sbt "compile; test; scalafmtCheckAll"` after each lane;
   `assembly` after merges. Exit-code and console-output validation is covered
   by the supplied YAML harness (process-level integration tests asserting exit
   codes and exact stdout/stderr on every case), plus `CliSpec`/`RenderSpec`
   unit tests.
2. Harness checkpoints (fat jar via vendored harness):
   - after L1–L4 + L6 + L7 skeleton: tests 01–08, 14, 19, 23–25 should pass;
   - after L2/L3 engines wired into commit/diff: 05, 06, 21, 22;
   - after merge: 09–11, 16–18, 20, 26, 27;
   - after L5: 12, 13;
   - after Render terminal modes: 28.
   Final: all 28/28 via
   `bash harness/snap/run_tests --lang scala --implementation-root $PWD/snap`.
3. Adversarial probes (Phase E): oversized messages (>4096), unicode IDs at 254
   bytes, deep path prefixes, concurrent-merge permutations beyond suite,
   `--serve` port reuse/invalid, broken pipe on stdout, `HOME` unset with
   `config --global`.
4. Coverage protocol per plan §10 at the end (sbt-scoverage, target ≈80%
   statement coverage; pure-data ADT leaves exempt).

## Risks / watch items

1. **SIGTERM/SIGINT → exit 0** on the fat jar: needs an early empirical spike
   (L5) — JVM default would exit 143; plan is `sun.misc.Signal` handlers that
   complete the serve effect so `exit(ExitCode.success)` runs (verify interplay
   with zio-http's own shutdown hooks).
2. **`--serve` HEAD with zero body bytes**: harness uses a raw socket; must set
   headers incl. Content-Length but write no body.
3. **Byte-exact pretty JSON** for the served snapshot (Node-stringify
   compatible: per-line array elements, `"key": value` spacing, empty
   containers inline, minimal string escaping, trailing LF) — custom writer
   over zio-json AST, golden-tested.
4. **zio-json exactness**: duplicate-key detection with key name, integer-
   literal strictness (`1.5`, `1.0`, `1e2`), and trailing-content behavior for
   config files — spike before L2/L3; thin wrapper checks fill any gaps.
5. **zio-http wire exactness**: confirm exact header casing/values, 405/404
   behavior, HEAD zero-body, redirect-off client, and Netty assembly merge;
   fall back to JDK HTTP behind the same ZIO interface only if zio-http cannot
   meet the byte-exact contract (recorded deviation).
6. **OT transform edge cases** (count splitting, trailing inserts, Q-insert
   priority) — pinned by test 22; unit-test exhaustively.
7. **Canonical integration order** drives log order, warning attribution, and
   convergence — Snap-order comparator must be exact.
8. **Config trailing-JSON tolerance** vs strict repository parsing (CONTRACT §15
   ruling A).
9. Java 25 `sun.misc.Unsafe` stderr warnings on Scala 3.3.x startup: harness
   success cases assert `stderr_equals: ""`. **Resolved (verified
   2026-09-04):** `-Dsun.misc.unsafe.memory.access=allow` silences them; the
   vendored `harness/snap/run_tests` wrapper passes the flag. No Java 21 pin
   needed.

## Decision log

| Date | Decision | Rationale |
|---|---|---|
| 2026-09-05 | Use zio-http (not JDK HttpClient/HttpServer) for both client and server | User direction. Spike will verify byte-exact wire behavior; JDK fallback only if zio-http cannot meet the contract. |
| 2026-09-05 | Use zio-json (not a hand-rolled parser) | User direction. Parse to `zio.json.ast.Json`; custom canonical writer for Node-compatible pretty output; spike verifies duplicate-key + trailing-content behavior. |
| 2026-09-05 | `Port` is a validated `Int`-backed opaque type parsed at the CLI boundary | User direction; invalid input surfaces as `InvalidPort(input)` for the exact error message. |
| 2026-09-05 | Repository-validation failures are individual `SnapError` cases with typed fields, not one free-string case | User direction; pinned substrings derived from fields in one place per case. |
| 2026-09-05 | `Revision` positivity/range validated at every parse site (CLI and JSON) | User direction; `NotPositiveSafeInteger` carries context. |
| 2026-09-05 | `Version.join` remains total (`Version`, not `Either`) | Componentwise max of two valid versions is always valid; invalid input is rejected at parse boundaries. |
| 2026-09-05 | `Replay.Warning` is a sealed enum (DeleteWins/LaterCreateWins/LaterPutWins/NamespaceWins/PutWins), not a String pair | User direction; rendering derives `auto-resolved <path>: <reason>`. |
| 2026-09-05 | Exit-code and console-output validation relies on the supplied YAML harness plus Cli/Render unit tests | User question answered: the 28-case harness is a process-level integration suite asserting exit codes and exact stdout/stderr. |
