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

## 2. Architecture (Scala 3 + ZIO)

Recommended module split (adapt to the actual capstone):

| Module       | Responsibility                              |
|--------------|---------------------------------------------|
| `Model.scala`| Pure ADTs for domain values + typed errors  |
| `Parser.scala`| Input -> AST, pure, fully unit-testable    |
| `Core.scala` | Pure logic (evaluation, merging, etc.)      |
| `Effects.scala`| ZIO wrappers for FS/process/HTTP/clock    |
| `Render.scala`| Pure output formatting (byte-exact)       |
| `Cli.scala`  | Arg parsing, mode dispatch                  |
| `Main.scala` | ZIOAppDefault entrypoint, exit codes        |

Rules:
- Parse external input at the boundary; typed domain after that.
- No raw `println`, `sys.exit`, `System.getenv`, file/network IO outside ZIO.
- Errors are a sealed ADT, never raw strings/exceptions in domain code.
- Renderer/logic must be pure: `now`, `cwd`, `color` come from state/config.

## 3. Parallel subagent strategy

Parallelism is only safe when modules have fixed interfaces.

### Phase A — Foundation (sequential, one agent)
- Scaffold build, CI script, `Model` + error ADT + shared interfaces.
- Freeze these as the "contract files" other agents code against.

### Phase B — Parallel implementation (worktree-isolated)
Each subagent gets ONE packet and must not touch shared files:
- Agent 1: Parser (+ parser unit tests)
- Agent 2: Renderer/output formatting (+ golden unit tests)
- Agent 3: Core logic / commands (+ unit tests)
- Agent 4: External effects (FS/process/HTTP) behind ZIO interfaces (+ tests)

Packet template (see `docs/TASK_PACKET_TEMPLATE.md`):
- Goal, exact spec sections, allowed files, interfaces to implement,
  definition of done (tests + commands + evidence), out-of-scope list.

### Phase C — Integration (sequential, one integrator)
- Merge one branch at a time into `develop`.
- Run full quality gates after EACH merge, never batch merges.
- Fix integration breakage before merging the next branch.

### Phase D — Adversarial review (parallel lanes, sequential fixes)

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
