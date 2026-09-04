# Task F-io-http: IO boundary hygiene (H1, E5-F3, E4-P1, E4-P2, E4-P3)

**Branch:** `task/12-io-http`
**Worktree:** `/private/tmp/snap-fio`
**Files you own (modify ONLY these, plus adding a new small source file if genuinely needed):**

- `snap/src/main/scala/snap/WorkingTree.scala`
- `snap/src/main/scala/snap/RepoIo.scala`
- `snap/src/main/scala/snap/Config.scala`
- `snap/src/main/scala/snap/HttpServe.scala`
- `snap/src/main/scala/snap/HttpFetch.scala`
- `snap/src/test/scala/snap/WorkingTreeSpec.scala`
- `snap/src/test/scala/snap/RepoIoSpec.scala`
- `snap/src/test/scala/snap/ConfigSpec.scala`
- `snap/src/test/scala/snap/HttpSpec.scala`

**Do NOT touch:** `Model.scala`, `Diff.scala`, `Ot.scala`, `Replay.scala`, `Codec.scala`,
`SnapError.scala`, `Cli.scala`, `Render.scala`, `Commands.scala`, `Main.scala`, anything under
`harness/`. Other lanes are editing those files concurrently.

## Context

Scala 3.3.8 / ZIO 2.1.26 / zio-json / zio-http 3.x capstone implementation of the Snap spec.
Ground truth: `harness/snap/SPEC.md`, frozen contract `docs/snap/CONTRACT.md`, and the 28 YAML
acceptance cases in `harness/snap/tests/`. The codebase currently compiles and all 481 unit tests
+ 28 harness cases pass. Keep it that way.

## Findings to address

### H1 — NIO side effects not properly wrapped (human reviewer)

Human review: "there are side effects using nio, that are not wrapped in ZIO, e.g. in WorkingTree.
Wrap them properly or use zio.nio. That would also allow nicer code in other places, where we
basically have java code wrapped in ZIO.attempt."

Current state: `WorkingTree.scan` and `WorkingTree.materialize` wrap large imperative blocks
(`walkDir`, `processEntry`, `materializeOrError`, `deleteRecursively`, `pruneEmpty`,
`ensureParentDirs`) in a single coarse `ZIO.attemptBlocking(...)`, and the helpers use
early-return `Either` style that reads like imperative Java.

Requirements:

1. Restructure `WorkingTree` so filesystem side effects are expressed as ZIO effects composed
   with for-comprehensions / combinators, instead of one large `Either`-returning imperative block
   inside a single `attemptBlocking`. Preserve exactly:
   - deterministic unsigned-UTF-8 child ordering,
   - first-error-wins failure semantics in that deterministic order,
   - `.snap/` skipping at the top level only,
   - symlink / non-regular entry rejection via `UnsupportedEntry`,
   - `materialize` behavior: write files before `repository.json` is written by callers,
     create parent dirs, delete removed paths, prune newly-empty ancestor dirs, never touch `.snap/`.
2. Apply the same treatment to `RepoIo` (discovery, read, atomic write) and `Config`
   (read/write local + global).
3. You MAY use `zio-nio` (`"dev.zio" %% "zio-nio"`) if you judge it produces cleaner code. If you
   add it: add the dependency to `snap/build.sbt`, run `sbt --client shutdown` after editing build
   files before building, and verify `assembly` still produces a working fat jar (check
   `assembly / assemblyMergeStrategy` if META-INF conflicts appear). If zio-nio causes friction,
   fine-grained `ZIO.attemptBlocking` per NIO operation is fully acceptable — the goal is idiomatic
   effect structure, not a specific dependency.
4. Public signatures of `scan`, `materialize`, `isClean`, `compare`, `discoverRepo`,
   `loadRepository`, `writeRepositoryAtomic`, `Config.read`/`Config.writeLocal`/`writeGlobal`
   should keep the same shape (`IO[SnapError, ...]` / `Either`) so other lanes' code keeps
   compiling. If you must change a signature, keep it source-compatible with current call sites
   in Commands.scala (you do NOT own Commands.scala).

### E5-F3 — scan must validate tracked-path rules

`WorkingTree.scan` currently never calls `Model.validatePath`; only `Commands.buildChanges`
validates. So `status`/`diff` happily list files named e.g. `bad\name` or containing control
characters, while `commit` rejects them. SPEC §2 defines tracked-path rules and §10 says scanning
commands fail on unsupported entries.

Fix: during scan, validate each collected relative path with `Model.validatePath` and fail with
the existing `SnapError.InvalidRepoPath` error (same message commit produces). Keep deterministic
first-failure order (unsigned UTF-8 sorted traversal). Add WorkingTreeSpec tests: a file with a
backslash in its name makes scan fail with the invalid-path message; a control-character name too;
`.snap` itself still skipped; valid unicode names still work.

### E4-P3 — strict UTF-8 decoding of repository.json / config.json

`RepoIo.loadRepository` and `Config.readConfigId` use `new String(bytes, UTF_8)`, which silently
replaces invalid byte sequences with U+FFFD. Fix: decode strictly (e.g. reuse `Model.decodeUtf8`
or a `CharsetDecoder` with `CodingErrorAction.REPORT`) and fail with `SnapError.InvalidJson(...)`
(exit 1, `snap: invalid JSON: ...` shape) on malformed UTF-8. Add tests in RepoIoSpec/ConfigSpec
writing raw invalid bytes (e.g. `0xFF 0xFE`) and asserting a Left with the InvalidJson message.

### E4-P1 — port-in-use must be a typed error (exit 1), not a defect (exit 2)

`HttpServe.serve` maps typed failures to `IoFailure`, but a bind failure from zio-http currently
surfaces as a defect, so `snap --serve <busy-port>` prints `snap: internal error` and exits 2.
Fix in `HttpServe.serve` (e.g. `.sandbox.catchAll` / `catchAllCause` mapping any bind failure to
`SnapError.IoFailure("failed to start HTTP server: …")`). Add an HttpSpec test: bind a plain
`java.net.ServerSocket` (or a zio-http server) on an ephemeral port, then assert
`HttpServe.serve(...)` on that port fails with `IoFailure` containing "failed to start HTTP
server". Verify the message shape still satisfies SPEC §10's `snap: <detail>` one-line rule.

### E4-P2 — HEAD must return the same headers as GET

SPEC §9: "HEAD returns the same status and headers without a body." Currently HEAD returns
`content-length: 0` while GET returns the snapshot length. Fix: respond to HEAD with the same
headers as GET, including `Content-Length` equal to the snapshot's UTF-8 byte length, and an
empty body. Extend the raw-socket tests in HttpSpec to pin: HEAD status 200, `content-type`
identical to GET, `content-length` equal to GET's body byte length, zero body bytes. Make sure
harness test 12 still passes.

## Definition of done

1. Red tests written first for each behavior change (E5-F3, E4-P3, E4-P1, E4-P2); captured red
   output noted in your report.
2. All fixes implemented; behavior for every existing test unchanged.
3. Full gate green in the worktree:

   ```bash
   source /private/tmp/snap-fio/scripts/env.sh
   cd /private/tmp/snap-fio/snap
   sbt --client "compile; test; assembly; scalafmtCheckAll"
   sbt --client shutdown
   ```

4. Acceptance harness green (28/28) from the worktree:

   ```bash
   cd /private/tmp/snap-fio
   bash harness/snap/run_tests --lang scala --implementation-root "$PWD/snap"
   ```

5. Commits on `task/12-io-http` with messages like
   `fix(snap): <scope> — <summary> (E4-P1)` etc., author `Snap dev <capstone-dev@local>`,
   NO `Co-Authored-By` or AI trailers. Push: `git push origin task/12-io-http`.

## Safety rules (strict)

- Work ONLY inside `/private/tmp/snap-fio`. Never modify, move, or delete anything outside it,
  except `git push origin task/12-io-http`.
- NEVER run `rm -rf` outside your worktree; no destructive git commands (no `reset --hard` on
  shared refs, no force push, no branch deletion). Test scratch dirs go under
  `/tmp/snap-fio-scratch/` and are cleaned up explicitly.
- NEVER touch anything under `harness/`.
- `sbt --client` only; shut down the server when done.

## Report format (final message, keep under ~400 words)

Lane id, status (done/blocked), branch + commit SHAs, files changed, tests added/total, gate
evidence (test counts, harness N/28), findings addressed per ID, parked items, risks.
