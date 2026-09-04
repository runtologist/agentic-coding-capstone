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

One worktree lane per module, disjoint file ownership, no build-file edits,
no behavior changes. Lane gates: `compile`, `test`, `scalafmtAll;
scalafmtCheckAll`, then `sbt --client shutdown`.

| Lane key | Owned test file(s) | Production file (testability-only edits allowed) | Status | Commit |
|---|---|---|---|---|
| `cov-executor` | `ExecutorSpec.scala` | `Executor.scala` | dispatched | — |
| `cov-main` | `MainSpec.scala` (new) | `Main.scala` | dispatched | — |
| `cov-external` | `ExternalSpec.scala` (new) | `External.scala` | dispatched | — |
| `cov-specs` | `ParserSpec`, `JsonSpec`, `CsvSpec`, `RenderSpec`, `TabbyErrorSpec` | none | dispatched | — |

### Rulings / parked items

- SIGINT/Ctrl-C REPL handling remains parked (needs real signal handling).
- `External.run` live process execution may be left partially uncovered if
  tests would become environment-dependent; record whatever the lane skips.

## Integration log

- (pending) merge lanes sequentially into `main`; full gates + fresh
  scoverage report after final merge; record before/after numbers here.
