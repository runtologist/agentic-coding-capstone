# Phase E/F Triage — Snap (Scala)

Triage per `receiving-code-review`: each claim verified independently against
code/spec before acceptance. Only verified blockers/majors and accepted
human-review items enter the fix wave; minors/nits get explicit rulings.

Sources: E1 spec-compliance, E2 overfitting/env, E3 code quality, E4 dynamic
probing (partial — worker timed out, parent salvaged from transcript), E5
untested-spec hunt, human review (`00-human-review.md`), CI failure on test 25.

**Policy constraint (user directive):** the harness (`harness/`) is NEVER
modified. The earlier wrapper experiments (commits `a81e229`, reverted in
`d593590`) are void. All fixes land in `snap/` implementation code or docs.

## Verified top finding: CI test 25 UTF-8 filename regression (BLOCKER for CI)

- Symptom: under harness-forced `LANG=C`, Linux JVM sets `sun.jnu.encoding` to
  ASCII; `Path.getFileName.toString` mangles `é`/`😀` to U+FFFD. macOS masks it
  (jnu always UTF-8 locally).
- Verified: `-Dsun.jnu.encoding` is NOT honored on JDK 25 command line;
  `System.setProperty` in a static initializer is too late (cached at JVM
  bootstrap). Harness builds the candidate env from scratch, so env injection
  from outside the JVM is impossible without harness changes.
- **Accepted fix (F-utf8):** guarded JVM re-exec in `Main.scala`: cheap
  filename round-trip probe (or `sun.jnu.encoding` check) at startup; if
  lossy and not already re-exec'd (guard env var) and running from a jar,
  spawn `java -jar <self> <args>` with `LANG=C.UTF-8 LC_ALL=C.UTF-8`,
  `inheritIO`, TERM/INT forwarded (harness signals the whole process group),
  exit with child's code. No behavior change where jnu is already UTF-8.

## Fix lanes (Phase F, wave 1 — parallel, disjoint files)

| Lane | Items | Files |
|---|---|---|
| F-utf8 | CI blocker: guarded re-exec for UTF-8 filename decoding | Main.scala, MainSpec.scala |
| F-coreA | **E5-F1 (major)**: remove 64M-cell diff cap via linear-space Hirschberg (identical script/tie-breaks, property-tested vs naive oracle); H2 while-loops in Diff | Diff.scala, DiffSpec.scala |
| F-coreB | **H3**: `PositiveSafeInteger` opaque type + smart constructor at parse/decode boundaries; **E1-S1**: `Model` revision-increment overflow helper (typed error, reuses `NotPositiveSafeInteger`); **E3-N1**: remove guarded cast in Replay; H2 while-loops in Model/Json/Codec/Ot/Replay | Model.scala, Json.scala, Codec.scala, Ot.scala, Replay.scala, SnapError.scala (+Specs) |
| F-io | **H1**: idiomatically wrap NIO side effects in ZIO (fine-grained `attemptBlocking`+`mapError` or zio-nio; behavior byte-identical); **E5-F3**: validate path rules during `WorkingTree.scan` so status/diff fail like commit; **E4-P3**: strict UTF-8 decode of repository.json/config.json (reuse `Model.decodeUtf8` → `InvalidJson`, exit 1) | WorkingTree.scala, RepoIo.scala, Config.scala (+Specs) |
| F-http | **E4-P1**: port-in-use must exit 1 (`IoFailure`) not exit 2 defect — catch cause/defect in `HttpServe.serve`; **E4-P2**: HEAD must carry same headers as GET (explicit Content-Length = snapshot bytes); **E4-P3b**: strict UTF-8 decode of fetched HTTP bodies | HttpServe.scala, HttpFetch.scala, HttpSpec.scala |

## Fix lane (Phase F, wave 2 — after wave 1 merges)

| Lane | Items | Files |
|---|---|---|
| F-cmd | **E3-M1**: add `Output.flushErr`, flush stderr on all error/warning exit paths; **E3-M2/E2-F4**: `SNAP_DEBUG` into `CmdEnv` snapshot (Main + Commands); **E1-S1 wiring**: commit/revert use Model overflow helper; **E5-F4**: unit test exit-2 defect channel; H2 while-loops in Cli/Commands/Render | Commands.scala, Main.scala, Cli.scala, Render.scala (+Specs) |

## Rulings (no code change)

| ID | Finding | Ruling |
|---|---|---|
| E2-F1/E1-S2/E5-F2 | single `System.console()` TTY probe vs §7.11 per-stream | **Waiver**: no portable per-fd isatty on JVM without JNI/JLine dependency; resolver logic is per-stream and unit-tested (RenderSpec); harness pipes both streams so unobservable in acceptance suite. Document in CONTRACT §15 + ARCHITECTURE. |
| E1-N1 | structurally-equal duplicate patches accepted | **Accept**: §4.2b permits same-dot duplicates iff structurally equal; deduped at every use. |
| E1-N2 | `1.0`/`1e2` accepted for integer fields | **Accept**: matches `Number.isInteger` semantics pinned in CONTRACT; tests pin it. |
| E1-N4 | non-base-closed version message wording | **Accept**: exit channel and `snap: <detail>` shape conform; wording unpinned. |
| E2-F2 | run_tests jar glob/staleness | **Park**: harness is frozen by user directive; single pinned `assemblyJarName` + single Scala version make current glob unambiguous. |
| E2-F5 | `.snap` exact-case on case-insensitive FS | **Accept + document**: SPEC §2 forbids case normalization; assume case-sensitive filesystem semantics. |
| E2-F6 | harness/README drift | **Park**: harness frozen by user directive. |
| E3-N2 | test gaps (advisory) | Partially covered by F-wave test additions (exit-2 channel, diff cap, scan validation). Rest parked. |
| E3-N3 | `-Wunused` scalac options | **Park**: late-project gate-tightening risk. |
| E3-N4 | `scripts/env.sh` gitignored | **Doc fix** in integrator pass: JDK requirement documented in README/LEDGER. |
| E5-F5..F10 | untested-risk coverage gaps | **Park** (advisory); F5/F6/F8 may be picked up opportunistically in F-wave lanes' own suites. |

## After wave 2 (integrator pass)

1. Sequential merges → full gate after each merge → final gate + harness on main.
2. Doc updates: ARCHITECTURE.md drift (E3-M3), CONTRACT §15 rulings (TTY waiver,
   diff-guard decision if any, strict-UTF-8 decode behavior, port-in-use exit 1).
3. §10 coverage protocol: add sbt-scoverage temporarily, measure, ≥~80%
   statement coverage on `src/main/scala` or run a coverage extension wave.
4. §6.5 reflection doc.
5. Push, tag final state.
