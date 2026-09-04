# Lane E3 — Code & Process Quality Review (Snap / Scala)

**Date:** 2026-07-09 · **Reviewer:** E3 subagent (read-only; no sbt/git executed)
**Scope:** `capstone-scala/snap/` (Scala 3 + ZIO 2.1.26 + zio-json 0.7.43 + zio-http 3.3.3), docs (`CONTRACT.md`, `ARCHITECTURE.md`, `LEDGER.md`, `packets/`), build files, test suites.
**Repo state:** branch `main`, HEAD `4f03ed0` (task packet cited `8ab113e`; one docs-only ledger commit `4f03ed0` landed after the packet was written — no code delta).

## Verdict

**No blockers. No majors.** 4 minor, 4 nits. ZIO idiom discipline, error-channel design, and test quality are all strong. Merge status: OK with notes.

---

## 1. ZIO idiom rules

Greps run over `snap/src/main/scala`:

| Check | Result |
|---|---|
| `System.(out\|err\|exit)\|println(` | 2 hits, both documented exceptions: `Commands.scala:108,458` — `System.err.println(defect.prettyPrint)` gated behind `SNAP_DEBUG`, wrapped in `ZIO.succeed`, defect-trace only. No `System.exit` anywhere. |
| `scala.io.Source.stdin` / `scala.io.StdIn` | 0 hits ✓ |
| Exit codes | `Main.scala:47` uses `ZIOApp#exit(ExitCode(code))` ✓ |
| Blocking IO | All FS in `RepoIo` (41,63,83,130), `Config` (46,72), `WorkingTree` (45,151), `Commands` signal install (474,487) via `ZIO.attemptBlocking` with `mapError` → sealed `SnapError` ✓ |
| Time / randomness | No `Clock`, `Instant`, `currentTimeMillis`, `Random`, `UUID` anywhere in main ✓ (CONTRACT §14 no-wall-clock honored) |
| Env | Snapshot-once pattern: `Main.scala:39-44` captures cwd/HOME/SNAP_COLOR/NO_COLOR/TTY inside `ZIO.attempt` into `CmdEnv` ✓ (TTY via `System.console()` — the sanctioned exception, `Main.scala:43`) |
| Raw-JVM exceptions | Netty property at `Main.scala:21` (inline comment, pre-class-init — unavoidable), `sun.misc.Signal` in `Commands.scala:469-495` (doc comment cites SPEC §14/CONTRACT §12, restored via `acquireReleaseExit`) ✓ |

Deviations from the letter of the checklist (see findings M2, M3): `zio.Console`/`zio.System` are **not** used — replaced by a `Commands.Output` trait over raw UTF-8 `PrintStream(FileDescriptor.out/err)` (byte-exactness + flush control + in-memory test double) and `java.lang.System.getenv` inside `ZIO.attempt`. The intent (effect-wrapped, snapshot-injected, testable) is met; the architecture doc is stale.

## 2. ADT / error-channel design — clean

`SnapError.scala`: sealed abstract class, ~40 typed variants, one case per failure mode, frozen message strings cross-referenced to CONTRACT §1–§13. No raw-string domain errors. Boundary mapping is consistent: CLI parse (`Cli.parse → Either[SnapError,_]`), IO (`toSnapError` in `RepoIo`/`Config`/`WorkingTree`), HTTP (`HttpFetch.toSnapError` covers status/timeout/malformed-URL/transport; `HttpServe` maps bind failures). Unreachable-branch invariant violations (`Ot`, `Diff`) throw `IllegalStateException` → defect → `snap: internal error` + exit 2, which matches CONTRACT §2. `SnapErrorSpec` pins every contract message verbatim.

## 3. Purity boundaries — clean

`Diff`, `Ot`, `Replay`, `Codec`, `Render`, `Model`, `Cli`, `Json` contain no IO, env, clock, or randomness (verified by grep + read). Env enters only via `CmdEnv`; `Render.resolvePresentation` takes TTY flags as plain booleans. `Json.firstValueEnd` is a pure scanner. Mutable state is confined to method-local `var`s inside pure functions.

## 4. Type discipline — clean, 2 notes

- `asInstanceOf` exactly once: `Replay.scala:282` (`change.asInstanceOf[Change.Text]`), guarded by the `isTextChange` match 4 lines above — safe but restructure-able (nit N1). `isInstanceOf` once (`Replay.scala:300`) — fine.
- `Option.get` uses (`Replay.scala:250,279-280,298,302`) are all provably guarded by the enclosing conditions; `Map.get`/`Version.get` are defaulting lookups, not `Option.get`.
- `null`: confined to Java-interop boundaries (`Path` parent walks, temp-file cleanup sentinel `RepoIo.scala:87-108`, `System.console()` check). Never leaks into domain types.
- `var`: pervasive but consistent deliberate hot-loop style in pure code; no shared mutable state (`Output.Captured` correctly uses `Ref`).
- No `TODO/FIXME/???` in main sources.

## 5. Test quality (spot-checks: MainSpec, CommandsSpec, OtSpec, HttpSpec, SnapErrorSpec, DiffSpec) — excellent

Assertions pin observables, not internals: exact `(exit, stdout, stderr)` triples (`MainSpec:92-135`), byte-exact golden JSON over raw sockets incl. HEAD zero-body and lowercase header values (`HttpSpec:197-246`), OT transform outputs asserted in **both** association directions (`OtSpec:42-81`), exact error strings (`SnapErrorSpec`), plus property-style checks against a naive reference diff (`DiffSpec:80-99`). 481 tests, no assertion-weak suites found.

## 6. Build hygiene — clean

`build.sbt`: Scala **3.3.8** (LTS ✓), `run/fork := true`, `assembly/assemblyJarName := "snap-assembly-0.1.0.jar"` ✓, `mainClass` set for both run and assembly, sensible merge strategy, zio-test framework registered. `project/plugins.sbt`: sbt-assembly 2.3.1 + sbt-scalafmt 2.5.4 ✓. `.scalafmt.conf`: 3.8.3, `scala3` dialect, maxColumn 100 ✓; ledger gates show `scalafmtCheckAll` green.

## 7. Process conformance — clean (one caveat)

- Packets: all 8 present in `docs/snap/packets/` (L1b, L2, L3, L4, L5, L6, L7, J1), each with branch, deps, owned files, frozen APIs, safety rules.
- Ledger: lane/commit/gate evidence throughout, including a candid incident+recovery record (`rm -rf` worker accident, history reconstruction, guardrails added).
- Commit hygiene: `.git/logs/HEAD` reflog shows **every** commit authored by `Snap dev <capstone-dev@local>` with conventional subjects (`feat/docs/merge`, lane tags); no AI-trailer indicators in subjects. **Caveat:** reviewer could not run `git log` body check; supervisor ran: `git log --format='%an <%ae>%n%B' main | grep -i "co-authored-by"` → **no matches (clean)**. See parent note below.

## 8. Coverage risk (advisory)

Surface mapping is complete: 16 production files ↔ 15 suites (HttpFetch+HttpServe share HttpSpec). Thinnest spots, all low-risk:
- Exit-2 defect path in `Commands.finish`/`serve` (never exercised end-to-end; only the message string is pinned).
- `HttpFetch` timeout branch (`RequestTimedOut`) — no test.
- `Diff` 64M-cell budget throw — no test (and see M4).
- Signal-handler *restoration* on scope close.

---

## Ranked findings

**Blockers: none. Majors: none.**

- **M1 (minor)** — Error path never explicitly flushes stderr before process exit. `Commands.scala:103-112` (`finish`) and `:449-462` (`serve`) write the error line and return exit 1/2 without flushing; only success (`:105`) and serve-URL paths flush, and `Output` exposes `flushOut` only. Byte delivery on exit-1 relies on implicit JVM/ZIO shutdown flushing the `BufferedWriter` inside the raw `PrintStream`. Harness is empirically green, but this is the one output path not deterministically flushed. **Fix:** add `flushErr` to `Output`, call it in both failure branches.
- **M2 (minor)** — `SNAP_DEBUG` read live via `java.lang.System.getenv` at `Commands.scala:107` and `:457`, bypassing the one-time `CmdEnv` snapshot that CONTRACT §14 / Main's doc comment establish as the env policy. **Fix:** add `snapDebug: Boolean` to `CmdEnv`, populate in `Main.cmdEnv`.
- **M3 (minor)** — Doc/code drift: `ARCHITECTURE.md:82` claims output goes via `zio.Console` and `:206` claims env via `zio.System.env`; implementation deliberately uses raw `FileDescriptor` PrintStreams (`Commands.scala:44-52`) and `System.getenv` in `ZIO.attempt` (`Main.scala:41`). The code choice is defensible for byte-exactness; the doc is stale. **Fix:** update ARCHITECTURE.md and list both in its raw-JVM-exceptions table.
- **M4 (minor)** — `Diff.scala:83-86`: the 64M-cell budget throws `IllegalStateException`, surfacing as opaque `snap: internal error` + exit 2. Reachable in normal use by fully rewriting an ~8k-line file ((8001)² > 64M). **Fix:** raise the budget or add a typed `SnapError.DiffTooLarge` with a clean exit-1 message.
- **N1 (nit)** — `Replay.scala:282` `asInstanceOf[Change.Text]`: guarded but restructure into the earlier pattern match would remove the only cast in main sources.
- **N2 (nit)** — Test gaps listed in §8 (exit-2 path, fetch timeout, diff budget) — advisory only.
- **N3 (nit)** — `scalacOptions` could add `-Wunused:all` (optionally `-Werror`) to harden the gate; scalafmt is already enforced.
- **N4 (nit)** — `scripts/env.sh` (required by the LEDGER quality gate: `JAVA_HOME → openjdk@25`) is gitignored (`scripts/` scratch rule), so a fresh clone can't reproduce the gate as documented. No secrets inside; consider committing it or documenting the JDK requirement in README/LEDGER.

---

**Parent triage note (orchestrator):** report persisted by parent (reviewer lane had no write tool). Co-author trailer check executed by parent: clean, no matches.
