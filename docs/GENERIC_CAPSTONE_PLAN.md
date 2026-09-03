# Generic Capstone Plan (Scala)

This plan is language-agnostic in structure but tuned for our Scala/ZIO
implementation. It is designed to be executed with parallel subagents while
keeping one integrator responsible for merging and quality gates.

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

Idiomatic ZIO rules (see memory: `idiomatic-zio-patterns`):
- No raw side effects outside ZIO: `zio.Console` for stdout/stderr,
  `zio.System` for env/properties, `zio.Clock` for time, `ZIOApp#exit`
  for process exit codes.

Anti-patterns:
- Creating Parser/Render/Cli modules when the project has no textual input
  language, byte-exact text output, or CLI surface.
- Copying TabbyShell's module split into a differently shaped project.
- Choosing a structure before reading the spec and every public test.

## 3. Parallel subagent strategy

Parallelism is only safe when modules have fixed interfaces.

### Phase A — Foundation (sequential, one agent)
- Scaffold build, CI script, domain model + error ADT + shared interfaces.
- Freeze these as the "contract files" other agents code against.

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
6. Have it reviewed (human or oracle subagent) before parallel work starts.

Only after approval are Phase C packets created, each referencing the frozen
contracts in `docs/<project>/ARCHITECTURE.md`.

### Phase C — Parallel implementation (worktree-isolated)
Each subagent gets ONE packet derived from the approved architecture: one
module (or one cohesive module group), exclusive file ownership, and the
frozen interfaces to code against. Packets never prescribe shared modules.

Packet template (see `docs/TASK_PACKET_TEMPLATE.md`):
- Goal, exact spec sections, allowed files, interfaces to implement,
  definition of done (tests + commands + evidence), out-of-scope list.

### Phase D — Integration (sequential, one integrator)
- Merge one branch at a time into `develop`.
- Run full quality gates after EACH merge, never batch merges.
- Fix integration breakage before merging the next branch.

### Phase E — Adversarial review (parallel lanes, sequential fixes)

Run after the official acceptance suite is green and before declaring the
capstone passed. Launch parallel review lanes; each lane produces a report
under `reviews/<project>/`, then one writer applies fixes sequentially and
re-runs the full gate suite.

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

1. Group findings into items; group coupled files into one item (e.g. a
   parser signature change plus its call sites must share a lane — splitting
   them breaks compilation between merges).
2. Items with disjoint file sets run as **parallel worktree lanes**
   (`worktree: true`, one writer each). Each lane's packet includes: exact
   file scope, required change, spec citation, and the full gate set it must
   pass before committing (compile, unit tests, assembly, scalafmt, official
   harness).
3. The parent integrates **sequentially**: merge one lane at a time into
   `main`, then re-run the full gate set after the final merge. Disjoint file
   sets merge cleanly; verify commit claims against the actual diff
   (`git show`), never against commit messages alone.
4. Items that depend on the integrated state (e.g. unit tests for the fixed
   behaviors) run after integration, as their own single subagent.
5. A final parallel review pass (spec-compliance + code-quality, read-only)
   closes the round; findings re-enter this phase.

Gotchas learned:
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
- Determinism: freeze time/env in tests; verify no wall-clock or locale leaks.

## 6. Definition of "passed the capstone"

1. Official verifier exits 0 on the full public suite.
2. Our extended adversarial probes behave sensibly per spec.
3. Code is formatted, typed, no `any`/unsafe casts without justification.
4. Every claim of "done" is backed by command output saved as evidence.
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
     (`java.lang.System.console()`) and full-stdin reads
     (`scala.io.Source.stdin`) — ZIO has no equivalent; keep them inside
     `ZIO.attemptBlocking` and map errors into the domain error ADT.
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
1. **Write real unit tests, not just pass-the-harness.** The only unit test
   was `VersionSpec`; every P1 bug above was invisible to both unit tests and
   the public suite. Minimum for Snap: spec per module (parser, renderer,
   formatters, comparators, edge cases).
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
