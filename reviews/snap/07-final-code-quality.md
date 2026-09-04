# Final Review — Code Quality (post-fix-round, main @ 5ebb965)

**Reviewer:** fresh-context code-quality lane (run 2bc65cbb)
**Verdict:** OK with notes. No blockers, no majors. 3 minors, 3 nits.

## Correct (verified good)

- ZIO idiom: side effects lifted via `ZIO.attemptBlocking`, typed error channel, defects reserved for documented invariants; `ZIO.scoped` + `acquireReleaseExit` for signals/server.
- Scala 3 idiom: enums, opaque types (`Port`, `PositiveSafeInteger`), exhaustive matches, no null misuse; var/while confined to hot loops with justification.
- Tests behavior-pinning, byte-exact; property tests for diff cross-checked vs naive reference.
- All Phase F fixes verified: lazy JNU re-exec, Hirschberg diff, typed bind failure, HEAD Content-Length, strict UTF-8, scan path validation, flushErr, snapDebug in CmdEnv, nextRevision guard.

## Findings

### P2-minor: URL-encoded jar path in re-exec may fail on paths with spaces
- **Location:** `Main.scala:98`, `Jnu.scala:26`
- `_.getPath` on a `java.net.URL` returns percent-encoded path. Jar at a path with spaces → `my%20projects/...` → child spawn fails → silent exit 2.
- **Fix:** `Paths.get(l.toURI).toString` instead of `.getPath`.

### P2-minor: Missing revert overflow integration test
- `commitWithRepo` has an overflow test (CommandsSpec.scala:707); `revertWithRepo` is documented "Package-visible for overflow tests" but has none.
- **Fix:** mirror the commit overflow test for revert.

### P2-minor: Platform-specific assumptions in re-exec
- `s"$javaHome/bin/java"` (no .exe for Windows); `C.UTF-8` may not exist everywhere.
- Failure is graceful (IOException → exit 2). Target platforms (macOS dev, Linux CI) both covered.
- **Ruling candidate:** accept as documented limitation; project targets macOS/Linux.

### Nit: Race window between `builder.start()` and signal handler installation (Main.scala:107-110)
- SIGTERM in that window kills parent. Extremely unlikely; fix by installing handlers before spawn.

### Nit: `.get` on Options in Replay.integratePatch rule-3 branch
- Safe (guarded by `.exists`), non-idiomatic.

### Nit: `System.setOut`/`setErr` mutation in HttpSpec:317-328
- Fragile under parallel test execution; currently mitigated. Option: `Test / parallelExecution := false`.

## CI risk assessment — all clear

No -Xfatal-warnings; scalafmt consistent; port 0 everywhere; temp dirs cleaned; sequential aspects on serve/HTTP suites; JDK 25 mitigations in place.
