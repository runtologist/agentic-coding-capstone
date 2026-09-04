# E2 Review — Snap (Scala) — Overfitting / Environment

**Lane:** E2 — overfitting / environment review (read-only static analysis)
**Repo:** `/Users/sschenk/ziverge/vibe-coding-2-workshop/capstone-scala` @ main
**Scope:** `snap/src/main/scala/snap/*.scala` (all 16 files read), `snap/build.sbt`, `snap/project/*`, `harness/snap/{run_tests,verify,test-harness/src/*.ts,tests/*.yaml}`, `scripts/env.sh`, `docs/snap/{CONTRACT.md,progress.md}`, `.github/workflows/capstone-ci.yml`

## Verdict

**No blocker findings. No major findings.** 3 minor, 4 nit.

The implementation is substantially environment-clean: no wall-clock, no locale-dependent formatting, no hardcoded machine paths/hosts in production code, no network outside the HTTP seams, no test-only magic strings, deterministic ordering on every output path inspected, and Java 25 stderr noise mitigated in both `Main.scala` and the harness wrapper.

## Findings

### F1 — Minor: Single `System.console()` TTY probe collapses spec-required per-stream presentation selection
**Evidence:**
- `snap/src/main/scala/snap/Main.scala:43` — `isTty = java.lang.System.console() != null` (one flag)
- `snap/src/main/scala/snap/Commands.scala:30` — `CmdEnv` carries a single `isTty: Boolean`
- `snap/src/main/scala/snap/Commands.scala:82` — `Render.resolvePresentation(env.snapColor, env.noColorPresent, env.isTty, env.isTty)` (same flag for stdout **and** stderr)
- SPEC §7.11: auto mode = "terminal mode independently on stdout or stderr when **that stream** is a TTY"; SPEC §11 requires unit-testing TTY selection for stdout and stderr independently.

**Impact:** `System.console()` is non-null only when stdin+stdout are terminals; it says nothing about stderr. Real-world divergence: `snap merge ../other > out.txt` (stderr still a TTY) renders warnings plain instead of terminal; `snap status 2> err.txt` (stdout TTY) treats piped stderr as terminal. This is precisely the area the harness cannot see — all 28 cases pipe both streams (no PTY; TEST-HARNESS.md notes this), so the suite is blind to it. The pure `resolvePresentation` is correct and unit-tested with independent flags (`RenderSpec.scala:39–59`); only the process wiring collapses them. Cosmetic only — never affects plain-mode bytes, data, or exit codes.

**Repro (manual):** `snap status > /tmp/out.txt` in an interactive shell → observe plain stderr though stderr is a TTY.

**Fix:** split into `stdoutIsTty`/`stderrIsTty` in `CmdEnv` using per-fd isatty (JNI/JNA; JVM has no portable API), or document the single-probe approximation in CONTRACT §5/§14 and pin it with a unit test.

### F2 — Minor: `run_tests` assembly-jar location is order-undetermined with an incomplete staleness check (latent stale-artifact risk)
**Evidence:** `harness/snap/run_tests` (scala branch):
```bash
jar="$(find "$implementation_root/target" -path '*/scala-*/*-assembly-*.jar' -type f 2>/dev/null | head -n 1)"
if [ -z "$jar" ] || [ -n "$(find "$implementation_root/src" "$implementation_root/build.sbt" -type f -newer "$jar" -print -quit 2>/dev/null)" ]; then
  ... sbt -batch assembly ...
  jar="$(find ... | head -n 1)"   # re-find, still unordered, no re-validation
fi
```
- `find | head -n 1` has filesystem-dependent (unspecified) order. It is deterministic today **only by accident**: exactly one matching jar exists (`snap/target/scala-3.3.8/snap-assembly-0.1.0.jar`, verified by directory listing; name pinned by `assemblyJarName` in `build.sbt`).
- If a second match appears (e.g., `scalaVersion` bump leaving `target/scala-3.3.7/…-assembly-….jar`, or a `version` bump), `head -n 1` can select the stale jar — including immediately after a rebuild, since the post-build re-find is not re-validated or mtime-sorted.
- Staleness check omits `project/plugins.sbt` (sbt-assembly 2.3.1) and `project/build.properties` (sbt 1.10.7); toolchain changes there silently reuse the old jar.
- Direct answer to the task question: the jar path is **not** pinned to `target/scala-3.3.8/snap-assembly-0.1.0.jar`; it's glob-only.

**Impact:** false-green acceptance runs (testing stale bytecode). Not reproducible on current clean state or fresh CI checkouts — hence minor, not major.

**Repro sketch:** `cd snap && cp -r target/scala-3.3.8 target/scala-3.3.7 && touch src/main/scala/snap/Main.scala` → observe which jar the two `find | head -n 1` calls pick (order undefined).

**Fix:** select newest by mtime (`find … -print0 | xargs -0 ls -1t | head -n 1`), add `"$implementation_root/project"` to the `-newer` set, or pin the exact path derived from known scalaVersion/version.

### F3 — Minor: Unicode-filename tests under harness-enforced `LANG=C` rely on unpinned JVM filename encoding
**Evidence:**
- Harness forces `LANG=C`, `LC_ALL=C` on candidate processes (`harness/snap/test-harness/src/process.ts:27–28`).
- Test 25 creates/asserts filenames `é` and `😀` and their unsigned-UTF-8 order `nested/file < z < é < 😀` (`harness/snap/tests/25-config-version-path-boundaries.yaml`).
- The wrapper passes only `-Dsun.misc.unsafe.memory.access=allow`; no `-Dsun.jnu.encoding` / `-Dfile.encoding` anywhere in the repo (grep: zero matches).
- JVM filename byte↔String conversion uses `sun.jnu.encoding`, which on some JDK/OS/locale combinations follows the C locale (ASCII), mangling non-ASCII names.

**Assessment:** 28/28 green on this machine and JDK 25 (JEP 400-era JDKs treat C/POSIX as UTF-8 aggressively), so correctness currently holds — but via JDK behavior, not explicit wiring. A JDK/platform change could break test 25 and real unicode-path repos. Latent environment coupling rather than active defect.

**Fix:** add `-Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8` to the wrapper's `java` line (harmless if redundant), or document the reliance in CONTRACT §14.

### F4 — Nit: `SNAP_DEBUG` read directly from process env, bypassing the CmdEnv seam
**Evidence:** `snap/src/main/scala/snap/Commands.scala:107,457` — `java.lang.System.getenv("SNAP_DEBUG")`. Contradicts `Main.scala` scaladoc ("The process boundary is the only place that touches ambient state… Everything is captured once into a CmdEnv snapshot") and is absent from CONTRACT §14's environment table.
**Impact:** debug-only (exit-2 defect stack traces); never affects success paths; harness env excludes it. Purity/doc inconsistency, not overfitting.
**Fix:** fold into `CmdEnv` or document in CONTRACT §14.
*(Duplicate of E3 finding M2.)*

### F5 — Nit: exact-case `.snap` matching vs case-insensitive filesystems
**Evidence:** `WorkingTree.scala:25` (`SnapDirName = ".snap"`, skip `isTop && name == SnapDirName`); `RepoIo.discoverRepo` uses `Files.isDirectory(cur.resolve(".snap"))`. On case-insensitive volumes (default macOS APFS), a user dir `.SNAP` satisfies discovery but is not skipped by the exact-case scan, so its contents would be tracked. SPEC §2 ("no … case normalization") makes exact matching defensible; the asymmetry is filesystem-dependent and untested.
**Fix:** none required; optionally document a case-sensitive-filesystem assumption.

### F6 — Nit: vendored-harness drift / doc inconsistency (no runtime impact)
**Evidence:** `harness/README.md` claims vendored harnesses are "unmodified snapshots" and lists only `tabbyshell/`, but `harness/snap/run_tests` is a Scala-specific adaptation (`--implementation-root` required; rejects ts/rust with "this workspace only implements the Scala edition"; sbt build + Unsafe-flag wrapper) differing from upstream `capstones/snap/run_tests` (auto-selects latest-modified language, delegates to `capstones/snap/run`). `harness/snap/TEST-HARNESS.md` referenced by the upstream layout is absent from the vendored copy.
**Fix:** update `harness/README.md` (list snap, describe adaptations) and/or re-vendor TEST-HARNESS.md.

## Verified clean (checked, no issue)

1. **Wall-clock/timezone:** zero matches for `Instant.now`, `System.currentTimeMillis`, `java.time.*` now/clock APIs, `ZoneId`, `TimeZone`, `Calendar`, `new Date(` in `snap/src/main/scala`. SPEC's no-wall-clock claim holds.
2. **Locale:** no locale-sensitive formatting; only `f"\\u${c.toInt}%04x"` hex escapes in `Json.scala:695–705` (locale-independent by definition). Harness pins `LANG=C LC_ALL=C` regardless.
3. **Hardcoded paths/hosts/ports:** none beyond spec-mandated `127.0.0.1` (`HttpServe.scala:65`, `Render.scala:174`) and port default 8765 (`Model.scala:110`, SPEC §7.9). `scripts/env.sh` hardcodes `/opt/homebrew/opt/openjdk@25/...` but is gitignored (`.gitignore`: `scripts/`), README-documented as local convenience; CI provisions its own JDK 25 (`capstone-ci.yml`).
4. **Network discipline:** production network I/O confined to `HttpServe` (loopback server) and `HttpFetch` (user-supplied operand, SPEC §9, one GET, no redirects — `HttpFetch.scala:26–48`). Unit tests bind/connect only `127.0.0.1` OS-assigned ports; `example.com` appears only as a string predicate in `isHttpUrl` (`HttpSpec.scala:413`, no request); connection-refused test targets `127.0.0.1:1` (`HttpSpec.scala:402`, loopback, no DNS). No DNS lookups anywhere in tests.
5. **No test-knowledge leakage:** greps for YAML fixture strings (`a@x`, `alice@example.com`, `file.txt`, `added.txt`, `same@x`, `remote@x`, …) in `snap/src/main/scala` → zero matches; they exist only under `src/test/scala`. Error strings are the frozen CONTRACT set in `SnapError.scala`.
6. **Deterministic iteration:** every output-affecting path sorts explicitly — scan sorts children per directory level by unsigned UTF-8 (`WorkingTree.scala:78–82`); `compare`, `buildChanges`, `diffEntries` sort via `Model.utf8Compare`; `Render.status`/`Render.diff` re-sort; `Codec.checkContiguity` sorts authors; replay warnings `distinct.sorted(byPathThenReason)` (`Replay.scala:170`); `Json.writeRepository` fixed field order over validated-sorted vectors. All HashMap/HashSet uses traced (Codec collision/reachability, Replay memo/namespace sets, Json duplicate-key `seen`) are membership-only or re-sorted before output; `Replay.integratePatch` set iterations can't affect the resulting tree map or sorted warnings.
7. **JDK 25 assumptions:** only `sun.misc.*` use is `sun.misc.Signal`/`SignalHandler` in `Commands.installSignalHandlers` (`Commands.scala:469–492`) — documented raw-JVM exception in code comment (SPEC §14/CONTRACT §12) and progress log; previous handlers restored via `acquireReleaseExit`. No reflection hacks otherwise.
8. **Java 25 stderr noise:** mitigated for harness runs — `io.netty.transport.noNative=true` set in `Main` object initializer before any Netty class init (`Main.scala:17–21`); `Runtime.removeDefaultLoggers` (`Main.scala:25–26`); wrapper flag `-Dsun.misc.unsafe.memory.access=allow` with explanatory comment (`harness/snap/run_tests`); `HttpSpec` has an explicit noise test. Caveat: the Unsafe suppression lives only in the wrapper, so standalone `java -jar snap-assembly-0.1.0.jar` prints one-time Unsafe WARNINGs on Java 25 — outside the SPEC's harness-scoped stderr contract, noted for awareness.
9. **Resource leaks / `--serve` shutdown:** server installed in ambient ZIO Scope; signal → Promise → scope close → server shutdown + handler restore → exit 0 (validated by test 12 SIGTERM/SIGINT assertions). `Files.list`/`Files.walk` closed in `finally` (`WorkingTree.scala`); atomic-write temp file cleaned on failure (`RepoIo.writeRepositoryAtomic` finally-block); `HttpFetch` provides `Client.default` per one-shot call. No error-path leaks found.
10. **cwd/HOME assumptions:** cwd captured once at boundary (`Path.of("").toAbsolutePath`); HOME optional and absence handled (`Config.resolveContributor`, test 19 `HOME: null` steps); discovery walks up from cwd. Sandbox isolation per-case via `mkdtempSync` + confined `sandboxPath`. Harness TS sources contain no machine-specific values (loopback + `os.tmpdir()` only).

---

## Structured summary

- **Lane:** E2 — overfitting / environment (Snap, Scala)
- **Status:** complete — static read-only review, all 16 production sources + harness wiring + 28 YAML cases + CI examined
- **Report path:** `reviews/snap/02-overfitting-environment.md` (persisted by parent; reviewer lane had no write tool)
- **Counts:** blocker 0 · major 0 · minor 3 · nit 4

**Top 5 findings:**
1. (Minor) TTY detection uses one `System.console()` flag for both streams, violating SPEC §7.11's independent per-stream auto selection — invisible to the pipe-only harness (Main.scala:43, Commands.scala:82).
2. (Minor) `run_tests` finds the assembly jar via unordered `find | head -n 1` glob with staleness check omitting `project/` — latent stale-jar false-green after version bumps (harness/snap/run_tests).
3. (Minor) Unicode-filename test 25 runs under harness-forced `LANG=C` with no `-Dsun.jnu.encoding=UTF-8` pin — green today via JDK 25 behavior, not explicit wiring.
4. (Nit) `SNAP_DEBUG` read via `System.getenv` inside Commands, bypassing the documented CmdEnv seam (Commands.scala:107,457).
5. (Nit) Exact-case `.snap` matching diverges on case-insensitive filesystems; plus vendored-harness/README drift (no TEST-HARNESS.md vendored).

**Bottom line:** no evidence of behavior tuned to the 28 public tests or this machine; no blockers or majors. Merge verdict: **OK with notes** (three minor hardening fixes recommended, all in wiring/robustness rather than product behavior).
