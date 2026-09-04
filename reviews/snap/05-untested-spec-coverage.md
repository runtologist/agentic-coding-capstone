# Report: `reviews/snap/05-untested-spec-coverage.md`

## Lane E5 — Untested-Spec Coverage Hunt: Snap (Scala)

**Repo:** `/Users/sschenk/ziverge/vibe-coding-2-workshop/capstone-scala` (main @ 8ab113e)
**Sources reviewed:** `harness/snap/SPEC.md` (full), all 28 cases in `harness/snap/tests/*.yaml`, `docs/snap/CONTRACT.md`, all 16 files under `snap/src/main/scala/snap/*.scala`, all 15 suites under `snap/src/test/scala/snap/*.scala`, `harness/snap/run_tests`.
**Method:** clause-by-clause enumeration of every normative (MUST/SHOULD/MAY) statement in SPEC.md §1–§11, mapped to (a) harness YAML case, (b) unit suite/test, or (c) nothing. For uncovered clauses, the implementing code was inspected to judge compliance; divergences are ranked findings, compliant-but-untested clauses are listed as untested risks.
**Parent note:** report persisted by parent (reviewer lane had no write tool).

**Status legend:** `H+U` = harness + unit covered · `U` = unit only · `H` = harness only · `UNTESTED` = no coverage found, code appears compliant · `FINDING(Fn)` = see ranked findings.

### Summary of counts
- Findings: **0 blocker, 1 major, 2 minor, 7 nit** (10 total)
- Matrix rows: 78 clauses → 62 fully covered (H+U), 4 harness-only, 6 unit-only, 6 untested-but-compliant risks.

---

## Ranked findings

### F1 — MAJOR: Hard 64M-cell cap in canonical diff aborts with exit 2 ("internal error") on large-but-valid text diffs
- **Spec:** §5 defines the token diff recurrence as total over any old/new token sequences and permits alternative algorithms "only if it produces the same script" — no size exemption. §10 reserves exit code 2 for "unexpected internal failures," not input-size conditions.
- **Evidence:** `snap/src/main/scala/snap/Diff.scala:30` (`MaxDiffCells: Long = 64000000L`) and `Diff.scala:84-87` (`throw new IllegalStateException(...)`). The throw occurs inside pure callers (`Commands.buildChanges` via `ZIO.fromEither`, `Replay.integratePatch`), so it surfaces as a ZIO defect and is mapped by `Commands.finish` (`Commands.scala:94-113`) to exit 2 with `snap: internal error`.
- **Impact:** Any commit, working-tree diff, version diff, or merge whose per-file token diff exceeds ~8,000 × 8,000 cells (e.g., rewriting a ~9k-line text file) fails with an opaque internal error instead of producing the mandated canonical diff. Affects core VCS utility on realistic source files.
- **Coverage:** NOTHING tests this path; it is also the only reachable trigger of the exit-2 channel (see F4).
- **Repro:** create a repo, commit a text file of ~10,000 lines; rewrite the file with ~10,000 different lines; run `snap diff` (or `snap commit`). Expect exit 2, stderr `snap: internal error`, no diff output.
- **Smallest fix:** replace the dense decision table with a linear-space algorithm that yields the identical spec walk (Hirschberg over the same recurrence with the deletion-on-tie rule), or at minimum convert the cap into a typed `SnapError` (exit 1, explicit detail) and document the limit; add a unit test pinning whichever behavior is chosen.

### F2 — MINOR: Per-stream TTY detection required by §7.11 is not implemented at the process boundary
- **Spec:** §7.11 table: unset/`auto` → "terminal mode independently on stdout or stderr when that stream is a TTY, unless NO_COLOR is present"; §11 requires unit-testing auto selection for TTY/non-TTY stdout and stderr independently.
- **Evidence:** `Main.scala:43` captures one flag, `isTty = java.lang.System.console() != null`; `Commands.scala:82` passes it twice: `Render.resolvePresentation(env.snapColor, env.noColorPresent, env.isTty, env.isTty)`. `Commands.CmdEnv` carries a single `isTty` field. `System.console()` is non-null only when stdin and stdout are attached to a console and does not reflect stderr, so the two streams can never be resolved independently in production:
  - stdout piped, stderr TTY (`snap status | cat`): console is null → stderr wrongly plain (should be terminal).
  - stdout TTY, stderr redirected (`snap status 2>err.log`): console non-null → ANSI escapes written into the non-TTY stderr file.
- **Coverage:** `RenderSpec` "resolvePresentation" suite tests the pure 4-argument function with independent booleans, satisfying the letter of the §11 unit-test requirement, but nothing tests or implements the real per-stream detection (`MainSpec` only varies the single collapsed flag). Harness cannot exercise it (no PTY).
- **Repro:** from an interactive terminal run `snap status 2>err.txt` with a dirty tree; observe SGR bytes in `err.txt` although stderr is not a TTY.
- **Smallest fix:** detect stdout/stderr TTY-ness separately (e.g., via jline/JNA `isatty` on fds 1 and 2, or a JDK facility if available on the toolchain), extend `CmdEnv` with `stdoutIsTty`/`stderrIsTty`, and add `MainSpec` cases asserting the wiring maps them independently.

### F3 — MINOR: Read-only scans silently accept working-tree filenames that violate §2 tracked-path rules
- **Spec:** §2: "A tracked path … MUST be nonempty, contain no ASCII control character or backslash, contain no empty, `.` or `..` segment…"; §10: any command that scans the working tree fails on unsupported entries "rather than following or silently ignoring it."
- **Evidence:** `WorkingTree.scan` (`WorkingTree.scala:43-104`) rejects only non-regular entries (symlink/FIFO); it never calls `Model.validatePath` on collected names. `Commands.status` and the no-arg `Commands.diff` render whatever scan returns. Only `Commands.buildChanges` (`Commands.scala:172`) validates paths, so `commit` fails while `status`/`diff` print e.g. `A bad\name` for a file named `bad\name`.
- **Coverage:** NOTHING (harness has no invalid-filename working-tree case; unit `ModelSpec.validatePath` covers the validator in isolation, not the scan path).
- **Repro:** in a repo, create a regular file whose name contains a backslash or a control character; run `snap status` (currently lists it, exit 0) vs `snap commit x` (currently fails with `snap: path is invalid: …`). Spec-consistent behavior would be for status/diff to fail as well.
- **Smallest fix:** validate each scanned relative path in `WorkingTree.scan` and fail with the existing `InvalidRepoPath` (or `UnsupportedEntry`-style) error; add a CommandsSpec/WorkingTreeSpec case.

### F4 — NIT (untested risk): exit-code-2 channel has zero executed coverage
- **Spec:** §10: "unexpected internal failures exit 2."
- **Evidence:** `Commands.finish` (`Commands.scala:94-113`) and the `serve` fold map defects to exit 2 + `snap: internal error`; `SnapErrorSpec` only asserts the message string, never the exit code. No test forces a defect (the only practical trigger is F1's diff cap).
- **Repro idea:** unit test invoking `Commands.run` with an `Output`/effect rigged to die (e.g., `ZIO.dieMessage`), asserting code 2 and plain `snap: internal error\n`.

### F5 — NIT (untested risk): §10 mutation ordering (working files before `repository.json`) not directly verified
- **Evidence:** `Commands.revert`/`merge` call `WorkingTree.materialize` before `RepoIo.writeRepositoryAtomic` (compliant by construction); `RepoIoSpec` proves atomic temp-file replacement and no leftover temps, but no test simulates metadata-write failure after tree writes (or vice versa) to prove ordering/effects.
- **Repro idea:** inject a failing repository write (or test against a read-only `.snap` directory) during revert and assert the working tree changed while `repository.json` stayed old, matching §10's documented partial-failure semantics.

### F6 — NIT (untested risk): serve default port 8765 and loopback-only binding not exercised end-to-end
- **Evidence:** harness cases 12/28 always use port `0`; `CliSpec`/`ModelSpec` assert `Port.default == 8765` and parsing; `HttpServe.serve` binds `"127.0.0.1"` (`HttpServe.scala`), but nothing asserts the listener is unreachable from a non-loopback interface or that `snap --serve` with no argument binds 8765 (port-conflict risk explains the omission).
- **Repro idea:** optional/local-only test: `--serve` with no port, GET `http://127.0.0.1:8765/repository.json`, plus a connection attempt to the same port on the host's non-loopback address expecting refusal.

### F7 — NIT (untested risk): `https://` operand recognized but never exercised
- **Evidence:** `HttpFetch.isHttpUrl` accepts `https://` (`HttpFetch.scala:53`); all harness/unit fetch tests use `http://` against the local server or stub server. TLS behavior (cert validation, failure mapping) is unverified. CONTRACT §15.4 acknowledges this is out of offline-harness scope.
- **Repro idea:** unit-level test with a local TLS listener, or at minimum assert a certificate failure maps to `IoFailure` exit 1 rather than a defect.

### F8 — NIT (untested risk): merge of an empty / strictly-contained remote no-op not explicitly pinned
- **Evidence:** re-merge no-op covered by test 09 and `CommandsSpec` (`noop == (0, "(alice@x->1,bob@x->1,seed@x->1)\n", "")`), but the degenerate case of merging a remote with zero patches (empty frontier) into a populated local repo — which exercises `noOp = union.length == local.patches.length && joined == local.frontier` via the identity path — has no dedicated test. Code appears compliant.
- **Repro idea:** init empty remote, commit locally, `snap merge ../empty` → expect unchanged version, empty stderr, repository.json byte-unchanged.

### F9 — NIT (untested risk): generated revert messages longer than 4096 bytes (§4.2 exemption) untested
- **Evidence:** `Commands.revert` builds `s"revert to ${target.render}"` and only stored-message validation (no length cap) applies on write/reload; `ModelSpec` tests the 4096 commit limit, never the revert exemption. Compliant by construction (revert bypasses `validateCommitMessage`).
- **Repro idea:** unit test constructing a target `Version` with enough contributors that `render` exceeds 4096 bytes, asserting revert succeeds and the repository later re-validates.

### F10 — NIT (untested risk): non-UTF-8 filenames and case-normalization edges
- **Evidence:** §2 promises UTF-8 paths and no Unicode/case normalization. `WorkingTree.scan` decodes names via `Path.getFileName.toString` (platform charset) and never validates encoding; macOS NFD/NFC filename forms are not exercised anywhere. Unicode *content* and é/😀 path ordering are covered (test 25, `ModelSpec`), but malformed-encoding filenames are not. Code likely surfaces them as invalid paths only at commit (via `validatePath` on the decoded string), while status/diff would echo them (see F3).
- **Repro idea:** create a file whose name is an invalid-UTF-8 byte sequence and assert a deterministic failure (or documented replacement behavior) across status/diff/commit.

*Note (not a finding):* `Model.positiveSafeInteger` accepts integer-valued float forms (`1.0`, `1e2`) per JS `Number.isInteger` semantics (pinned by `ModelSpec`); spec's "non-integer numbers are errors" is ambiguous here and CONTRACT §15.3 leaves wording free. Recorded as an adopted interpretation. Also noted: `harness/snap/run_tests` injects `-Dsun.misc.unsafe.memory.access=allow` to silence Java 25 stderr warnings — harness-environment coupling for byte-exact stderr assertions (flagged for the E2 lane, not a spec-coverage gap).

---

## Clause-by-clause matrix

### §1 Product model & §1.1 Core invariants

| # | Clause | Harness case | Unit test | Status |
|---|---|---|---|---|
| 1.1 | Repo starts at empty tree, version `()` | 01 (empty repo json, `()` stdout) | RepoIoSpec init, CommandsSpec init | H+U |
| 1.2 | Patch names exact base version | 05 (base `[[a@x,1]]` pinned) | ModelSpec Patch.result, CommandsSpec commit | H+U |
| 1.3 | Patch increments author revision by one | 03/04/05 (`->1`, `->2` stdout) | ModelSpec "result increments only the author component" | H+U |
| 1.4 | Merge imports patches, joins frontiers, no merge patch | 09/13/21 (joined versions, no local contributor in 13) | ReplaySpec, CommandsSpec merge | H+U |
| 1.5 | Automatic deterministic resolution; may discard effects | 10/17/22 | ReplaySpec order-independence | H+U |
| 1.6 | Original patches stay in history | 07 (log retains all 4 after reverts) | CommandsSpec revert additive | H+U |
| 1.7 | Warning facts on whole-file resolution | 10/11/17 | ModelSpec ReplayWarning, ReplaySpec | H+U |
| 1.8 | Manual-resolution workflow (descriptive) | — | — | non-normative |
| 1.9 | Binary name `snap` | (wrapper-based; not literally asserted) | — | UNTESTED (harness wrapper substitutes) |
| 1.10 | Inv.1: nonzero counters | 23 (retain 0), 25 (`->0`) | ModelSpec parse/positiveSafeInteger | H+U |
| 1.11 | Inv.2: one patch per dot | 16 | CodecSpec/ReplaySpec collision+dedupe | H+U |
| 1.12 | Inv.3: complete base present | 15 (gap) | CodecSpec base closure | H+U |
| 1.13 | Inv.4: known versions reproducible | 21 (diff at mid-history versions) | ReplaySpec mid-history materialize | H+U |
| 1.14 | Inv.5: same patch set → same tree | 18 (6 orders), 22 | ReplaySpec determinism/permutation | H+U |
| 1.15 | Inv.6: import = idempotent/commutative/associative union | 09 (re-merge, both dirs), 18 (6 association orders) | ReplaySpec | H+U |
| 1.16 | Inv.7: same dot different value = corruption | 16 | CodecSpec checkCollision | H+U |
| 1.17 | Inv.8: `.snap/` never tracked | 25 (`.snap/untracked` invisible) | WorkingTreeSpec skip `.snap` | H+U |
| 1.18 | Serial-contributor ⇒ version identifies one closure/tree | implicit in 19/21 | CodecSpec knownVersion | U |

### §2 Repository and working tree

| # | Clause | Harness case | Unit test | Status |
|---|---|---|---|---|
| 2.1 | init creates `.snap/` under existing or new dir | 01/02 | RepoIoSpec init | H+U |
| 2.2 | Tracks every regular file except `.snap/` | 04/25 | WorkingTreeSpec scan | H+U |
| 2.3 | Contents arbitrary bytes | 06/26 (NUL, CRLF) | WorkingTreeSpec binary bytes | H+U |
| 2.4 | Directories implicit; empty dirs untracked | 25 (empty dirs invisible) | WorkingTreeSpec | H+U |
| 2.5 | Symlinks/non-regular: MUST report, MUST NOT follow | 08 (symlink, fifo), 20 | WorkingTreeSpec symlink/fifo/order | H+U |
| 2.6 | Permissions/ownership/timestamps/xattrs not tracked | — | (never read/written) | UNTESTED, compliant |
| 2.7 | Path rules: nonempty, no control/backslash, no `.`/`..`/empty segments, no leading `.snap` — repo-stored paths | 15 (`.snap/secret`), 27 | ModelSpec validatePath, JsonSpec, CodecSpec | H+U |
| 2.8 | Path rules — working-tree paths in read-only commands | — | (scan does not validate) | **FINDING F3** |
| 2.9 | No Unicode/case normalization | 25 (é, 😀 preserved) | ModelSpec utf8Compare | H+U (case edge UNTESTED, see F10) |
| 2.10 | Paths sort by unsigned UTF-8 bytes | 25 (`nested/file < z < é < 😀`) | ModelSpec sortedPaths, RenderSpec | H+U |
| 2.11 | Prefix-free trees; validated per patch | 15 (`a` + `a/b` in one patch) | CodecSpec checkPrefixConflicts | H+U |
| 2.12 | Prefix-free enforced during replay via §6.4 | 11 | ReplaySpec namespace tests | H+U |
| 2.13 | Clean/dirty definition | 04/07/20 | WorkingTreeSpec isClean | H+U |
| 2.14 | commit records dirty; merge/revert refuse dirty; read-only may inspect | 04 (clean-commit err), 07 (dirty revert), 20 (dirty merge), 05 (diff on dirty OK) | CommandsSpec | H+U |

### §3 Versions

| # | Clause | Harness case | Unit test | Status |
|---|---|---|---|---|
| 3.1a | ID: one `@`, nonempty sides, no control/ws/`,`/`(`/`)`/`->`, ≤254B | 03/25 (7 bad IDs) | ModelSpec ContributorId (incl. 254-byte boundary, non-ASCII) | H+U (254B boundary U-only) |
| 3.1b | Spelling preserved exactly | 03/04 (ids echoed verbatim) | ModelSpec | H+U |
| 3.1c | Revision positive ≤ 9007199254740991; zero omitted | 25 (`->0`, overflow), 23 (1.5) | ModelSpec positiveSafeInteger/parse | H+U |
| 3.2a | CLI exact form; dup/zero/leading-zero/overflow/invalid-id/whitespace/order errors | 19/25 | ModelSpec Version.parse, CliSpec | H+U |
| 3.2b | JSON ordered `[id, rev]` pairs | 23 (unsorted frontier) | JsonSpec version vectors | H+U |
| 3.3 | Four-way causal comparison; concurrency distinct | — (indirect via 09/17/21) | ModelSpec causal comparison suite (6 tests) | U |
| 3.4 | join componentwise max | 21 (stdout join) | ModelSpec join (commutative/idempotent) | H+U |
| 3.5 | Snap order definition; extends causal | 09 (bob-before-alice effect), 17 | ModelSpec snapOrder (3 tests) | H+U |
| 3.6 | Serial-contributor rule; dot corruption fails before writing | 16 | CodecSpec/ReplaySpec | H+U |

### §4 Repository and patch format

| # | Clause | Harness case | Unit test | Status |
|---|---|---|---|---|
| 4.1a | Layout `.snap/repository.json` + optional `config.json` | 01/03 | RepoIoSpec | H+U |
| 4.1b | Readers accept whitespace/key-order; typed value authoritative | 26 (shuffled keys merge as duplicates) | JsonSpec | H+U |
| 4.1c | Unique object keys required | 15 (repo), 25 (config) | JsonSpec duplicate-key suite | H+U |
| 4.1d | Writers SHOULD 2-space indent + trailing LF | 12 (body_text_equals exact pretty bytes) | JsonSpec writeRepository golden, RepoIoSpec | H+U |
| 4.1e | Unknown fields error | 23 (repo `unknown`), 27 (patch/change field), config unknown (unit) | JsonSpec, ConfigSpec | H+U |
| 4.1f | Non-integer numbers error | 23 (1.5) | JsonSpec integer strictness, ModelSpec | H+U (integer-valued float interpretation: U-only note) |
| 4.1g | patches = exact closure, sorted, no unreachable | 23 (unreachable), 27 (unsorted), 15 (gap) | CodecSpec steps 2/6/7/8 | H+U |
| 4.1h | "known version" definition; diff/revert reject unknown | 19 (`unknown version: (a@x->2)` echo), 21 (non-frontier known version diffs) | CodecSpec knownVersion (incl. non-base-closed selection) | H+U |
| 4.2a | dot, `revision = base[author]+1`, result rule | 27 (wrong dot) | CodecSpec, ModelSpec Patch.result | H+U |
| 4.2b | Same-dot duplicates iff structurally equal | 26 | CodecSpec/ReplaySpec dedupe | H+U |
| 4.2c | Message nonempty; tab/LF allowed; other controls invalid | 04 (tab/LF/backslash msg), 23 (empty), 25 (empty commit msg) | ModelSpec messages, CommandsSpec control-char rejection | H+U |
| 4.2d | Commit message ≤4096 bytes | — | ModelSpec 4096 boundary, CommandsSpec overlong | U |
| 4.2e | Revert messages may exceed 4096 | — | — | UNTESTED (compliant by construction) → **F9** |
| 4.2f | changes nonempty, sorted, ≤1 per path | 23 (empty), 27 (unsorted) | CodecSpec dup-path | H+U |
| 4.3a | text/put/delete shapes | 05/06/26 | JsonSpec change parsing | H+U |
| 4.3b | base64 standard padded RFC4648, any bytes | 15 (`abc` rejected), 06/26 (NUL bytes round-trip) | ModelSpec base64 (unpadded, trailing bits, alphabet) | H+U |
| 4.3c | create requires absent path; edit/replace/delete require present | 27 (create-over-present), 23 (delete absent) | CodecSpec change-vs-base | H+U |
| 4.3d | no-op change invalid; empty text edit may create empty file | 15 (no-op put), 06 (empty file `edit: []`) | CodecSpec no-op text, empty-create valid | H+U |
| 4.4a | text = valid UTF-8, no NUL; split after LF retaining LF; CRLF example; empty file no tokens | 26 (`a\r\n` token, `nul.bin`→put), 06 | ModelSpec tokenize/isText | H+U |
| 4.4b | op semantics; positive safe-int counts; no adjacent same-kind | 23 (retain 0), 15 (adjacent insert) | ModelSpec applyEdit/hasAdjacentSameKind, CodecSpec | H+U |
| 4.4c | script consumes complete old sequence; no implicit trailing retain | 15 (under-consume) | ModelSpec applyEdit, CodecSpec | H+U |
| 4.4d | result must be canonical token sequence | 27 (non-canonical insert tokens) | ModelSpec applyEdit non-canonical result, CodecSpec | H+U |
| 4.4e | empty script valid only for empty-file create | 06, 27 | ModelSpec, CodecSpec | H+U |
| 4.5 | Validation steps 1–6; cycle/missing-dependency failure; no fuzzy apply | 15 (cycle, gap, base64, under-consume, prefix, adjacent, no-op), 23 (12 layers), 27 (7 layers) | CodecSpec validateRepository (45 tests), ReplaySpec | H+U |

### §5 Canonical text diff

| # | Clause | Harness case | Unit test | Status |
|---|---|---|---|---|
| 5.1 | Recurrence + deletion-on-tie + coalescing define output | 05 (golden `[delete 1, retain 2, insert a]`) | DiffSpec golden + tie test | H+U |
| 5.2 | Repeated equal lines handled exactly | 05 | DiffSpec | H+U |
| 5.3 | Optimizations allowed only if identical script | — | DiffSpec property vs naive memoized reference; apply round-trip property; canonicity property | U |
| 5.4 | Algorithm total over all inputs | — | — | **FINDING F1** (64M-cell cap) |

### §6 Deterministic replay and OT

| # | Clause | Harness case | Unit test | Status |
|---|---|---|---|---|
| 6.1a | Selection `n <= V[c]`; base-closed selection | 21 | ReplaySpec, CodecSpec knownVersion | H+U |
| 6.1b | Ready-set order: Snap order → author UTF-8 → revision | 09/17/18 (observable effects) | ReplaySpec integrationOrder, ModelSpec snapOrder | H+U (author/revision tie-breakers only indirectly exercised) |
| 6.2a | Namespace-first resolution; S vs C′; install authored T; remove conflicting current paths; per-removal `namespace-wins`; collapse duplicates | 11 (both directions) | ReplaySpec namespace tests | H+U |
| 6.2b | Rule 1 B==C → apply authored directly | 09/21/22 | ReplaySpec | H+U |
| 6.2c | Rule 2 C==T → keep, collapse identical concurrent changes | 10 (`identical.txt`, no warning) | ReplaySpec identical-edits | H+U (identical concurrent delete/put variants UNTESTED → **F8**) |
| 6.2d | Rule 3 all-text → Q=diff(B,C), transform via §6.3, apply to C | 09/18/22 | OtSpec, ReplaySpec | H+U |
| 6.2e | Rule 4 fallback to §6.4 | 10/17 | ReplaySpec | H+U |
| 6.2f | Apply all changes together; install removes blockers, creates dirs, writes files, prunes newly-empty dirs | 07 (file↔dir), 11 | WorkingTreeSpec materialize (prune/preserve empty dirs, `.snap` untouched) | H+U |
| 6.3 | Transform table (all 6 rows), Q-insert priority, count splitting, trailing inserts, coalesce, once-vs-aggregate | 22 (4 scenarios) | OtSpec (all rows incl. mechanics suite) + applicability property | H+U |
| 6.4a | Rules 1–6 in order, reasons, warnings | 10 (delete-wins, put-wins, later-put-wins), 17 (later-create-wins), 11 (namespace-wins) | ReplaySpec delete-vs-edit both orders | H+U |
| 6.4b | Unique pairs sorted by path then reason | 10 (3-warning exact order) | ModelSpec byPathThenReason | H+U |
| 6.4c | Line OT emits no warning | 09/18/22 (empty stderr) | OtSpec/ReplaySpec | H+U |
| 6.4d | Merge prints only new warnings (joined minus local) | 09/10 re-merge empty stderr | CommandsSpec merge no-op | H+U |
| 6.5 | Same bytes+warnings guarantee; re-merge no-op; direction independence | 09/11/17/18/21 | ReplaySpec permutation tests | H+U |

### §7 Commands

| # | Clause | Harness case | Unit test | Status |
|---|---|---|---|---|
| 7.0a | Nearest-repo discovery walking to root | 19 (status from `repo/sub/deep`) | RepoIoSpec discover (nested, root, outside, `.snap`-as-file) | H+U |
| 7.0b | Operand = http(s) URL or local repo path; local resolved vs cwd | 13/16/26 (`../right`, `../remote`, URLs) | CliSpec merge http operand | H+U (https live UNTESTED → **F7**) |
| 7.0c | Options exact positions, at most once; unknown/extra/missing-value errors | 14/24 (full grammar matrix + no file creation) | CliSpec (all commands) | H+U |
| 7.0d | Plain mode default for non-TTY; §7.11 governs rest | 28 + all default-env cases | RenderSpec/MainSpec | H+U (per-stream independence → **F2**) |
| 7.1 | init: default `.`, create dirs, empty repo.json, preserve files, reinit error, inside-repo error, prints `()` | 01/02 | RepoIoSpec/CommandsSpec init suites | H+U |
| 7.2 | config: validate before write; local path; `--global` → `$HOME/.snapconfig.json` without repo; drop unknown fields; silent | 03/25 | ConfigSpec/CommandsSpec (incl. no-write on invalid id, global without HOME fails) | H+U |
| 7.3 | status: version line + A/M/D sorted; clean → version only | 04/25/28 | RenderSpec/CommandsSpec/WorkingTreeSpec compare | H+U |
| 7.4 | log: reverse canonical order, TSV, escape order `\\`→`\t`→`\n` | 04 (escape-order proof), 07 | RenderSpec/ModelSpec escapeLogMessage | H+U |
| 7.5a | commit: requires contributor + dirty tree | 03/04/19 | CommandsSpec commit suite | H+U |
| 7.5b | reject >4096-byte message | — | ModelSpec/CommandsSpec | U |
| 7.5c | diffs complete trees; one patch; text vs put vs delete selection | 05/06/26 (stored edits pinned) | CommandsSpec buildChanges outcomes | H+U |
| 7.5d | atomic repository.json replace; prints new version | 03-06 stdout; atomicity indirect | RepoIoSpec writeRepositoryAtomic (canonical bytes, no temp leftovers, round-trip) | H+U (crash-time atomicity UNTESTED) |
| 7.5e | errors: clean tree, invalid message, overflow, dot collision | 04 (clean), 25 (empty msg) | ModelSpec overflow; commit overflow/dot-collision effectively unreachable → UNTESTED (nit) |
| 7.6a | diff no-args = current vs working | 05/06/28 | CommandsSpec | H+U |
| 7.6b | diff old/new locally known versions | 05/21 | CommandsSpec | H+U |
| 7.6c | `--repo`: old local, new remote, no import | 13/26 (local repo.json unchanged) | CommandsSpec cross-repo | H+U |
| 7.6d | validate everything before output; cross-repo dot compare | 16 (collision, empty stdout), 26 | CommandsSpec collision-before-output | H+U |
| 7.6e | block format: headers, `@@ -1,n +1,m @@`, `/dev/null`, §5 ops, no-newline marker, binary line, empty on no-diff | 05/06/21/26/28 | RenderSpec diff suites | H+U |
| 7.7 | revert: contributor+clean+known; message `revert to <v>`; installs target; prints NEW version; already-current error; additive; check order (unknown version before missing contributor) | 07/19/28; 14 (order) | CommandsSpec revert suite | H+U |
| 7.8 | merge: clean required, no contributor needed; validate other; union+join; replay/install/update; no patch; warnings stderr + version stdout; contained/equal no-op | 09-11/13/16-18/20/21/26/28 (13 = merge without config) | CommandsSpec/ReplaySpec | H+U (empty-remote no-op → **F8**) |
| 7.9 | serve: validate+snapshot at startup; 127.0.0.1 only; default 8765, 0=OS; print+flush URL; snapshot until SIGINT/SIGTERM then exit 0 | 12 (snapshot immutability, exact pretty bytes, SIGTERM+SIGINT exit 0, invalid-repo startup), 28 (URL plain under always) | HttpSpec/CommandsSpec serve suite | H+U (default-port bind & loopback-only assertion → **F6**) |
| 7.10 | `--version` prints `snap <semver>` without locating repo | 14 (regex, run outside repo), 28 (`snap 1.0.0`) | MainSpec/CommandsSpec | H+U |
| 7.11a | Presentation MUST NOT change execution/effects/warning order/exit | 28 (semantics duplicated under always) | CommandsSpec presentation suite | H+U |
| 7.11b | SNAP_COLOR table incl. per-stream TTY independence, NO_COLOR conservative, always overrides NO_COLOR, never | 28 (never, NO_COLOR="" and "1", always, invalid value) | RenderSpec resolvePresentation (independent booleans), MainSpec | H+U for matrix logic; **FINDING F2** for production wiring |
| 7.11c | Invalid SNAP_COLOR error before execution, plain, exact text | 28 | MainSpec/CommandsSpec | H+U |
| 7.11d | All terminal layouts byte-exact (success lines, status, log, diff coloring, version, warnings, errors, silent config, plain serve URL) | 28 (pins every family) | RenderSpec goldens | H+U |

### §8 Configuration

| # | Clause | Harness case | Unit test | Status |
|---|---|---|---|---|
| 8.1 | Exact shape `{"contributor":{"id":...}}` | 03 (json_equals local+global) | ConfigSpec write bytes | H+U |
| 8.2 | Local read first; if ID provided global never read | 03 (local wins while global malformed) | ConfigSpec | H+U |
| 8.3 | Missing local falls through to global | 03 | ConfigSpec | H+U |
| 8.4 | Missing file = no value; malformed/dup-key/unknown-field/invalid-id in read file = error; invalid local blocks global | 03 (malformed global), 25 (invalid local id, dup key) | ConfigSpec (unknown field, dup key, malformed) | H+U |
| 8.5 | `$HOME` absent → global unavailable (not error) | 19 (`HOME: null` steps) | ConfigSpec | H+U |
| 8.6 | Only commit/revert require ID; exact missing-id message | 19 (revert), 03 (commit path via malformed global) | CommandsSpec | H+U |

### §9 HTTP repository

| # | Clause | Harness case | Unit test | Status |
|---|---|---|---|---|
| 9.1 | GET/HEAD `/repository.json` only | 12 | HttpSpec | H+U |
| 9.2 | GET: 200 + exact Content-Type + startup snapshot | 12 (body pinned before/after later commit) | HttpSpec (incl. golden bytes, immutability) | H+U |
| 9.3 | HEAD: same status/headers, zero body bytes | 12 (raw socket) | HttpSpec | H+U |
| 9.4 | Other paths 404 (incl. query string); other methods 405 + `Allow: GET, HEAD` | 12 | HttpSpec (PUT/DELETE too) | H+U |
| 9.5 | Client: one GET of exact URL, status 200 required, parse+validate body | 13/26 (http_requests_equal counts) | HttpSpec (404, one-request discipline) | H+U |
| 9.6 | Redirects not followed | 13 (302 → `HTTP 302`, single request) | HttpSpec | H+U |
| 9.7 | HTTP read-only (no local mutation on failure) | 16/26 | CommandsSpec | H+U |

### §10 Mutation and failures

| # | Clause | Harness case | Unit test | Status |
|---|---|---|---|---|
| 10.1 | merge/revert: parsing, validation, replay, dirty check, target construction before writing; no mutation on validation failure | 15/20/23/26 (files + repo.json untouched) | CommandsSpec | H+U |
| 10.2 | Scan-failing commands error on symlinks/unsupported entries | 08/20 | WorkingTreeSpec | H+U |
| 10.3 | Working files first, then repository.json via same-dir temp | — (order in code: Commands.revert/merge) | RepoIoSpec atomicity only | UNTESTED (compliant by construction) → **F5** |
| 10.4 | I/O failure may leave partial tree; reported; recovery out of scope | — | (IoFailure mapping exists) | UNTESTED (by-design untestable) |
| 10.5 | UTF-8 + LF output | all byte-exact cases | RenderSpec | H+U |
| 10.6 | stdout results; stderr warnings/errors | all (stdout/stderr split asserted) | CommandsSpec | H+U |
| 10.7 | Exit 0 success / 1 expected errors | every case | CommandsSpec/MainSpec | H+U |
| 10.8 | Exit 2 unexpected internal failures | — | SnapErrorSpec (message only) | UNTESTED → **F4** |
| 10.9 | Plain errors one line `snap: <detail>` | 14/19/20/23/25 etc. | SnapErrorSpec (full message contract) | H+U |

### §11 Required acceptance tests (meta)

| # | Clause | Harness case | Unit test | Status |
|---|---|---|---|---|
| 11.1–11.10 | Acceptance areas 1–10 | cases 01–27 cover all ten areas | — | H |
| 11.11 | Cross-language exchange (maintainers, all three impls) | n/a (single-impl workspace; §11 limits suite to one impl) | — | out of scope here |
| 11.12 | Redirected plain output, SNAP_COLOR/NO_COLOR precedence, exact terminal bytes | 28 + default-env of all cases | RenderSpec/MainSpec | H+U |
| 11.13 | MUST unit-test auto TTY/non-TTY selection for stdout and stderr independently | n/a (no PTY) | RenderSpec resolvePresentation (independent flags) | U — logic covered, but production wiring collapses streams (**F2**) |
| 11.14 | SHOULD property tests over causal patch graphs / permutations | 18 (6 association orders) | DiffSpec/OtSpec properties, ReplaySpec permutation & determinism | U (adequate) |

### §12 Out of scope — no normative clauses to test.

---

## Verdict
No blockers. The harness + unit suite covers the byte-stable contract remarkably completely (error strings, exit channels, OT/diff goldens, warning order, HTTP semantics, config precedence all doubly covered). The substantive gaps are: **(F1)** the undocumented 64M-cell diff cap that converts large valid diffs into exit-2 internal errors; **(F2)** the single-`System.console()` wiring that defeats §7.11's per-stream TTY requirement; and **(F3)** read-only commands silently accepting filenames that violate §2 path rules. Remaining items are untested-but-compliant risks (exit-2 channel, mutation ordering, loopback/default-port binding, HTTPS, empty-remote merge, long revert messages, odd filenames).

---

## Compact final message

**Status:** Review complete — no blockers found; report artifact returned above (target path `reviews/snap/05-untested-spec-coverage.md`; no write tool was available in this subagent, so the full report is included verbatim for persistence).

**Method:** Enumerated 78 normative clauses from SPEC.md §1–§11; mapped each against all 28 harness YAML cases and 15 unit suites (481 tests); inspected all 16 implementation sources for uncovered clauses.

**Counts by rank:** blocker 0 · major 1 · minor 2 · nit 7 (10 findings).

**Top 5 findings:**
1. **MAJOR — F1:** `Diff.scala:30,84-87` hard 64M-cell cap throws a defect → exit 2 `snap: internal error` for large valid text diffs/commits, violating §5's total canonical diff; untested.
2. **MINOR — F2:** `Main.scala:43` + `Commands.scala:82` use one `System.console()` flag for both streams, so §7.11's independent stdout/stderr TTY detection is not actually implemented (ANSI can leak into redirected stderr; TTY stderr can go plain).
3. **MINOR — F3:** `WorkingTree.scan` never validates path names, so `status`/`diff` silently list files with backslash/control-char names that §2 forbids; only `commit` rejects (`Commands.scala:172`).
4. **NIT — F4:** exit-code-2 defect channel (`Commands.finish`) has no executed test — only the message string is asserted.
5. **NIT — F5:** §10 mutation ordering (working files before `repository.json`) is correct in code but unverified by any fault-injection test.

Merge verdict: **OK with notes** — acceptance suite integrity is sound; recommend fixing F1 before release and F2/F3 in the next hardening round.
