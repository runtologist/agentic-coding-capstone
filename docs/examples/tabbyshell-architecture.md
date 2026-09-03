# Case study: TabbyShell module split (Scala + ZIO)

This is an **example only**, not a default template. The split below was
derived from TabbyShell's spec and delivery interface (a CLI that reads
pipelines, executes them, and renders typed values). A different capstone
should derive its own architecture from its own spec (see
`../GENERIC_CAPSTONE_PLAN.md`, §2).

## Module map

All sources live in package `tabbyshell` under
`tabbyshell/src/main/scala/tabbyshell/`.

| Module | Responsibility | Notes |
|---|---|---|
| `Main.scala` | Process entry point (`ZIOAppDefault`), CLI flag parsing, environment/state bootstrap, mode dispatch (`--version`, `--eval`, `--eval-file`, REPL, stdin script), exit codes | Owns all top-level effects: `Console` for stdout/stderr, `System` for env, `Clock` for time, `exit(code)` for the process exit status |
| `Parser.scala` | Line-continuation joining, tokenizing, parsing one logical line into a `Pipeline` | Pure; returns `Either[TabbyError, Option[Pipeline]]`. Token/AST ADTs: `Token`, `Literal`, `Arg`, `Command`, `Pipeline` |
| `Value.scala` | Domain value model (`Value` ADT) and `ShellState` | `VRecord`/`VTable` constructors are `private[tabbyshell]`; all construction goes through `Value.record`/`Value.table` (validated, `Either[ConstructionError, _]`) or `recordTrusted`/`tableTrusted` (internally guaranteed valid) |
| `Executor.scala` | Pipeline interpretation and builtins: `ls`, `open`, `cat`, `pwd`, `cd`, `where`, `select`, `sort-by`, `first`, `last`, `length`, `get`, `to`, `save` | Pure command dispatch; side effects wrapped in `ZIO` with errors mapped to `TabbyError`; unknown heads dispatch to `External.run` |
| `External.scala` | External-process execution and AI formatting via OpenRouter, with graceful fallback | All AI/network failures fall back to `Str(stdout)` + dim stderr note per spec §5.15 |
| `Render.scala` | Pure rendering of `Value` to terminal text | `RenderOpts(color, maxColWidth, now)` injects display context; no clock reads inside — determinism via `ShellState.now` / `TABBY_NOW` |
| `Json.scala` | JSON parsing/pretty-printing for `open *.json` and `to json` | Duplicate object keys: last wins; uniform object arrays collapse to `VTable` via `Value.tableFromUniformRecords` |
| `Csv.scala` | CSV parsing/serialization for `open *.csv` and `to csv` | Blank lines produce no row; ragged rows are rejected via `Value.table` instead of silently padded |
| `TabbyError.scala` | Sealed error ADT with spec-mandated message formats (`Parse`, `TypeMismatch`, `MissingColumn`, `MissingArg`, `BadArg`, `IoError`, `ExternalFailed`) | Single error channel for the whole pipeline |
| `Version.scala` | Version string constant | Kept separate so tests and `--version` share one source of truth |

## How this maps to the required architectural properties

1. **Parse at the boundary** — CLI args (`parseArgs`), scripts (`Parser`),
   JSON/CSV files (`Json`/`Csv`), env vars (`TABBY_NOW` validation) all
   become typed values (`CliOptions`, `Pipeline`, `Value`, `Long`) before
   domain logic runs.
2. **Pure domain logic** — `Parser`, `Render`, `Json`, `Csv`, `Value` are
   pure and directly unit-testable.
3. **Side effects behind ZIO** — filesystem/process I/O in `Executor` and
   `External` use `ZIO.attemptBlocking` and map throwables into `TabbyError`;
   console I/O uses `zio.Console`; env uses `zio.System`; time uses
   `zio.Clock`.
4. **Deterministic output** — `now` is injected through `ShellState`
   (from `TABBY_NOW` or `Clock`) and carried in `RenderOpts`; renderers never
   read the clock themselves.
5. **Sealed error ADT** — every expected failure is a `TabbyError` with the
   exact spec message format; unexpected throwables surface as defects and
   map to exit code 2 via `catchAllCause` in `Main.runScript`.
6. **Independent ownership** — each module has frozen interfaces, so lanes
   like "Csv invariants", "Render locale", and "Parser/Main strictness" can
   be implemented and verified independently and merged without conflicts.

## Delivery contract enforced on every change

- `sbt --client "compile; test; assembly"` green
- `sbt --client scalafmtCheckAll` green
- `capstones/tabbyshell/verify --lang scala --implementation-root <repo>/tabbyshell` → 50/50
- Exit codes verified on the assembled fat jar (`java -jar`), since
  `ZIOAppDefault` requires `exit(code)` (not a returned `ExitCode`) and the
  harness runs the jar directly.
