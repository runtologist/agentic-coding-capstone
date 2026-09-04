# Human Reviewer Comments (2026-09-05)

Source: human code review on the merged Snap implementation (main @ 63f963a).
These enter Phase F triage alongside the E1–E5 lane findings with equal standing.

## H1 — NIO side effects not properly wrapped in ZIO
> There are side effects using nio, that are not wrapped in ZIO, e.g. in
> WorkingTree. Wrap them properly or use zio.nio. That would also allow nicer
> code in other places, where we basically have java code wrapped in
> ZIO.attempt.

Interpretation: `WorkingTree` (and similar modules: `RepoIo`, `Config`) call
`java.nio.file` APIs inside coarse `ZIO.attemptBlocking` blocks. Prefer
`zio-nio` (or fine-grained ZIO-wrapped NIO calls) so effect boundaries are
explicit and the code reads as idiomatic ZIO rather than Java-in-attempt.

## H2 — while loops should be idiomatic Scala
> There are several while loops that I would probably rewrite
> .zipWithIndex.foreach, to tail recursion, or a foldLeft.

Interpretation: `var`-driven `while` loops (noted by E3 as "deliberate
hot-loop style" in Diff/Replay/Json/Model) should be rewritten where
practical to idiomatic functional style — `zipWithIndex.foreach`,
`@tailrec` recursion, or `foldLeft` — without changing observable behavior.
Performance-sensitive spots (Diff DP inner loop) may keep imperative style
only if justified and documented.

## H3 — PositiveSafeInteger as opaque type
> PositiveSafeInteger could be extracted into an opaque type with smart
> constructor

Interpretation: today revision/count values flow as `Long` validated by
`Model.positiveSafeInteger`. Extract an opaque type (e.g.
`PositiveSafeInteger` with a smart constructor returning
`Either[SnapError, PositiveSafeInteger]`) so invalid values are
unrepresentable downstream; parse at the boundary (JSON decode, CLI parse),
use the refined type in `Version`, edit-op counts, revisions.
