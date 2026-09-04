# F-utf8 — Guarded JVM re-exec for UTF-8 filename handling (CI blocker)

**Lane:** F-utf8 · **Worktree:** `/tmp/snap-fu` · **Branch:** `task/10-jnu-reexec` (from `main` @ d593590)
**Owned files (modify ONLY these):** `snap/src/main/scala/snap/Main.scala`, `snap/src/test/scala/snap/MainSpec.scala`
**Rank:** Blocker (CI test 25 red on Linux).

## Problem

The acceptance harness spawns the candidate with `LANG=C LC_ALL=C` (harness/snap/test-harness/src/process.ts:27-28).
On Linux, the JVM derives `sun.jnu.encoding` (filename byte↔String conversion) from the locale, so under
`LANG=C` it becomes `ANSI_X3.4-1968` (ASCII). `WorkingTree` reads filenames via `Path.getFileName.toString`,
so UTF-8 names like `é` (2 bytes) and `😀` (4 bytes) decode to U+FFFD replacement characters.

CI failure (test 25, step 32, `snap status`):

```
--- expected ---
version ()
A nested/file
A z
A é
A 😀
--- actual ---
version ()
A nested/file
A z
A ��
A ����
```

Verified constraints (do NOT re-litigate):
- `-Dsun.jnu.encoding=UTF-8` on the command line is IGNORED by JDK 25 (empirically verified).
- `System.setProperty("sun.jnu.encoding", ...)` at runtime is too late: `jdk.internal.util.StaticProperty`
  caches it during JVM bootstrap before app code runs (empirically verified).
- The harness (`harness/` directory) must NEVER be modified — the wrapper script is harness-generated.
- macOS JDK always uses UTF-8 for jnu regardless of LANG, so this cannot be reproduced locally; CI (Linux) is the oracle.

## Required fix

Add a guarded process re-exec at the very start of `Main.run`, before any filesystem access:

1. **Decision (pure, unit-tested):** re-exec is needed iff ALL of:
   - `System.getProperty("sun.jnu.encoding")` normalized (uppercase, strip `-`/`_`) does NOT contain `UTF8`;
   - env var `SNAP_JNU_REEXEC` is not set (recursion guard);
   - the app is running from an assembly jar (`Main.getClass.getProtectionDomain.getCodeSource.getLocation`
     path ends in `.jar`). Under `sbt run`/`sbt test` (classes dir) do NOT re-exec.
2. **Re-exec:** build command `[<java.home>/bin/java, -Dsun.misc.unsafe.memory.access=allow, -Dfile.encoding=UTF-8, -jar, <jarPath>, <original args…>]`
   via `ProcessBuilder`; copy the parent environment and override `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`, and set
   `SNAP_JNU_REEXEC=1`; `inheritIO()` so stdout/stderr/stdin pass through byte-exact; do NOT change the working
   directory (harness sets cwd per step).
3. **Signals:** before waiting, install no-op `sun.misc.Signal` handlers for `TERM` and `INT` in the parent
   (the harness signals the whole process group, so the child receives the signal directly and performs its own
   graceful shutdown; the parent must survive to relay the child's exit code). This mirrors the existing
   documented `sun.misc.Signal` exception in `Commands.installSignalHandlers`.
4. **Exit:** `child.waitFor()`, then return the child's exit code through the normal `exit(ExitCode(code))`
   path. If spawning fails (IOException), fall through to normal execution (degrade gracefully, no crash).
5. Parent must print NOTHING extra to stdout/stderr (byte-exact harness assertions).
6. Keep existing behavior: Netty `noNative` property and `Runtime.removeDefaultLoggers` stay as-is.

Structure for testability: extract pure helpers, e.g.
`private[snap] def jnuNeedsReexec(jnuEncoding: Option[String], guardEnv: Option[String], jarPath: Option[String]): Boolean`
and `private[snap] def reexecCommand(javaHome: String, jarPath: String, args: Seq[String]): List[String]`.
The side-effecting spawn can be a separate private method invoked from `run`.

## TDD (red-green)

1. FIRST write failing `MainSpec` cases for the pure helpers:
   - `jnuNeedsReexec`: `Some("ANSI_X3.4-1968")`+no guard+jar path → true; `Some("UTF-8")`/`Some("utf8")` → false;
     guard `Some("1")` → false; non-jar path (classes dir) → false; `None` encoding → false (assume fine).
   - `reexecCommand`: exact expected command list including flags and arg passthrough.
   Run `sbt --client test` and capture the red output.
2. Implement minimally until green.
3. Do not weaken or delete existing MainSpec assertions.

## Gates (all must pass before commit)

```bash
source scripts/env.sh   # copied into your worktree
cd snap
sbt --client "compile; test; assembly; scalafmtCheckAll"
sbt --client shutdown
cd ..
bash harness/snap/run_tests --lang scala --implementation-root "$PWD/snap"   # expect 28/28
```

Note: on macOS the re-exec path will not fire (jnu is already UTF-8); the local harness validates the
no-regression path. The re-exec branch itself is validated by CI on Linux after merge.

## Safety rules

- Work ONLY inside `/tmp/snap-fu`. Never modify anything outside it except `git push origin task/10-jnu-reexec`.
- No `rm -rf`/`mv` against paths outside your worktree; no destructive git commands (no reset --hard on shared
  refs, no push --force, no branch deletion). NEVER touch `harness/`.
- Git identity: `git config user.name "Snap dev"; git config user.email "capstone-dev@local"`.
  NO Co-Authored-By or AI trailers in commit messages.
- Use `sbt --client` for all builds. Run `sbt --client shutdown` after any build.sbt/project change and at the end.
- Do not start background servers that outlive a command; kill anything you start.

## Finish

Commit on `task/10-jnu-reexec`, push the branch to origin, then report: lane id, status, branch, commit SHAs,
files changed, tests added/total, gate evidence (test counts, harness result), parked items, risks.
