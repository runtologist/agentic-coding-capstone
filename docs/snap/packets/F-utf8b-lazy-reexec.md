# Task F-utf8b — Lazy JNU re-exec (fix CI test 28 timeout)

**Branch:** `task/15-lazy-jnu-reexec` · **Worktree:** `/private/tmp/snap-fu2`
**Owned files (modify ONLY these):**

- `snap/src/main/scala/snap/Main.scala`
- `snap/src/main/scala/snap/WorkingTree.scala`
- `snap/src/test/scala/snap/MainSpec.scala`
- `snap/src/test/scala/snap/WorkingTreeSpec.scala`
- Optionally a new `snap/src/main/scala/snap/Jnu.scala` and `snap/src/test/scala/snap/JnuSpec.scala`

Do NOT touch any other production or test file. NEVER touch `harness/`.

## Problem

The current eager re-exec in `Main.run` fires on **every** invocation when `sun.jnu.encoding`
is not UTF-8 (which is always true under the harness's `LANG=C` on Linux). This doubles JVM
startup for every single run-step. Test 28 has 31 run steps inside a 30-second case timeout and
now times out on CI.

## Required change

Replace the eager re-exec with a **lazy** one that only fires when a non-ASCII filename is
actually encountered:

1. **Detection in `WorkingTree.scan`:** after decoding each entry name via
   `entry.getFileName.toString`, check whether it contains `'\uFFFD'` (the Unicode replacement
   character). If it does AND the JVM is in lossy-jnu mode (see below), throw a package-private
   control exception (e.g. `Jnu.ReexecRequired`). This fires before any output is produced, so
   nothing is half-printed.

2. **Detection in `WorkingTree.materialize`:** before writing or deleting anything, check whether
   any key in the target tree (or any path about to be deleted from current) contains a character
   > 0x7F. If so and lossy-jnu mode is active, throw `Jnu.ReexecRequired`. This prevents
   writing files with mangled names.

3. **`Jnu` helper** (new file or inside Main — your choice):
   - `lazy val lossyRisk: Boolean` — true iff `sun.jnu.encoding` (normalized: uppercase,
     strip `-`/`_`) does NOT contain "UTF8", AND `SNAP_JNU_REEXEC` env is unset, AND
     CodeSource path ends in `.jar`. Under `sbt test` (classes dir, not jar) this is always
     false, so unit tests are unaffected.
   - Pure predicates for unit testing:
     `def decodedNameNeedsReexec(lossy: Boolean, name: String): Boolean`
     `def writeNeedsReexec(lossy: Boolean, paths: Iterable[String]): Boolean`

4. **`Commands.finish`** (or wherever defects are caught): if the defect is
   `Jnu.ReexecRequired`, return a sentinel code (e.g. `private[snap] val ReexecCode = 254`)
   **without printing anything** — no error line, no debug trace.

5. **`Main.run`**: remove the eager re-exec block. Instead, after `Commands.run` returns,
   check if the code equals `ReexecCode`. If so, call `attemptJnuReexec(args)` (the existing
   helper, unchanged) and exit with the child's code. If spawn fails, print
   `snap: internal error\n` to stderr and exit 2.

6. **No other behavior changes.** All existing tests must pass unchanged. The only new observable
   difference: on a lossy-jnu system, a command that touches a non-ASCII filename will silently
   re-exec once and produce correct output; commands that only touch ASCII names will not re-exec.

## TDD

1. Write `JnuSpec` (or add to MainSpec) testing the pure predicates:
   - `decodedNameNeedsReexec(true, "caf\uFFFD") == true`
   - `decodedNameNeedsReexec(true, "cafe") == false`
   - `decodedNameNeedsReexec(false, "caf\uFFFD") == false`
   - `writeNeedsReexec(true, List("a/b", "café")) == true`
   - `writeNeedsReexec(true, List("a/b", "c")) == false`
   - `writeNeedsReexec(false, List("café")) == false`
2. Run → red (Jnu object doesn't exist yet).
3. Implement, run → green.
4. Full gate.

## Gates

```bash
source scripts/env.sh && cd snap
sbt --client "compile; test; assembly; scalafmtCheckAll"
sbt --client shutdown
cd ..
bash harness/snap/run_tests --lang scala --implementation-root "$PWD/snap"   # expect 28/28
```

## Safety rules (strict)

- Work ONLY inside `/private/tmp/snap-fu2`. Scratch under `/tmp/snap-fu2-scratch/` if needed.
- No `rm -rf` / `mv` outside your worktree. No destructive git. No harness edits. No new deps.
- Kill any process you start. `sbt --client shutdown` when done.
- Git identity: `Snap dev <capstone-dev@local>`. No Co-Authored-By or AI trailers.

## Report (<400 words)

Lane id, status, branch, commit SHAs, files changed, tests added/total, gate evidence
(unit count + harness N/28), how eager re-exec was replaced, sentinel value used,
perf check result, parked items, risks.
