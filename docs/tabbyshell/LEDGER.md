# TabbyShell Ledger

Integrator-owned record of lanes, branches, commits, gate evidence, rulings,
parked findings, and blockers. Lanes never write here; the parent updates it
after each merge/verification.

## Coverage baseline (2026-09-04, HEAD `1bbb9f1`)

Measured with sbt-scoverage 2.4.4 (temporary `project/coverage.sbt`, removed
afterwards); unit tests only (91 tests), harness runs out-of-process and does
not count.

- **Overall:** statement 54.46%, branch 48.27%
- Reports: `tabbyshell/target/scala-3.3.8/scoverage-report/{index.html,scoverage.xml}`
  (transient; deleted by `sbt clean`)

| Class | Stmts | Stmt % | Branch % |
|---|---:|---:|---:|
| Executor | 842 | 39.07 | 34.62 |
| LineParser | 565 | 68.32 | 53.99 |
| Render | 409 | 81.17 | 75.24 |
| Main | 323 | 0.00 | 0.00 |
| JsonParser | 280 | 81.43 | 72.60 |
| External | 191 | 0.00 | 0.00 |
| Csv | 133 | 87.97 | 80.65 |
| Json | 132 | 85.61 | 77.78 |
| Value | 92 | 100.00 | 100.00 |
| TabbyError | 41 | 75.61 | 62.50 |
| Parser | 26 | 100.00 | 100.00 |
| CliOptions | 8 | 0.00 | 0.00 |
| RenderOpts | 4 | 100.00 | 100.00 |
| Version | 3 | 100.00 | 100.00 |
| error ADT leaves (9 classes) | 9 | mixed | mixed |

Target: ≈80% statement coverage on production code (pure-data ADT leaves
exempt from dedicated tests; smart constructors are not).

## Coverage wave 1 (dispatched 2026-09-04)

Dispatched as async pi-subagents workflow `47fd5361-c271-4f86-b86c-8482f59d91c3`
(mission `0771c413-b6a2-45b8-b58f-c10da05c1573`), five fresh-context worker
lanes in managed worktrees, global concurrency limit 3.

One worktree lane per module, disjoint file ownership, no build-file edits,
no behavior changes. Lane gates: `compile`, `test`, `scalafmtAll;
scalafmtCheckAll`, then `sbt --client shutdown`.

| Lane key | Owned test file(s) | Production file (testability-only edits allowed) | Target | Status | Commit |
|---|---|---|---|---|---|
| `cov-executor` | `ExecutorSpec.scala` | `Executor.scala` | 39→80% | merged (`dfb6df2`) | `167d1ab` |
| `cov-main` | `MainSpec.scala` (new) | `Main.scala` | 0→60%+ | merged (`a51c7ce`) | `d1e38e3` |
| `cov-external` | `ExternalSpec.scala` (new) | `External.scala` | 0→70%+ | merged (`fceef54`) | `4b4b276` |
| `cov-parser` | `ParserSpec.scala` | none | 68→85%+ | retry lane patch applied (`780d188`) | `f193605f` |
| `cov-render-json-csv` | `RenderSpec`, `JsonSpec`, `CsvSpec`, `TabbyErrorSpec` | none | 80%+ each | merged (`6e5d07d`) | `ae6b262` |

Wave 1 notes:
- First `cov-parser` lane (`d8016d42`) hung ~9 min piping a heredoc into
  `sbt --client console` (interactive REPL never exits cleanly through a pipe);
  killed, worktree cleaned, re-dispatched as `f193605f` with a no-console rule.
- Lanes ran `sbt --client shutdown` on completion; no stray servers remained
  after integration.
- All lane branches were pruned by worktree cleanup; merged by commit hash
  (commits remained reachable).

### Rulings / parked items

- SIGINT/Ctrl-C REPL handling remains parked (needs real signal handling).
- `External.callAi` success path (live HTTP) intentionally uncovered;
  `run`/`formatWithAi` covered via injected env (`runWithEnv` seam).
- Main REPL cluster (`repl`, `readLogicalLine`, `appendHistory`,
  `printBanner`, `findBannerPath`, `goodbye`) and `Main.run` (calls
  `exit`) parked; `runScript` `catchAllCause` defect branch not
  deterministically triggerable.

## Integration log

- 2026-09-04: merged cov-executor (`dfb6df2`), cov-main (`a51c7ce`),
  cov-external (`fceef54`), cov-render-json-csv (`6e5d07d`) sequentially into
  `main`; applied cov-parser retry patch (`780d188`). Gates after final merge:
  - `sbt --client "compile; Test/compile; test; assembly; scalafmtCheckAll"`:
    **411 tests passed, 0 failed** (baseline was 91); assembly jar built.
  - Official harness: **50 passed, 0 failed** on HEAD `780d188`.
  - Coverage (sbt-scoverage 2.4.4, temporary plugin, then removed):
    statement **91.92%** (baseline 54.46%), branch **89.85%** (baseline
    48.27%) — exceeds the ≈80% target.

### Post-wave coverage (HEAD `780d188`, 411 unit tests)

| Class | Stmts | Stmt % | Branch % |
|---|---:|---:|---:|
| Executor | 842 | 97.51 | 96.58 |
| LineParser | 565 | 94.51 | 87.73 |
| Render | 409 | 95.60 | 93.33 |
| Main | 327 | 56.57 | 55.00 |
| JsonParser | 280 | 95.71 | 93.15 |
| External | 196 | 90.31 | 89.36 |
| Csv | 133 | 100.00 | 100.00 |
| Json | 132 | 97.73 | 97.22 |
| Value | 92 | 100.00 | 100.00 |
| TabbyError | 41 | 95.12 | 100.00 |
| Parser | 26 | 100.00 | 100.00 |
| CliOptions | 8 | 100.00 | 100.00 |
| remaining leaves | 9 | 100.00 | 100.00 |

Overall: **91.92% statement / 89.85% branch** — target (≈80% stmt) met.
Remaining Main gap is the interactive REPL cluster and `Main.run` exit path
(parked above); remaining External gap is the live HTTP call.

## CI setup (2026-09-04, commits `b09e76d`, `8ce0d1d`)

- Vendored an unmodified snapshot of the workshop acceptance harness into
  `harness/tabbyshell/` (`verify`, `run_tests`, `test-harness/`, `tests/`,
  `fixtures/`, `SPEC.md`; `node_modules` excluded). Rule: no edits to vendored
  files; re-copy from workshop materials to update.
- Added `.github/workflows/capstone-ci.yml`: auto-discovers every top-level
  dir with a `build.sbt`, then per project runs
  `sbt "scalafmtCheckAll; test; assembly"` and, if `harness/<project>/`
  exists, installs its npm deps and runs `run_tests --lang scala`.
  New capstones (e.g. `snap/`) are picked up automatically.
- `scripts/verify-tabbyshell.sh` now prefers the vendored harness, falling
  back to the workshop copy.
- Local validation before push: `sbt "test; assembly"` → 411/411, jar built;
  vendored `run_tests` → 50/50 passed (13.4 s).
- GitHub Actions first run on push: workflow run 33874539613 (status tracked
  separately; see monitor notes).

## Plan maintenance

- 2026-09-04: Added **Phase B½ — Plan & packet review** to
  `docs/GENERIC_CAPSTONE_PLAN.md`: after packet drafting (post Phase B
  approval), fresh-context read-only reviewer lanes validate each packet for
  scope, file-ownership disjointness, interface conformance, test-first DoD,
  and verifiability before any writer is dispatched; `fusion_validate`
  optional advisory check; materially changed packets need re-review. Skills
  map and background-execution table updated accordingly.


