# F-diff — Remove 64M-cell diff cap via linear-space canonical diff (E5-F1, MAJOR)

**Lane:** F-diff · **Worktree:** `/tmp/snap-fa` · **Branch:** `task/11-diff-totality` (from `main` @ d593590)
**Owned files (modify ONLY these):** `snap/src/main/scala/snap/Diff.scala`, `snap/src/test/scala/snap/DiffSpec.scala`
**Rank:** Major (only reachable trigger of the exit-2 "internal error" channel on legitimate input).

## Problem

SPEC §5 defines the canonical token diff recurrence as TOTAL over any old/new token sequences; alternative
algorithms are allowed "only if it produces the same script". SPEC §10 reserves exit 2 for *unexpected*
internal failures. Current code (`Diff.scala:30` `MaxDiffCells = 64000000L`, `Diff.scala:84-87` throws
`IllegalStateException`) turns a legitimate ~8000×8000-line full rewrite into `snap: internal error` / exit 2
via the ZIO defect channel. A predictable resource budget is not an "unexpected internal failure".

## Required fix

1. Replace the dense DP table with a **linear-space Hirschberg** (divide & conquer over the same recurrence).
   It MUST produce a byte-identical edit script to the current implementation for all inputs, including:
   - deletion-on-tie (`del <= ins` preference),
   - forced diagonal on equal tokens,
   - safe equal-prefix/equal-suffix trimming,
   - coalescing of adjacent same-kind ops,
   - empty-script semantics (empty-script-creates-empty-file ruling).
2. Remove the `MaxDiffCells` throw entirely. The public signature(s) consumed by `Commands.buildChanges` and
   `Replay.integratePatch` MUST NOT change; no other file may need editing. No `IllegalStateException` may
   remain reachable for size reasons.
3. Keep the algorithm deterministic — no randomness, no map-iteration-order dependence.
4. H2 (human review): while rewriting, prefer idiomatic Scala (`@tailrec`, `foldLeft`, `zipWithIndex.foreach`)
   for loops in Diff.scala where it does not change behavior or asymptotic performance. If an inner loop must
   stay imperative for performance, keep it and add a one-line justification comment.

## TDD (red-green)

1. FIRST add failing tests in DiffSpec:
   - **Large-diff test (red today):** old = 8100 distinct lines, new = 8100 completely different lines
     (8100² = 65.6M > 64M old cap → currently throws). Assert: no exception, script applies cleanly
     (`Model.applyEdit` round-trips old→new), script is canonical (coalesced, no adjacent same-kind).
     Keep the size just above the old cap to bound runtime; if the test is slow, mark it accordingly but keep it.
   - **Equivalence property tests:** for randomized token vectors (lengths 0..120 over tiny alphabets like
     ["a","b"] to force ties/repeats, plus cases with repeated equal runs), assert the new implementation's
     script equals the existing naive memoized reference diff already used in DiffSpec (or reconstruct the
     naive reference from the §5 recurrence inside the test), and that apply(old, script) == new.
   - Keep all existing goldens/tie/canonicality tests unchanged and passing.
2. Run `sbt --client test` — capture red (large-diff test throws today).
3. Implement Hirschberg; rerun until green.

## Gates (all must pass before commit)

```bash
source scripts/env.sh
cd snap
sbt --client "compile; test; assembly; scalafmtCheckAll"
sbt --client shutdown
cd ..
bash harness/snap/run_tests --lang scala --implementation-root "$PWD/snap"   # expect 28/28 (tests 05/06/26 pin diff goldens)
```

## Safety rules

- Work ONLY inside `/tmp/snap-fa`. Never modify anything outside it except `git push origin task/11-diff-totality`.
- No `rm -rf`/`mv` against paths outside your worktree; no destructive git commands. NEVER touch `harness/`.
- Do NOT edit any file outside your owned list (Model.scala is being changed by a parallel lane — code
  against its CURRENT public API).
- Git identity: `Snap dev <capstone-dev@local>`; NO Co-Authored-By/AI trailers.
- `sbt --client` only; `sbt --client shutdown` at the end and after any sbt config change (do not change build config).

## Finish

Commit on `task/11-diff-totality`, push, then report: lane id, status, branch, commit SHAs, files changed,
tests added/total, gate evidence, algorithm note (how tie-break parity was proven), parked items, risks.
