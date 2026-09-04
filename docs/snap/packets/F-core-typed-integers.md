# Task F-core: Typed integers + core idiom pass (wave 2a)

**Lane:** F-core · **Branch:** `task/14-core-typed-integers` (from current `main` HEAD)
**Worktree:** `/private/tmp/snap-fb`
**Owned files (modify ONLY these):**

- `snap/src/main/scala/snap/Model.scala`
- `snap/src/main/scala/snap/Json.scala`
- `snap/src/main/scala/snap/Codec.scala`
- `snap/src/main/scala/snap/Ot.scala`
- `snap/src/main/scala/snap/Replay.scala`
- `snap/src/main/scala/snap/SnapError.scala`
- `snap/src/test/scala/snap/ModelSpec.scala`
- `snap/src/test/scala/snap/JsonSpec.scala`
- `snap/src/test/scala/snap/CodecSpec.scala`
- `snap/src/test/scala/snap/OtSpec.scala`
- `snap/src/test/scala/snap/ReplaySpec.scala`
- `snap/src/test/scala/snap/SnapErrorSpec.scala`

**Do NOT touch:** Main.scala, Diff.scala, WorkingTree.scala, RepoIo.scala, Config.scala,
HttpServe.scala, HttpFetch.scala, Commands.scala, Cli.scala, Render.scala (other lanes own
them — Diff/WorkingTree/Http are being edited in wave 1 concurrently; Commands/Cli/Render come
in wave 2b). Nothing under `harness/`.

## Context

Wave 2a of Phase F, running concurrently with wave 1 (F-utf8: Main.scala; F-diff: Diff.scala;
F-io-http: WorkingTree/RepoIo/Config/HttpServe/HttpFetch). None of those files overlap with yours.
Branch from current `main` HEAD. Because F-diff is concurrently rewriting Diff.scala against the
CURRENT Model API, you must keep every public type and signature consumed by Diff.scala
(EditOp shape, tokenize, applyEdit, tokensIn, etc.) source-compatible.

## Findings to address

### H3 — `PositiveSafeInteger` opaque type (human reviewer, primary ask)
> "PositiveSafeInteger could be extracted into an opaque type with smart constructor."

Currently `Model.positiveSafeInteger(bd: JBigDecimal, context: String): Either[SnapError, Long]`
validates at parse and hands a raw `Long` downstream, so invalid values are merely checked, not
unrepresentable. Introduce in Model:

```scala
opaque type PositiveSafeInteger = Long  // or a final wrapper — your call, justify in the report
object PositiveSafeInteger {
  val MaxValue: Long = 9007199254740991L
  def from(...): Either[SnapError, PositiveSafeInteger]   // smart constructor; preserves exact existing error messages/contexts
  extension (p: PositiveSafeInteger) def toLong: Long / render etc.
}
```

Requirements:
- Parse at the boundary: all JSON decode sites (revisions in frontier/patches/bases, edit-op
  counts) and CLI version parsing go through the smart constructor.
- **Keep existing public signatures stable where non-owned modules consume them.** Version's
  component accessors (`get`, `ids`, `render`, join/compare/snapOrder) must keep behaving
  identically. Where a signature change would ripple into files you don't own, validate via the
  smart constructor at the boundary and unwrap to `Long` for the domain model — note any deeper
  threading you skipped in the report.
- Every existing pinned error message stays byte-identical (SnapErrorSpec + harness pin them).

### E1-S1 — revision overflow helper (pairs with H3)
Add `Model.nextRevision(current: Long): Either[SnapError, ...]` (naming to your judgment) that
fails with a typed SnapError when incrementing would exceed `9007199254740991`. Reuse an existing
error variant/message shape if one fits (`NotPositiveSafeInteger` pattern); only add a new
SnapError variant if you must, and pin its exact message in SnapErrorSpec. Do NOT wire it into
Commands.scala (wave 2b owns that); just export and unit-test the helper. Document in your report
that Commands wiring lands in F-cmd.

### H2 — while-loop idiom pass in owned files (human reviewer)
> "several while loops that I would probably rewrite .zipWithIndex.foreach, to tail recursion, or a foldLeft."

Current while-loop counts: Model 6, Json 17, Codec 7, Ot 1, Replay 6. Rewrite where it genuinely
improves idiomaticity **without changing observable behavior or asymptotic performance**. Rules:
- Early-exit validation loops (`while (it.hasNext && failure.isEmpty)`) map well to `foldLeft`
  with short-circuit semantics or `@tailrec` — prefer those.
- Hot character-scanner loops in Json.scala (e.g. the escape/number scanners): rewrite only if the
  result is clearly cleaner AND equally fast; a documented kept loop is acceptable. State in the
  report which loops you kept and why.
- Behavior is frozen: all existing suite outputs, error messages, and ordering must be identical.

### E3-N1 — remove the guarded cast in Replay.scala
`Replay.scala:~282` uses `change.asInstanceOf[Change.Text]` guarded by a nearby `isTextChange`
check. Restructure into a pattern match so the cast disappears.

## TDD

1. Red first: PositiveSafeInteger boundary tests (accepts 1 and 9007199254740991; rejects 0,
   negatives, 9007199254740992, and the existing non-integer cases with the SAME messages);
   `nextRevision` at max → Left, below max → Right. Capture red output.
2. H2/E3-N1 are behavior-preserving refactors: full suite green before and after; add tests only
   where a refactored loop lacks coverage.
3. Never weaken or delete existing assertions.

## Gates (all must pass before commit)

```bash
source scripts/env.sh && cd snap
sbt --client "compile; test; assembly; scalafmtCheckAll"
sbt --client shutdown
cd ..
bash harness/snap/run_tests --lang scala --implementation-root "$PWD/snap"   # expect 28/28
```

## Safety rules (strict)

- Work ONLY inside your assigned worktree (plus `git push origin task/14-core-typed-integers`).
  Never rm -rf/mv outside it; no destructive git ops. NEVER touch `harness/`.
- Git identity: `Snap dev <capstone-dev@local>`; NO Co-Authored-By or AI trailers.
- `sbt --client` only; `sbt --client shutdown` after any build-config change and at the end.

## Finish

Commit on `task/14-core-typed-integers`, push to origin, then report (<400 words): lane id,
status, branch, commit SHAs, files changed, tests added/total, gate evidence (unit count +
harness N/28), opaque-type design summary (what it wraps, where it's enforced, what you chose NOT
to re-thread and why), loops kept vs rewritten, parked items, risks.
