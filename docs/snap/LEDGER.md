# Snap — Project Ledger

Driver: main session. This ledger records lanes, branches, commits, gate
evidence, rulings, and parked findings. Reconcile against `git log` and
`git worktree list` after any compaction or interruption.

**Repo:** `/Users/sschenk/ziverge/vibe-coding-2-workshop/capstone-scala`
(remote: `runtologist/agentic-coding-capstone`)
**Commit identity:** `Snap dev <capstone-dev@local>` — NO AI co-author trailers, ever.
**Language:** Scala (workspace `snap/`). Acceptance suite: `harness/snap/` (28 cases).

## Quality gates (non-negotiable)

```bash
source scripts/env.sh   # JAVA_HOME -> openjdk@25
cd snap
sbt --client "compile; test; assembly; scalafmtCheckAll"
cd ..
bash harness/snap/run_tests --lang scala --implementation-root "$PWD/snap"
```

After any sbt config change: `sbt --client shutdown` first.

## Environment facts (verified 2026-09-04+)

- Java 25 (Homebrew openjdk 25.0.4.1) via `scripts/env.sh`; sbt runner 2.0.8.
- Node v26.8.1 / npm 11.19.0; harness `node_modules` installed via `npm ci`.
- Vendored harness `--list` validates all 28 YAML cases.
- Git branches: `main` (green), `develop` (integration, created at 67b6011).

## Log

| Date | Phase | Item | Evidence / Notes |
|---|---|---|---|
| 2026-09-04 | A0 bootstrap | Committed scaffold `67b6011` (main): snap/ sbt skeleton + vendored harness/snap | `git log -1 67b6011`; author "Snap dev" |
| 2026-09-04 | A0 bootstrap | Scaffold build check | bg task `bcb88e1c7` exit 0: compile + assembly OK → `snap/target/scala-3.3.8/snap-assembly-0.1.0.jar` |
| 2026-09-04 | A0 bootstrap | Jar smoke test | `--version` → `snap 1.0.0` exit 0; stub error path exit 1. Java 25 prints `sun.misc.Unsafe` WARNINGs on stderr (known, harmless for contains-assertions). |
| 2026-09-04 | A0 bootstrap | **Risk noted:** ZIO default error reporter dumps stack trace to stderr on unhandled failure — Phase A must ensure all errors are caught and rendered as `snap: <detail>` with explicit `exit(code)`; no unhandled failures may reach the ZIO runtime reporter. | |
| 2026-09-04 | A0 bootstrap | **Risk resolved:** JVM `sun.misc.Unsafe` stderr warnings (Java 25 + Scala 3.3.x LazyVals) would break `stderr_equals: ""` assertions. Verified `-Dsun.misc.unsafe.memory.access=allow` suppresses them; vendored `run_tests` wrapper updated to pass this flag. Java 21 not needed. | jar smoke-test 2026-09-04 |
| 2026-09-04 | 1 intake | CONTRACT.md worker dispatched | run `da2f3221` — completed; committed as `75f07ab` |
| 2026-09-04 | 2 architecture | ARCHITECTURE.md drafted, then revised per user feedback (zio-json, zio-http, `Port`, modeled error/warning ADTs, join totality); committed `e9a3878`; user approved proceed | `git log --oneline` |
| 2026-09-04 | 2 architecture | Verified zio-json latest 0.7.43 and zio-http latest 3.3.3 on Maven Central (Scala 3 artifacts available) | Maven search API |
| 2026-09-04 | A/L1b | Foundation revision worker dispatched on branch `task/02-ziojson-foundation` | pending |
| 2026-09-04 | 1 intake | CONTRACT.md worker dispatched | run `da2f3221` — completed, 28/28 tests inventoried |
| 2026-09-04 | 2 architecture | Architecture revised per user feedback: zio-http (not JDK), zio-json (not hand-rolled), `Port` opaque Int type, granular error ADT (no free-string `RepositoryInvalid`), `ReplayWarning` as sealed enum, integration coverage via YAML harness | committed `e9a3878` |
| 2026-09-04 | 2 architecture | **APPROVED by user** — proceeding to implementation | user directive |
| 2026-09-04 | 3 foundation | L1 revision dispatched (zio-json migration, Port type, error ADT refinement, ReplayWarning enum) | pending |
