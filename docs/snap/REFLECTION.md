# Snap Capstone — Reflection (§6.5)

## What the agent accomplished autonomously

1. **Intake & Contract (Phase 1):** Read SPEC.md, all 28 YAML tests, and TEST-HARNESS.md; produced CONTRACT.md with exact output formats, exit codes, and ambiguity rulings.
2. **Architecture (Phase 2):** Derived module decomposition from spec structure, chose ZIO for effect management, zio-json for parsing, zio-http for HTTP. Designed file-ownership table enabling safe parallelism.
3. **Foundation (Phase A):** Scaffolded sbt project, defined Model ADT, SnapError hierarchy, and shared interfaces.
4. **Implementation (Phase C):** Dispatched 5 parallel lanes (L1b, L2, L3, L4, L5, L6, L7 + J1) across worktrees, each producing green tests independently.
5. **Integration (Phase D):** Sequential merges with full gate after each; resolved one integration issue (Codec.checkPrefixConflicts needed to exclude deleted paths for file↔dir transitions).
6. **Adversarial review (Phase E):** Dispatched 5 review lanes; triaged findings using receiving-code-review discipline.
7. **Fix round (Phase F):** Dispatched 4 fix lanes addressing all accepted findings; all merged green.

## What required human intervention

1. **CI failure (test 28 timeout):** The eager JNU re-exec doubled JVM startup on every invocation under LANG=C. Human reported the failure; the fix (lazy re-exec) was designed and implemented autonomously.
2. **Human code review comments:** Three style/architecture suggestions (zio-nio wrapping, while-loop idiom, PositiveSafeInteger opaque type) were provided by the human reviewer and incorporated as fix-lane requirements.
3. **Harness immutability constraint:** Human clarified that harness/ must never be modified; the UTF-8 fix had to be purely in application code.

## Key lessons

- **Eager environment fixes are dangerous:** The initial JNU re-exec fired on every invocation, breaking timing-sensitive tests. Lazy, condition-triggered re-exec is the correct pattern.
- **Parallel lanes need disjoint file ownership:** All successful lanes touched non-overlapping files; the one integration issue (Codec prefix check) was a semantic interaction, not a textual conflict.
- **Fork-context subagents derail:** Early fork-context workers replayed stale context and produced no useful output. Fresh-context workers with self-contained prompts worked reliably.
- **Review lanes find what unit tests miss:** The 64M diff cap (E5-F1) and HEAD Content-Length issue (E4-P2) were invisible to the harness but caught by review.

## Final metrics

- Unit tests: 530 passing
- Acceptance harness: 28/28
- Statement coverage: 90.43% (target: 80%)
- Branch coverage: 85.62%
