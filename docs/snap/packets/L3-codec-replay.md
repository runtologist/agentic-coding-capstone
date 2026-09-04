# Packet L3 — Codec (semantic validation) + Replay (deterministic integration)

**Branch:** `task/06-codec-replay` (from `develop`)
**Wave:** 3 (sequential; only lane)
**Depends on:** L1 Model/SnapError ✅ · J1 Json thin codecs ✅ · L2 Diff/Ot ✅ · L6 Render (ReplayWarning rendering) ✅

## Goal

Implement the two pure, side-effect-free modules that sit between the JSON
boundary and the I/O / command layers:

1. **`Codec.scala`** — full SPEC §4.5 semantic validation of a decoded
   `Model.Repository` (and helper routines used by commands).
2. **`Replay.scala`** — deterministic patch integration: canonical ordering
   (§6.1), per-patch integration with OT and path-level rules (§6.2–§6.4),
   materialization to a `Tree`, and `ReplayWarning` collection.

## Files owned (exclusive)
- `snap/src/main/scala/snap/Codec.scala` (new)
- `snap/src/main/scala/snap/Replay.scala` (new)
- `snap/src/test/scala/snap/CodecSpec.scala` (new)
- `snap/src/test/scala/snap/ReplaySpec.scala` (new)

**Do NOT modify:** Model.scala, SnapError.scala, Json.scala, Diff.scala,
Ot.scala, Cli.scala, Render.scala, build.sbt, docs, harness. If a change is
needed there, STOP and report.

## Safety rules (hard)
- Work ONLY under `snap/` in your assigned worktree. No `rm -rf`/`mv` outside
  it. No destructive git commands. If a path or file seems missing, STOP and
  report — never "clean up."
- `sbt --client`; `sbt --client shutdown` after your final gate. No interactive
  sbt.

## Read first
- `docs/snap/CONTRACT.md` §7 (pinned error strings), §11 (replay/OT semantics,
  test 09/10/11/16/17/18/21/22 behaviors), §13 (commit/revert mechanics — for
  context only; commands are L7)
- `harness/snap/SPEC.md` §4.1–§4.5, §6.1–§6.5
- `harness/snap/tests/15-repository-validation.yaml`,
  `23-strict-validation-matrix.yaml`, `27-history-canonicality.yaml`,
  `16-dot-collision.yaml`, `17-concurrent-creates.yaml`,
  `18-three-way-convergence.yaml`, `20-dirty-merge.yaml`,
  `21-version-algebra.yaml`, `22-ot-matrix.yaml`
- Frozen source: `Model.scala` (types + helpers), `Json.scala` (parse API),
  `Diff.scala`, `Ot.scala`, `SnapError.scala` (all error cases already defined)

---

## What the JSON layer already enforces (J1 — do NOT re-check, but rely on)

`Json.parseRepository` already rejects: malformed JSON, trailing content,
duplicate object keys (any depth), unknown fields (repo/patch/change levels),
non-integer or non-positive-safe-integer revisions/counts, edit ops without
exactly one key, empty insert arrays, empty `changes` arrays, empty/invalid
messages (`validateStoredMessage`), invalid paths (`validatePath`),
non-canonical base64 (`decodeCanonicalBase64`), and per-token insert validity
+ insert-sequence canonicality (`NonCanonicalTokens`). Codec validates the
decoded typed value: history shape, ordering, closure, and change-vs-base
semantics.

## Part A — `Replay.scala`

### Public surface
```scala
object Replay {
  /** §6.1: topological integration order.
    * Returns patches sorted so that every patch appears after its base.
    * Ready-set selection: patch is ready when all dots in its base are
    * already integrated. Tie-break: (1) snapOrder of result versions,
    * (2) unsigned-UTF-8 author, (3) numeric revision.
    * Left(CyclicOrIncompleteHistory) if no ready patch remains before all
    * are integrated. */
  def integrationOrder(patches: Vector[Patch]): Either[SnapError, Vector[Patch]]

  /** §6.1–§6.4: materialize the tree at `target` from `patches`.
    * Selects patches (c,n) with n <= target[c]; validates closure;
    * integrates in canonical order; returns (tree, sorted unique warnings). */
  def materialize(patches: Vector[Patch], target: Version)
      : Either[SnapError, (Model.Tree, Vector[ReplayWarning])]
}
```

### Integration of one patch (§6.2) — detailed algorithm
Given incoming patch `P`, its exact base tree `B` (materialized from the
selected patches up to `P.base`), and the current canonical tree `C`:

1. **Namespace resolution (whole patch, first):**
   - `S` = paths `P` makes present (text-create/put-create, or text/put
     result existing as a file).
   - `C'` = `C` minus every path that `P` authors as a deletion.
   - For each `p ∈ S`, if any path `q` in `C'` is a **proper ancestor or
     proper descendant** of `p` (use `Model.isProperAncestor`):
     mark `p` for installation as its authored result `T`; mark every such
     conflicting `q` for removal; emit `ReplayWarning.NamespaceWins(q)`.
   - These decisions override the per-path rules below. The authored result
     set is prefix-free within one patch, so two `S` paths never conflict.
     Duplicate removals/warnings collapse to one.

2. **Per-path rules** (for paths NOT settled by namespace):
   Let `T` = authored result of applying `P`'s change to `B` at that path.
   - Rule 1: `B == C` at path → apply authored change directly.
   - Rule 2: `C == T` at path → keep unchanged (identical concurrent change
     collapses; no warning).
   - Rule 3: `B`, `C`, `T` all text AND `P`'s change is text →
     `Q = Diff.canonicalDiff(tokens(B), tokens(C))`;
     `P' = Ot.transform(P.edit, Q)`;
     apply `P'` to `tokens(C)` via `Model.applyEdit`. No warning.
   - Rule 4 (path-level fallbacks, §6.4), resolved in this exact order:
     1. `C == T` → keep `C`, no warning. *(already handled by Rule 2)*
     2. `T` absent (incoming delete) → delete wins → `ReplayWarning.DeleteWins`.
     3. `B` present, `C` absent (earlier concurrent delete) →
        `ReplayWarning.DeleteWins`.
     4. `B` absent, `C` present, `T` present (incoming later create) →
        `ReplayWarning.LaterCreateWins`.
     5. Incoming change is `put` → `ReplayWarning.LaterPutWins`.
     6. Incoming text vs non-text `C` → keep `C` → `ReplayWarning.PutWins`.

3. **Apply all path changes together** to form the next canonical tree.
   Installation semantics: remove files that block required directories,
   create directories, write target bytes, remove newly-empty directories
   (at the in-memory `Tree` level: just produce the correct `Map`).

4. **Warnings:** collect unique `(path, reason)` pairs across the whole
   replay; sort by `ReplayWarning.byPathThenReason` (unsigned-UTF-8 path,
   then reason).

### Guarantees (§6.5)
- Same valid patch set + frontier ⇒ same bytes + same warning set.
- Re-merging the same history is a no-op (no new warnings).

---

## Part B — `Codec.scala`

### Public surface (suggested; adjust naming, keep semantics)
```scala
object Codec {
  /** Full §4.5 validation. Called on every repository load.
    * Returns the validated Repository or the FIRST error encountered. */
  def validateRepository(repo: Model.Repository): Either[SnapError, Unit]

  /** Validate a single patch's changes against its materialized base tree.
    * Used inside validateRepository and by commit. */
  def validateChangesAgainstBase(
      patch: Patch, baseTree: Model.Tree): Either[SnapError, Unit]

  /** Cross-repo dot-collision check (test 16, 26). */
  def checkCollision(local: Vector[Patch], remote: Vector[Patch])
      : Either[SnapError, Unit]

  /** Compute the new frontier after importing `incoming` patches
    * into a repo with `localFrontier`. */
  def joinedFrontier(localFrontier: Version, incoming: Vector[Patch]): Version
}
```

### §4.5 validation — exact order (first failure wins)

Run these steps sequentially over the `Repository` already decoded by
`Json.parseRepository` (structural/type/unknown-field/dup-key/base64/
integer-literal errors are ALREADY caught at the JSON layer — do not
re-check them):

1. **Frontier canonical sort:** `frontier.components` must be sorted by
   (unsigned-UTF-8 author, revision). Error → `NonCanonicalFrontier(found,
   expected)` rendering `.*canonical.*` (test 23).

2. **Patch sorting:** `patches` must be sorted by (author unsigned-UTF-8,
   then numeric revision). Error → any `snap: …` (test 27).

3. **Change sorting:** within each patch, `changes` sorted by path
   (unsigned-UTF-8). At most one change per path. Error → test 27.

4. **Dot consistency:** for every patch, `revision == base[author] + 1`.
   If `author` is absent from `base`, then `revision` must be 1.
   Error → test 27.

5. **One value per dot:** no two patches share `(author, revision)` unless
   structurally identical (`Patch.sameValue`). Non-identical → `PatchCollision`
   rendering `patch collision: <author> revision <n>` (test 16).

6. **Contiguity:** for each author appearing anywhere, revisions
   1, 2, …, max must all be present. Missing rev `k` →
   `MissingPatch(author, k)` rendering `missing <author> revision <k>`
   (test 15: `missing a@x`).

7. **Base closure:** every dot referenced in every `base` must exist in
   `patches`. Missing → `MissingPatch` (or `UnreachablePatch` depending on
   context; test 15 gap case expects `missing a@x`).

8. **Frontier closure / reachability:** every patch must be in the causal
   closure of `frontier` (i.e., reachable by walking bases back from
   frontier dots). A patch not reachable →
   `UnreachablePatch(author, revision)` rendering
   `unreachable patch: <author> revision <n>` (test 23).
   Frontier must also be exactly the set of maximal dots (no frontier
   component without a matching patch; no patch dot that strictly exceeds
   frontier).

9. **Acyclicity:** the base-dependency graph must be a DAG.
   Cycle → `CyclicOrIncompleteHistory` (test 15).

10. **Message / changes emptiness:** `message` non-empty →
    `EmptyField("…", "message")` rendering `…message is empty` (test 23).
    `changes` non-empty → `EmptyField` rendering `…changes is empty`.
    *(Note: Json layer may already reject these structurally; if so, Codec
    re-checks are harmless but keep them for defense-in-depth.)*

11. **Edit-script shape (per text change):**
    - Exactly one key per op → `EditOpWrongArity` (`must have one operation`).
    - Counts positive safe integers → `NotPositiveSafeInteger`
      (`positive safe integer`).
    - Insert non-empty → `EmptyField(scope, "insert")` (`insert is empty`).
    - No adjacent same-kind ops → `AdjacentSameKindOps(kind)`
      (`adjacent insert` / `adjacent retain` / `adjacent delete`) (test 15).
    *(Much of this is enforced by Json decoding into the typed EditOp ADT;
    Codec adds the adjacent-same-kind and canonicality checks.)*

12. **Insert-token canonicality:** every insert token must satisfy
    `Model.isValidInsertToken`; the sequence must satisfy
    `Model.isCanonicalTokenSeq` (every non-final token ends in `\n`; no token
    contains `\n` before its final byte). Error → `NonCanonicalTokens(path)`
    (test 27).

13. **Path validity:** every change path must pass `Model.validatePath`
    (non-empty UTF-8, `/` separators, no control chars, no `\`, no empty/
    `.`/`..` segments, first segment ≠ `.snap`). Error →
    `InvalidRepoPath(path, reason)` rendering `path is invalid` (test 15).

14. **Prefix conflicts within one patch:** two change paths where one is a
    proper ancestor of the other → `TreePathsConflict(path)` rendering
    `tree paths conflict` (test 15).

15. **Change-vs-base checks (requires Replay):** for each patch in
    integration order, materialize its exact base tree `B` via
    `Replay.materialize(patches-so-far, patch.base)`, then:
    - text-create / put-create: path must be **absent** in `B` →
      `CreateOfPresentPath(path)` (test 27).
    - text-edit / put-replace / delete: path must be **present** in `B` →
      `DeleteOfAbsentPath(path)` rendering `delete of absent path: <path>`
      (test 23).
    - text change over a **non-text (binary) base** →
      `TextOverBinaryBase(path)` (test 27).
    - edit script must **consume exactly** the old token count →
      under-consumption: `EditNotConsuming(path)` (`does not consume old
      content`, test 15); over-consumption: `EditOverconsumes(path)`
      (`consumes beyond old content`, test 23).
    - applying the edit must produce a **canonical token sequence** →
      `NonCanonicalTokens(path)`.
    - **no-op change:** result identical to base content →
      `NoOpChange(path)` (`no-op change`, test 15). For `put`, compare
      decoded bytes; for `text`, compare resulting bytes.

16. **Deterministic replay of declared frontier:** finally,
    `Replay.materialize(patches, frontier)` must succeed (this catches any
    residual inconsistency). Failure → `InternalError` or the specific
    `SnapError` from replay.

### `checkCollision` (test 16, 26)
For every `(author, revision)` dot present in BOTH local and remote patches:
if `Patch.sameValue` is false → `Left(PatchCollision(author, revision))`.
Must be called BEFORE any local mutation (merge/diff --repo).

### `joinedFrontier`
`Version.join(localFrontier, incomingFrontier)` where incoming frontier is
the componentwise max over all imported patch results.

---

## Part C — Tests (TDD: red first)

### `CodecSpec.scala` (≥35 tests)
Pin every row of the CONTRACT §7 table that is Codec-level:
- Frontier not canonically sorted → `NonCanonicalFrontier` (regex `.*canonical.*`)
- Patches unsorted → error
- Changes unsorted / duplicate path in one patch → error
- `revision ≠ base[author]+1` → error
- Dot collision (same dot, different values) → `PatchCollision` with exact
  `patch collision: a@x revision 1`
- Dot duplicate but structurally equal → OK (no collision)
- Gap in revisions → `MissingPatch` (`missing a@x`)
- Unreachable patch → `UnreachablePatch` (`unreachable patch:`)
- Cycle → `CyclicOrIncompleteHistory`
- Adjacent same-kind ops → `AdjacentSameKindOps` (`adjacent insert`)
- Non-canonical insert tokens → `NonCanonicalTokens`
- Invalid path `.snap/secret` → `InvalidRepoPath` (`path is invalid`)
- Prefix conflict `a` + `a/b` → `TreePathsConflict`
- Delete of absent path → `DeleteOfAbsentPath` (`delete of absent path: f`)
- Create of present path → `CreateOfPresentPath`
- Text over binary base → `TextOverBinaryBase`
- Edit under-consumes → `EditNotConsuming` (`does not consume old content`)
- Edit over-consumes → `EditOverconsumes` (`consumes beyond old content`)
- No-op put (same bytes) → `NoOpChange`
- Empty message / empty changes → `EmptyField`
- Valid repositories pass cleanly (single patch, multi-author, multi-revision)

### `ReplaySpec.scala` (≥30 tests)
- Empty repo → empty tree, no warnings
- Single text-create patch → tree has file
- Sequential single-author chain (3 revisions) → correct tree
- **Test 18 three-way:** base `start\nend\n`; a inserts `A\n` after start;
  b inserts `B\n` after start; c deletes `start\n`. Canonical order c→b→a.
  Final text `B\nA\nend\n`, **zero warnings**. Also verify all 6 merge
  association orders give the same result.
- **Test 22 OT matrix** (base `0\n1\n2\n3\n4\n`): all four scenarios give
  exact results `0\n3\n4\n`, `A\n0\nB\n3\n4\nTAIL\n`, `0\n2\n3\n4\nA\n`,
  `0\nB\n2\n3\n4\n`, zero warnings.
- **Test 10 whole-file rules:** delete-wins, put-wins, later-put-wins,
  identical-no-warning; exact final bytes (delete.txt gone,
  incompatible.txt = bob's binary, later-put.txt = alice's bytes,
  identical.txt = `same\n`); warnings sorted by path then reason.
- **Test 17 concurrent create:** both orders → later-create-wins warning,
  winner content is the canonically later author's bytes.
- **Test 11 namespace:** file `a` vs `a/b`; both directions → namespace-wins
  warning, correct winner installed, loser removed.
- **Test 09 line OT:** concurrent text edits on same file → no warnings,
  merged text `base\nright\nleft\n` (bob's line first — canonically earlier).
- **Test 21:** merge `(a@x->2)` with `(a@x->1,b@x->2)` → frontier
  `(a@x->2,b@x->2)`; final text `base\nB1\nB2\nA2\n`.
- Determinism: replaying the same patches twice → identical tree + warnings.
- `integrationOrder` returns patches in canonical order; cycle → Left.
- `materialize` at a mid-history version (not frontier) → correct partial tree.

---

## Definition of done

1. `sbt --client shutdown` (ignore failure), then
   `source scripts/env.sh && cd snap && sbt --client "compile; test; assembly; scalafmtCheckAll"`
   all green; then `sbt --client shutdown`.
2. All pre-existing tests (Model, SnapError, Json, Cli, Render, Diff, Ot —
   253 today) still pass; add ≥65 new tests.
3. Only the four owned files created.
4. Commit on `task/06-codec-replay`, push:
   `git push -u origin task/06-codec-replay`. No Co-Authored-By trailers.
5. Final report: files changed, test counts, gate tails, SPEC ambiguities you
   resolved (state the ruling), deviations with justification, and integration
   notes for L4 (WorkingTree/RepoIo/Config) and L7 (Commands/Main) on calling
   Codec + Replay.
