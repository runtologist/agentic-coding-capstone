# Lane E1 — Spec-Compliance Review (Snap / Scala)

**Reviewer:** E1 subagent (read-only; no builds, no git mutations, no servers run)
**Scope:** clause-by-clause comparison of `snap/src/main/scala/snap/*.scala` (16 files, read fully)
against `harness/snap/SPEC.md` §1–§12, cross-checked with `docs/snap/CONTRACT.md` (frozen rulings)
and the 28-case harness; unit-test evidence cited from the 15 test suites.
**Repo state:** branch `main`, HEAD `4f03ed0` (packet said `8ab113e`; `.git/logs/HEAD` shows exactly
one docs-only ledger commit since — no code delta).
**Parent note:** report persisted by parent (reviewer lane had no write tool).

## Verdict

**No blockers. No majors.** 3 minor, 4 nit. Merge status: **OK with notes.**

## Method

- Read SPEC.md fully; mapped every normative clause (§1–§12) to implementing code.
- Read all 16 production sources end-to-end; spot-read tests (JsonSpec, ConfigSpec, RenderSpec,
  ModelSpec, OtSpec, HttpSpec, CommandsSpec greps) and harness YAML 12/14/23/25 for pinning evidence.
- Determinism audit: grepped main sources for clock/locale/randomness; inspected every hash-based
  iteration site.
- Per read-only constraint, nothing was executed. The 481-unit / 28-harness green claim is taken from
  LEDGER.md gate records (final gate on equivalent tree; only docs commit since).

## Correct — clause-by-clause highlights

**§1–§2 model & working tree.** Vector-clock model, patch dots, serial-contributor rule
(`Model.scala`). Symlinks/non-regular entries fail scans with exact
`snap: unsupported working tree entry: <path>` and are never followed
(`WorkingTree.processEntry`, `NOFOLLOW_LINKS`) — test 08. Path rules (no backslash/control chars,
no empty/`.`/`..` segments, first segment ≠ `.snap`, no normalization) in `Model.validatePath`.
All spec orderings use unsigned-UTF-8 byte compare (`Model.utf8Compare`), never `String.compareTo`
— test 25 unicode order. Top-level `.snap/` excluded from tracking; extra files inside ignored.
Dirty/clean semantics; merge/revert refuse dirty trees (tests 04/07/20).

**§3 versions.** ID rules incl. 254-byte boundary (ModelSpec:34-40); canonical CLI parse rejects
duplicates, zeroes, leading zeroes, overflow, whitespace, unsorted (ModelSpec:94-95; tests 19/25);
four-way causal compare, componentwise join, Snap order exactly per §3.3/§3.4
(`Model.causalCompare/join/snapOrder`). Max-safe-integer enforced at every parse site
(`positiveSafeInteger`; JsonSpec:228-236).

**§4 format & validation.** All six §4.5 rules present in `Codec.validateRepository` in a fixed
first-error-wins order: schema/typed values (Json layer), patch sorting + one-value-per-dot +
contiguity, base closure + `revision = base[author]+1`, acyclicity (`Replay.topoSort` stall →
`cyclic or incomplete patch history`), change-vs-base (`validateChangeAgainstBase`), deterministic
frontier replay (`materializeValidating`). Strict JSON: duplicate keys rejected with key name at
every nesting level (JsonSpec duplicate suite); unknown fields pinned (`repository has unknown
field: unknown`, tests 23/15); repository.json rejects trailing bytes (`firstValueEnd` scanner +
JsonSpec trailing suite) while config tolerates them (ruling A; ConfigSpec trailing test);
Node-`JSON.stringify(v,null,2)+"\n"` byte-compatible writer (test 12 pins served bytes).
Message rules (tab/LF only controls; 4096-byte commit cap with boundary tests ModelSpec:351);
canonical padded base64 with round-trip check (ModelSpec:378-382); edit-op arity/positivity/
adjacency/insert-token canonicality (test 23 matrix). Validation never mutates (tests 15/20/23/26).

**§5 canonical diff.** Exact recurrence, deletion-on-tie (`del <= ins`), forced diagonal on equal
tokens, safe equal-prefix trim, coalescing, empty-script-creates-empty-file (ruling F; test 06),
`\ No newline at end of file` marker (`Diff.scala`, `Render.tokenLine`; DiffSpec incl. property
check vs naive reference; goldens tests 05/06/26).

**§6 replay & OT.** §6.1 least-ready ordering (snapOrder of results → author UTF-8 bytes →
revision) in `Replay.topoSort`/`patchOrdering`. §6.2 namespace-first resolution: S vs C′ (current
minus authored deletions), conflicting current paths removed with collapsed `namespace-wins`
warnings, decisions override per-path rules (`integratePatch`). §6.3 transform table matches
line-for-line incl. Q-insert priority, count splitting, trailing inserts, coalescing
(`Ot.transform`; OtSpec 12 tests incl. apply-cleanly property). §6.4 rules 1–6 in spec order with
exact reasons; warnings unique + sorted by path then reason; merge prints only pairs new vs the
pre-merge local replay; re-merge no-op prints unchanged version with empty stderr
(`Commands.merge`; tests 09–11, 16–18, 20, 22). §6.5 direction/association independence follows
from canonical replay (tests 11/17/18).

**§7 commands.** Grammar matrix incl. diff-usage special case (ruling B), option
position/uniqueness, no file creation on grammar errors (`Cli.scala`; tests 14/24). init
reinit/inside-repo errors, intermediate dirs, `()` output (tests 01/02). status/log exact formats,
log escaping order backslash→tab→LF (test 04). commit check order load→contributor→message→scan→
dirty (CONTRACT §13). diff validates repos/versions/collisions before any output; cross-repo diff
never imports or mutates (tests 16/26). revert order parse→known→clean→contributor→no-op
(test 14/19); additive, `revert to <version>` message. merge requires no contributor, order per
ruling 8 (validate local → load+validate remote → collision → joined replay → dirty check → write).
serve: validates before printing, binds 127.0.0.1, port 0 → actual port, URL always plain +
flushed (ruling I), SIGTERM/SIGINT → exit 0 (`HttpServe.scala`, `Commands.serve`; test 12).
`--version` needs no repository, pinned `snap 1.0.0` (test 14/28).

**§8 configuration.** Local-before-global; local failure fatal with no global fallback (test 25);
missing file = no value; malformed/duplicate/unknown-field/invalid-ID errors; `$HOME` absent →
global unavailable not an error (test 19); writer drops unknown fields (test 25).
Missing-ID message exact for commit/revert.

**§9 HTTP.** Server: GET/HEAD exact path with no query → 200 + `application/json; charset=utf-8`;
HEAD zero body (ruling H); other methods → 405 `Allow: GET, HEAD`; other paths/queries → 404;
immutable startup snapshot (`HttpServe.snapshotRoutes`; HttpSpec server suite; test 12). Client:
exactly one GET, 200 required, redirects not followed (`HTTP 302` error), body parsed + fully
validated, transport failures → `snap: <detail>` exit 1 (`HttpFetch`; HttpSpec client suite; test 13).

**§10 failures.** Exit 0/1/2 split with defects → `snap: internal error` + exit 2
(`Commands.finish`/`serve`); one-line `snap: <detail>` errors to stderr; results to stdout; UTF-8/LF
throughout; working files written before repository.json via same-directory temp + atomic move
(`RepoIo.writeRepositoryAtomic`); commit replaces metadata only.

**§11/§12.** Per-stream TTY matrix unit-tested at resolver level (RenderSpec: all four
combinations + NO_COLOR/always/never/invalid). Out-of-scope features (branches, tags, push, etc.)
are absent. Item 11 (cross-language exchange) applies to three-implementation maintainers; N/A
to this Scala-only workspace.

## Findings

**Blockers: none. Majors: none.**

### S1 — minor: commit/revert revision overflow is not rejected and can persist an unloadable repository
- **Spec:** §7.5 (“A clean tree, invalid message, **overflow**, or dot collision is an error”), §3.1 (revision ≤ 9007199254740991).
- **Evidence:** `Commands.commit` computes `revision = repo.frontier.get(contributor) + 1L` with no range check (same in `revert`); `Version.withComponent` does not range-check. A repository with frontier `[["a@x",9007199254740991]]` passes validation (`positiveSafeInteger` accepts the max), and the next commit/revert writes revision `9007199254740992`, after which **every** command fails with `…is not a positive safe integer` — overflow corrupts the repo instead of erroring.
- **Repro (needs write access; not run):** craft `.snap/repository.json` with frontier `[["a@x",9007199254740991]]` + one patch at that dot; `snap config contributor.id a@x`; add a file; `snap commit m` → exit 0, repo then unloadable.
- **Reachability:** impossible organically (2^53 commits); reachable via hand-crafted repository.json. Borderline major if adversarial input counts; minor otherwise.
- **Fix:** in commit/revert, fail with a typed `SnapError` when `repo.frontier.get(contributor) >= Model.MaxSafeInteger` before constructing the patch.

### S2 — minor: auto-mode TTY detection is one process-wide probe, not per-stream (SPEC §7.11 table)
- **Spec:** §7.11: unset/auto → “terminal mode **independently on stdout or stderr** when that stream is a TTY”; §11: MUST unit-test TTY/non-TTY selection for stdout and stderr independently.
- **Evidence:** `Main.scala:43` captures a single `isTty = java.lang.System.console() != null`; `Commands.run` passes it for both streams: `resolvePresentation(env.snapColor, env.noColorPresent, env.isTty, env.isTty)`. `System.console()` is null unless stdin **and** stdout are consoles, so: (a) piped stdin + TTY stdout → plain instead of terminal on stdout; (b) TTY stderr + piped stdout → stderr plain instead of terminal; (c) TTY stdout + piped stderr → stderr gets ANSI instead of plain.
- **Mitigants:** documented raw-JVM exception (`ARCHITECTURE.md:207` — “no per-stream isatty on JVM”); harness cannot exercise TTY (CONTRACT §5); RenderSpec covers all four independent flag combos, so the §11 unit-test duty is met at the resolver level. Reported because it contradicts the letter of the §7.11 table in mixed-stream scenarios despite the documentation.
- **Fix (if pursued):** per-fd isatty via JLine/JNI, or record an explicit waiver ruling in CONTRACT §15.
*(Duplicate of E2 F1.)*

### S3 — minor: canonical diff has a 64M-cell budget that surfaces as exit-2 “internal error”
- **Spec:** §5 (recurrence defines output for all inputs; optimizations allowed only if same script), §10 (exit 2 reserved for *unexpected* internal failures).
- **Evidence:** `Diff.MaxDiffCells = 64000000L`; `dpDiff` throws `IllegalStateException` over budget → ZIO defect → `snap: internal error`, exit 2 (via `Commands.finish`). Reachable in normal use: diffing/committing ~8,000-line text versions ((8001)² > 64M cells).
- **Note:** also flagged by lane E3 (M4); deterministic behavior, but a predictable budget should not use the “unexpected” channel.
- **Fix:** typed `SnapError.DiffTooLarge` → clean exit-1 message, or raise/document the budget.

### N1 — nit: structurally-equal duplicate patches inside one repository.json are accepted
- **Spec:** §4.1 “patches contains **exactly** the causal closure of frontier … no unreachable patches”; §4.2 same-dot structural equality defines import duplicates.
- **Evidence:** `Codec.checkDotCollisions` fails only on differing values; adjacent equal dots pass `checkPatchesSorted`. Harmless in practice: deduped at every use (`Replay.dedupePatches`), writers never emit duplicates; ruling G pins cross-repo collapse but is silent on within-file duplicates; not harness-tested.
- **Fix (optional):** reject within-file duplicate dots in validation, or record a ruling accepting them.

### N2 — nit: integer-valued decimal/exponent JSON numbers accepted for integer fields
- **Spec:** §4.1 “non-integer numbers … are errors.”
- **Evidence:** `Model.positiveSafeInteger` calls `stripTrailingZeros()` before the scale check → `"revision": 2.0`, `"retain": 1e2` accepted; `Json.requireFormat` compares numerically → `"format": 1.0` accepted. Defensible under §4.1 “the parsed typed value … is authoritative”; a strict syntactic reading would reject. Tests pin only `1.5` rejection.
- **Fix (optional):** reject pre-strip scale > 0 if cross-language parity demands; otherwise record a ruling.

### N3 — nit: HEAD responses may not carry GET-equivalent headers (Content-Length)
- **Spec:** §9 “HEAD returns the same status and headers without a body.”
- **Evidence:** `HttpServe` answers HEAD with `Body.empty`, so any auto-computed `Content-Length` reflects 0 rather than the snapshot length (RFC 9110 SHOULD-level). Harness test 12 + ruling H pin only status/content-type/zero-body, which pass. Needs a dynamic wire check to confirm (defer to lane E4).

### N4 — nit: in-frontier but non-base-closed versions report `missing <id> revision <n>` rather than `unknown version: <v>`
- **Spec:** §4.1 known-version definition; `diff`/`revert` must reject — rejection happens (exit 1, `snap: <detail>`); message wording not pinned.
- **Evidence:** `Codec.knownVersion` returns `MissingPatch` for versions within the frontier whose selected set lacks a base dot (e.g. `(b@x->1)` when `(b,1)` bases on `(a,1)`). Conforms to §10 shape; noted only for cross-language message parity.

## Determinism audit (task focus area)

- Greps over `snap/src/main/scala`: **no** `System.currentTimeMillis|nanoTime`, `Instant/Date/Calendar/TimeZone`, `Locale`, `Random/UUID`. Only ambient reads: `System.console()` (Main.scala:43) and `System.getenv("SNAP_DEBUG")` (debug-only defect printing in Commands).
- Every hash-ordered iteration site inspected and order-independent: `Replay.integratePatch` namespace loops (set accumulation; warnings deduped + sorted by path/reason at end), `Codec.checkContiguity` (authors sorted before scan), `checkReachability` (errors via vector-ordered `collectFirst`), `WorkingTree.scan`/`collectRegularFiles` (directory entries sorted by unsigned UTF-8 before processing — deterministic first-unsupported-entry reporting), `Commands.merge` (warnings sorted; patches written sorted via `patchLess`). No map-iteration-order-dependent output found.

## Could not verify read-only (handoff to supervisor / lane E4)

1. Live HEAD wire bytes for N3 (Content-Length on HEAD).
2. Re-run of 481 unit tests + 28/28 harness at HEAD `4f03ed0` (ledger gate evidence covers the equivalent tree; only a docs commit since).
3. S1 repro requires writing a crafted repository and running `snap`.
