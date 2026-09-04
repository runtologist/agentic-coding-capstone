# Generic Capstone Plan (Scala)

This plan is language-agnostic in structure but tuned for our Scala/ZIO
implementation. It is designed to be executed with parallel subagents while
keeping one integrator responsible for merging and quality gates.

Use available skills explicitly at each stage (Superpowers and pi-subagents).
If a named skill is unavailable, follow its written process manually and record
the fallback in the project ledger.

Long-running commands (sbt gate suites, official harness) and multi-model
review/fusion checks run as background tasks in the orchestrator session so
the parent keeps making independent progress; see "Background execution"
below.

## 0. Non-negotiable quality gates

Every task is "done" only when ALL of these pass:

1. `sbt --client compile` — no errors.
2. `sbt --client test` — all unit tests pass.
3. `sbt --client assembly` — fat JAR builds with the expected name.
4. Official acceptance harness passes (e.g. TabbyShell:
   `./scripts/verify-tabbyshell.sh`, Snap: its provided verifier).
5. `sbt --client scalafmtCheckAll` — formatting is clean.
6. Manual adversarial probes: edge cases NOT covered by public tests.
7. No hardcoded secrets; env vars only (`OPENROUTER_API_KEY`, etc.).

## 1. Intake (before writing code)

- Read `README.md`, `SPEC.md`, and every public test in `tests/`.
- Produce `docs/<project>/CONTRACT.md` containing:
  - Exact output/error message formats.
  - CLI flags, env vars, exit codes.
  - Determinism rules (frozen time, no-color, etc.).
  - Ambiguities and untested behaviors (explicitly listed).
- Freeze the contract before implementation starts. Changes go through review.
- Resolve ambiguities with `ask-user`; use `brainstorming` for substantive
  scope/design choices. Every unresolved ambiguity gets an explicit ruling or a
  user decision, never a silent assumption.

## 2. Architecture (derived, not prescribed)

Do not start from a fixed module list. Derive the architecture from the
project's specification, public tests, and delivery interface. The
TabbyShell CLI-shaped split is preserved as a case study in
[`examples/tabbyshell-architecture.md`](examples/tabbyshell-architecture.md);
it is an example, not a default template. Do not copy it unless the project
actually has that shape.

Required architectural properties (every capstone):

1. External inputs (CLI args, files, env vars, network payloads) are parsed
   at the boundary into typed domain values; no raw strings with implicit
   assumptions flow through the domain.
2. Domain logic is pure and unit-testable; impurity is pushed to the edges.
3. Side effects live behind explicit ZIO interfaces (filesystem, process,
   HTTP, clock, environment) and are mapped into the sealed error ADT.
4. Output formatting is a pure function whenever the spec demands exact or
   byte-identical output.
5. Time, environment, randomness, and workspace paths are injected (state or
   services), never read deep inside pure logic.
6. Errors are a sealed ADT with stable, spec-conformant messages; never raw
   strings or generic exceptions in domain code.
7. Each module is independently implementable and testable, with frozen
   interfaces, so parallel subagents never share file ownership.

Approval gate: before packet writing starts, present the derived architecture,
error ADT, determinism seams, and file-ownership table using `brainstorming` or
`ask-user`. For material tradeoffs use `council-mode`; for annotated document
review use `plannotator`. Do not start Phase C without explicit approval.

Idiomatic ZIO rules (see memory: `idiomatic-zio-patterns`):
- No raw side effects outside ZIO: `zio.Console` for stdout/stderr,
  `zio.System` for env/properties, `zio.Clock` for time, `ZIOApp#exit`
  for process exit codes.

Anti-patterns:
- Creating Parser/Render/Cli modules when the project has no textual input
  language, byte-exact text output, or CLI surface.
- Copying TabbyShell's module split into a differently shaped project.
- Choosing a structure before reading the spec and every public test.

## Skills map (by stage)

| Stage | Skills | Use for |
|---|---|---|
| Intake / contract | `ask-user`, `brainstorming`, `zio-knowledge`, `zio-http-knowledge` | resolving ambiguities, approving the contract, verifying ZIO/library facts |
| Architecture / planning | `writing-plans`, `brainstorming`, `council-mode`, `plannotator` | deriving architecture, writing CONTRACT/ARCHITECTURE docs, reviewing material decisions |
| Foundation / implementation | `subagent-driven-development`, `test-driven-development`, `using-git-worktrees`, `dispatching-parallel-agents`, `pi-subagents` | per-task packets, failing tests first, isolated worktrees, independent lanes |
| Integration | `finishing-a-development-branch`, `systematic-debugging`, `verification-before-completion` | sequential merges, gate-failure investigation, fresh evidence before claims |
| Review | `requesting-code-review`, `receiving-code-review`, `pi-subagents` review references | fresh-context review lanes, finding disposition, evidence-based feedback |
| Fix round | `receiving-code-review`, `dispatching-parallel-agents`, `systematic-debugging`, `verification-before-completion`, `memory-write` | triage findings, parallel worktree fixes, debug failures, record lessons |
| Background execution | `bg_run`, `bg_status`, `bg_logs`, `bg_delegate`, `fusion_reason`, `fusion_investigate`, `fusion_validate` | non-blocking gate runs, multi-model review lanes, triage oracles |

## Background execution (orchestrator-level)

Long commands and multi-model checks run as background tasks in the parent
(orchestrator) session, which keeps doing independent work and is woken by
terminal notifications.

| Phase | Tool | Use |
|---|---|---|
| A, D, F gates | `bg_run` | sbt gate suites and official harness runs, with timeouts |
| B, F rulings | `fusion_reason` | tool-less multi-model deliberation on tradeoffs and contested findings |
| E extra lanes | `fusion_investigate`, `fusion_validate` | clean-context multi-model spec hunt; advisory post-green validation |
| E triage | `bg_delegate` | inspect-only oracle questions carrying conversation context |

Operational rules:

1. `isAgent: false` for sbt/harness commands; `true` only when the task
   itself launches an LLM/agent process.
2. Names are 2–6 words ("full gates after merge 2"), never raw commands.
3. Never poll `bg_status`/`bg_logs` as a wait loop; terminal notifications
   wake a follow-up turn. Inspect only on explicit request, hang evidence,
   or after a notification.
4. Cap gate tasks with timeouts (e.g. 900s sbt, 600s harness) so hangs fail
   loudly instead of blocking the wave.
5. Record task IDs and output paths in the ledger alongside lanes/commits.
6. `fusion_validate` is advisory and never replaces the non-negotiable gates.
7. Child lanes must not depend on `bg_*` — their turn ends with the task.
   The one-writer-per-worktree rule is unchanged by background execution.
8. Parallel gates from different worktrees each start their own
   `sbt --client` server; keep lane gates inside the lane and reserve
   orchestrator-level `bg_run` for integration/final suites. Each lane shuts
   down its own server when done; the parent shuts down any server it started
   once integration finishes.

## 3. Parallel subagent strategy

Parallelism is only safe when modules have fixed interfaces.

### Phase A — Foundation (sequential, one agent)
- Scaffold build, CI script, domain model + error ADT + shared interfaces.
- Freeze these as the "contract files" other agents code against.
- Apply `test-driven-development`: include failing/invariant tests for the
  shared interfaces as part of the foundation, not after implementation.
- Run the gate suite as a named background task (`bg_run`, `isAgent: false`)
  while continuing contract/scaffolding work; verify from the terminal
  notification, never by polling.
- Shut down the sbt server (`sbt --client shutdown`) before handing off, and
  again after any sbt config change (`build.sbt`, `project/plugins.sbt`).

### Phase B — Architecture proposal (sequential, one architect)

Before any implementation packet is written:

1. Identify the project's delivery shape (CLI tool, library, service, batch
   processor, TUI, ...) from the spec and the acceptance harness.
2. Propose the domain model and error ADT.
3. Propose modules mapped to the project's domain — not to a template —
   each with exclusive file ownership and frozen interfaces.
4. Identify determinism seams (time/env/IO) and the test strategy per module.
5. Write `docs/<project>/ARCHITECTURE.md`: module map, interfaces, and the
   file-ownership table.
6. Obtain explicit review and approval before parallel work starts: human
   approval via `brainstorming`/`ask-user`; optionally `council-mode` for
   tradeoffs or `plannotator` for annotated plan review.
7. For material design tradeoffs, optionally run `fusion_reason` as a
   tool-less multi-model deliberation and record the verdict (and rejected
   alternatives) in ARCHITECTURE.md.

Only after approval are Phase C packets created, each referencing the frozen
contracts in `docs/<project>/ARCHITECTURE.md`.

### Phase C — Parallel implementation (worktree-isolated)
Each subagent gets ONE packet derived from the approved architecture: one
module (or one cohesive module group), exclusive file ownership, and the
frozen interfaces to code against. Packets never prescribe shared modules.

Packet preparation:

- Prefer `subagent-driven-development` scripts (`sdd-workspace`, `task-brief`,
  `review-package`) when available; `docs/TASK_PACKET_TEMPLATE.md` is the
  capstone-specific fallback.
- Every packet is a cold-start brief: goal, exact spec sections, allowed files,
  interfaces to implement, definition of done (tests + commands + evidence),
  out-of-scope list, and stop/escalation rules.
- Apply `test-driven-development`: each packet names the failing test(s) or
  invariant test(s) that must exist before implementation is accepted.

Orchestration:

- Use one async `workflowScript` per wave. Prefer `runs.lanes([...])` for
  bounded parallel lanes with implement→verify stages; use `runs.all([...])`
  only for simple independent fanout.
- Keep one writer per cwd/worktree; reviewers are read-only and fresh-context.
- Child lanes run their own gates **synchronously** (bash with timeouts). Do
  not rely on `bg_*` inside lane children: their lifetime ends with the task;
  background tasks belong to the orchestrator session.
- Every lane that starts an `sbt --client` server must run
  `sbt --client shutdown` after its final gate passes (and immediately after
  any sbt config change), so finished worktrees do not leave idle sbt/JVM
  processes behind. Add this as an explicit step in each packet's definition
  of done.

Ledger:

- Maintain `docs/<project>/LEDGER.md` (or `.superpowers/sdd/<plan>/progress.md`
  when using SDD) recording lanes, branches, commits, gate evidence, rulings,
  parked findings, and blockers.
- After compaction or interruption, reconcile the ledger against `git log`,
  active subagent runs, and `git worktree list` before dispatching anything
  new; never re-dispatch work already recorded as complete.

### Phase D — Integration (sequential, one integrator)
- Merge one branch at a time into `develop`.
- Run full quality gates after EACH merge, never batch merges.
- Fix integration breakage before merging the next branch.
- Use `finishing-a-development-branch` for the verify → options → cleanup flow.
- Use `systematic-debugging` for any gate or integration failure: root cause
  before patching.
- Use `verification-before-completion`: no merge is "green" without fresh gate
  output for the exact merged HEAD.
- Run post-merge gate suites as named background tasks (`bg_run` with
  timeouts, e.g. 900s sbt, 600s harness); while they run, update the ledger
  and prepare Phase E packets. The terminal notification starts the next
  merge step.

### Phase E — Adversarial review (parallel lanes, parent triage)

Run after the official acceptance suite is green and before declaring the
capstone passed. Launch parallel read-only review lanes with fresh context and
distinct contracts; each lane produces a report under `reviews/<project>/`.
The parent triages findings with `receiving-code-review` discipline and hands
actionable items to Phase F. Reviewers must not edit production code.

Use `requesting-code-review` / SDD review-package mechanics: hand reviewers an
exact BASE..HEAD range or diff artifact, the relevant spec sections, and the
global constraints — never session history.

1. **Spec-compliance review** — compare implementation against the spec
   clause by clause: exact CLI behavior, error message formats, edge cases,
   and untested but specified behavior.
2. **Overfitting / environment review** — look for behavior tuned only to
   public tests, hidden cwd/HOME/timezone/locale/JDK assumptions, stale jar
   naming issues, and accidental network access.
3. **Code/process quality review** — inspect ZIO idiom, ADT/error-channel
   design, purity boundaries, test coverage gaps, formatting/lint strictness,
   commit hygiene, and whether the task-packet process was actually followed.
4. **Dynamic adversarial probing** — run the built artifact against inputs not
   covered by the public suite: missing files, malformed input, bad escapes,
   boundary dates/times, truncation, unusual Unicode, empty/large inputs,
   external-command failures, `--eval`, and REPL smoke tests.
5. **Multi-model lanes (background)** — `fusion_investigate` runs a
   clean-context multi-model hunt for subtle spec violations that
   single-model lanes may share a blind spot for; after the suite is green,
   `fusion_validate` adds a structured advisory review (never a replacement
   for the non-negotiable gates). `bg_delegate` answers quick oracle questions
   during triage — it carries conversation context and is inspect-only, so it
   complements fresh-context lanes and cannot replace the dynamic-probing
   lane.

Review output requirements:
- Every finding must be ranked: blocker / major / minor / nit.
- Every finding must include evidence: file/line, spec clause or test name,
  reproduction command, and proposed fix.
- Blockers and majors must be fixed and re-verified before the branch is
  considered green.
- Reviewers must not edit production code; only reports are written.

### Phase F — Fix round (one subagent per item, parallel where safe)

Validated on TabbyShell (2026-09-03): after a review produces N actionable
findings, fix them with **one dedicated subagent per review item**, never one
mega-fix agent, and never hand-editing in the parent session.

Before dispatching fix lanes, triage findings with `receiving-code-review` and
the pi-subagents `review-and-validation.md` categories: valid blocker, valid
non-blocker, stale, invalid, out-of-policy/scope, speculative. Only valid
blockers/majors enter the fix wave; minors and parked items get explicit
rulings in the ledger.

Contested findings (ambiguous spec readings, e.g. "TypeMismatch vs BadArg")
go through `fusion_reason` for a multi-model ruling before the ledger ruling
is written.

1. Group findings into items; group coupled files into one item (e.g. a
   parser signature change plus its call sites must share a lane — splitting
   them breaks compilation between merges).
2. Items with disjoint file sets run as **parallel worktree lanes**
   (`worktree: true`, one writer each). Each lane's packet includes: exact
   file scope, required change, spec citation, and the full gate set it must
   pass before committing (compile, unit tests, assembly, scalafmt, official
   harness). Prefer `runs.lanes([...])` when a lane has implement→verify
   stages; use `runs.all([...])` only for simple independent fanout.
3. The parent integrates **sequentially**: merge one lane at a time into
   `main`, then re-run the full gate set after the final merge. Disjoint file
   sets merge cleanly; verify commit claims against the actual diff
   (`git show`), never against commit messages alone.
4. Items that depend on the integrated state (e.g. unit tests for the fixed
   behaviors) run after integration, as their own single subagent.
5. A final parallel review pass (spec-compliance + code-quality, read-only)
   closes the round; findings re-enter this phase.
6. Record lane branches/commits, gate evidence, rulings, and parked findings in
   the project ledger as each wave completes; reconcile ledger vs. git before
   declaring the round closed.
7. Run the closing full gate suite as a named background task and attach a
   `fusion_validate` advisory check on the merged HEAD; declare the round
   closed only after both complete with recorded evidence.

Gotchas learned:
- Idle `sbt --client` servers keep running after a lane or worktree is done.
  Require `sbt --client shutdown` at the end of every lane that ran sbt (and
  after every sbt config change, so the server restarts cleanly on the next
  build); otherwise parallel worktrees accumulate JVM processes.
- Managed worktree lanes may lose their branch refs when the worktree is
  cleaned up; merge by commit hash if the branch is gone (commits remain
  reachable).
- Lanes must not touch shared mutable files (docs, reports) concurrently with
  the parent; keep integration-only writes in the parent session.
- After a merge, always rebuild the assembly jar before running the harness —
  a stale jar silently passes with pre-merge behavior.

## 4. Git workflow

- `main` — always green (full harness passes).
- `develop` — integration branch.
- `task/<id>-<slug>` — one branch per subagent packet.
- One writer per worktree; reviewers get read-only instructions.
- Commit messages state what gate evidence was produced.

## 5. Verification strategy

- Unit tests: pure functions (parser, renderer, core) — fast, exhaustive.
- Golden tests: byte-exact expected outputs for rendering.
- Acceptance harness: run the official tests frequently, not just at the end.
- Adversarial probes: malformed input, unicode, empty input, huge input,
  missing env vars, missing files, broken pipes, timeout behavior.
- Gate failures use `systematic-debugging`: confirm the exact HEAD, read the
  focused failing logs, name the failing contract, classify cause, reproduce
  narrowly, patch forward.
- Determinism: freeze time/env in tests; verify no wall-clock or locale leaks.

## 6. Definition of "passed the capstone"

1. Official verifier exits 0 on the full public suite.
2. Our extended adversarial probes behave sensibly per spec.
3. Code is formatted, typed, no `any`/unsafe casts without justification.
4. Every claim of "done" is backed by fresh command output saved as evidence
   (`verification-before-completion`) for the exact HEAD being claimed.
5. Reflection doc completed (what the agent did alone vs. needed intervention).

## 7. Refinements from the TabbyShell dry run (2026-07-17)

Findings from building TabbyShell with Scala 3.3.8 + ZIO 2.1.26 on Java 25 LTS:

1. **Exit codes: returning `ExitCode` from `run` is NOT enough.**
   `ZIOAppPlatformSpecific.main` maps *any successful* `run` to
   `ExitCode.success` and *any failure* to `ExitCode.failure` (see
   https://zio.dev/reference/core/zioapp/ and the `ZIOApp` source). The
   supported way to control the exact process exit code is the
   `zio.ZIOApp#exit(code: ExitCode)` helper, which calls `System.exit`
   through `Platform.exit`. Our `Main.run` therefore computes an `ExitCode`
   and ends with `_ <- exit(code)`.
2. **sbt run propagation:** with `run / fork := true` sbt propagates the
   forked JVM's non-zero exit code ("Nonzero exit code returned from
   runner: 1" → sbt exits 1). Without forking, `System.exit` from
   `ZIOApp#exit` would kill sbt itself, and an unforked run does not
   report the app's exit code. Keep `run / fork := true` (already set in
   `build.sbt`).
3. **JVM stderr noise on Java 25:** Scala 3.3.x `scala.runtime.LazyVals`
   touches `sun.misc.Unsafe`, so Java 25 prints "terminally deprecated
   method" WARNINGs to stderr at startup. They do not break the harness
   (all stderr assertions are `stderr_contains`), but they appear in every
   run. If this ever becomes a problem, options are: upgrade Scala once a
   release removes the `Unsafe` use, or run on an older LTS (Java 21).
4. **Harness JVM selection:** `test-harness/src/runner.ts` prefers
   `/opt/homebrew/opt/openjdk/bin/java` when present (that is Homebrew's
   unversioned, currently non-LTS `openjdk`), falling back to `PATH`. All
   sbt steps here use Java 25 via `scripts/env.sh`; harness results are
   byte-for-byte identical either way, but pin the JDK if strict parity is
   ever required.
5. **Jar naming contract:** the Scala harness locates the artifact with
   `target/scala-*/tabbyshell-assembly-*.jar` or `*-assembly-0.1.0.jar`.
   Keep `name := "tabbyshell"`, `version := "0.1.0"` and sbt-assembly.
6. **Always `clean` after deleting old `target/scala-<ver>` dirs** or the
   harness may pick up a stale jar from another Scala version directory.
7. **Evidence recorded:** `sbt --client "compile; Test/compile; test;
   assembly; scalafmtCheckAll"` green; full official suite: **50 passed,
   0 failed** (`./capstones/tabbyshell/verify --lang scala
   --implementation-root capstone-scala/tabbyshell`), exit 0.
8. **Parallel subagent lesson:** interface-first worked — Parser, Render,
   Json/Csv, Executor, External and Main were built against the shared
   `Value`/`TabbyError` ADTs with only one integration fix needed
   (exit-code propagation). For Snap, freeze `Model`/error ADT and the
   CLI contract before fanning out.
9. **Idiomatic ZIO side effects (mandatory style rule):**
   - stdout: `zio.Console.print` / `Console.printLine` — never
     `System.out.print*`, not even wrapped in `ZIO.attempt*`.
   - stderr: `zio.Console.printError` / `Console.printLineError` — never
     `System.err.print*`.
   - env vars: `zio.System.env("NAME")` / `zio.System.property("NAME")`
     — never `java.lang.System.getenv()` / `getProperty`.
   - time: `zio.Clock.currentTime(TimeUnit.SECONDS)` — never
     `System.currentTimeMillis()` inside app logic.
   - Allowed raw exceptions, each with an inline comment: TTY detection
     (`java.lang.System.console()`) — ZIO has no equivalent; keep it inside
     `ZIO.attempt` as a boolean snapshot.
   - Stdin: use `Console.readLine` (prompt optional). End of input fails with
     `java.io.EOFException` — handle it with
     `.catchSome { case _: java.io.EOFException => ... }`, never null checks;
     `scala.io.Source.stdin` and `scala.io.StdIn.readLine` are prohibited.
   - Blocking file/process/network I/O stays in `ZIO.attemptBlocking`,
     always `mapError`d into the sealed error ADT (TabbyError pattern).
   - Verification command to enforce this in review:
     `grep -rn "System\.\(out\|err\|exit\)\|println(" src/main/scala` must
     return only the documented exceptions.
10. **Toolchain:** this machine has no system Java (`/usr/bin/java` stub
    fails); sbt is the Coursier launcher. Every sbt invocation needs
    `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
    exported (see `scripts/env.sh`, which scripts source). Source the env
    script before any sbt/verify command.
11. **Stale build artifacts:** after changing `scalaVersion`, delete old
    `target/scala-<old>/` dirs or the harness may pick up a stale assembly
    jar.

## 8. Adversarial review findings (TabbyShell dry run, 2026-09-03)

Three review lanes (spec compliance, code quality, dynamic probing) ran
against the green 50/50 implementation. The full reports are in
`docs/reviews/`. Lessons that carry into Snap:

### Fixed immediately (would have been caught only by spec re-reading, not tests)
1. **`last` with no argument returned the FIRST element** (`headOption` used
   for both directions). Classic copy-paste bug invisible to a suite that only
   tests `last 1`. Fixed to `lastOption` for both Table and List.
2. **`sort-by --reverse` tie order wrong.** Implementation negated the
   comparator (stable descending), spec says "reverse the result" — ties must
   appear in reverse input order. Fixed: sort stable ascending, then reverse.
3. **Numeric comparison went through `Double`**, silently breaking 64-bit
   Int/Filesize comparisons above 2^53. Fixed: exact `Long.compare` for
   Int/Filesize pairs, `BigDecimal` when a `Float` is involved.

### Open items for Snap (process, not code)
1. **Write real unit tests, not just pass-the-harness.** At dry-run time the
   only unit test was `VersionSpec`; every P1 bug above was invisible to both
   unit tests and the public suite. Since resolved for TabbyShell (91 unit
   tests across Value/Parser/Csv/Json/Render/Executor/TabbyError suites).
   Minimum for Snap: spec per module (parser, renderer, formatters,
   comparators, edge cases) from day one.
2. **Pin error-message contracts.** Several messages diverge from the spec's
   `command: expected X, got Y` shape (e.g. bool/null operator restrictions,
   `length` on wrong type). Spec ambiguities (CSV ragged rows, `.5` numbers,
   unicode idents, external rendering of literal args) should be raised with
   the spec owner before hidden tests are written, not after.
3. **Process artifacts were skipped:** no frozen `CONTRACT.md`, no
   develop/task branches (all work landed on `main` in 3 commits), no saved
   gate evidence, no CI. For Snap: create `docs/snap/CONTRACT.md` first,
   branch per task packet, save gate output, add a CI workflow.
4. **Environment gotchas:** JVM emits `sun.misc.Unsafe` deprecation warnings
   on stderr under Java 25 (harmless for contains-based assertions but noisy
   for exact-stderr assertions); harness prefers
   `/opt/homebrew/opt/openjdk/bin/java` over `PATH` when present.

## 9. Fix round record (TabbyShell round 2, 2026-09-03)

A second review pass (fresh spec-compliance + code-quality lanes over
`1f7cac6..af9c371`) returned 2 majors and 3 minors; all resolved on `main`
at `11d2d52` with gates green (91 unit tests, 50/50 harness, scalafmt clean):

1. **`select` duplicate columns** (major): `select name name` fell through
   `Value.tableTrusted` to `IllegalStateException` → "internal error", exit 2.
   Fixed in `Executor.select` with a `firstDuplicate` scan that fails with
   `TabbyError.BadArg("select", "duplicate column: <name>")` before table
   construction; regressions in `ExecutorSpec`.
2. **IO error messages** (major): three private `ioMessage` copies shadowed
   `TabbyError.ioMessage`, so `open` on a missing file printed only the path.
   Consolidated on `TabbyError.ioMessage` in Executor/External/Main;
   `TabbyErrorSpec` pins the mappings.
3. **Deterministic duplicate reporting** (minor): `Value.duplicateKey` now
   scans left-to-right (first repeated key wins, positional).
4. **Color rendering tests** (minor): `RenderSpec` asserts exact ANSI
   sequences per §6.6 instead of a bare ESC[ presence check.
5. **Ragged-CSV error kind** (minor, ruled): surfaces as `BadArg`
   ("open: row N has M columns, expected K"); §2's `TypeMismatch` wording
   conflicts with §3.3's message format, so the deviation is documented in
   `Csv.scala` rather than forced through the wrong error constructor.
6. **Parked:** SIGINT/Ctrl-C REPL handling (§7.4) — pre-existing, invisible
   to the harness, needs real signal handling; setup-error messages
   intentionally lack the `✗ ` prefix (consistent with the arg-parse path).

Process lessons for future rounds:
- Two overlapping fix workflows were dispatched after a context reset; the
  duplicate lanes were reconciled with `git cherry` (patch-equivalence) and
  stale worktree branches deleted. The ledger requirement in Phase C exists
  because of this.
- Disjoint-file lanes merged into `main` cleanly; even two lanes editing
  different regions of one file auto-merged, but treat that as the exception
  and prefer disjoint file ownership when forming lanes.

