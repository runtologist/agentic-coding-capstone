# Packet L5 — HTTP serve + fetch (zio-http)

**Branch:** `task/08-http` (from `develop`)
**Wave:** 4 (parallel with L4)
**Depends on:** Model/SnapError (L1), Json thin codecs (J1), Codec (L3) — all
merged on develop. `zio-http 3.3.3` is ALREADY in build.sbt (frozen — do not
edit build.sbt; the assembly merge strategy already handles Netty and the
assembly gate builds green with zio-http on the classpath).

## Goal

Implement the HTTP edge of Snap per SPEC §9 and CONTRACT §12:
1. **`HttpServe.scala`** — loopback-only server exposing one immutable
   repository snapshot with exact status/header/body control.
2. **`HttpFetch.scala`** — single-GET client with redirects disabled and full
   validation of the fetched repository.

Both are ZIO effects mapping failures into `SnapError`. L7 (Commands/Main)
will wire them into `--serve`, `merge <url>`, and `diff --repo <url>`.

## zio-http reference (verified against https://ziohttp.com docs, 3.x)

Consult the zio-http docs (https://ziohttp.com/llms.txt → reference pages)
before writing code; key facts used by this packet:
- Server config: `Server.Config.default.binding("127.0.0.1", port)` binds host
  + port. `Server.install(routes)` returns the actual bound port (use this to
  learn the OS-assigned port when the requested port is 0), then keep the
  server alive (e.g. `ZIO.never` interrupted on shutdown signal).
- Routes: `Routes(Method.GET / "repository.json" -> handler(...))`.
- Response headers: build `Response` with explicit `Headers` including
  `Header.ContentType(MediaType.application.json)` + UTF-8 charset; verify the
  exact wire value in the spike.
- Client: `Client.batched(Request.get(url))` performs one request; redirect
  following is an OPT-IN aspect (`ZClientAspect.followRedirects`) — the plain
  client does not follow redirects. Verify in the spike.

## Files owned (exclusive)
- `snap/src/main/scala/snap/HttpServe.scala` (new)
- `snap/src/main/scala/snap/HttpFetch.scala` (new)
- `snap/src/test/scala/snap/HttpSpec.scala` (new)

**Do NOT modify:** build.sbt, Main.scala, Model/SnapError/Json/Codec/Replay/
Diff/Ot/Cli/Render, docs, harness. If blocked by a frozen file, STOP and
report. **Do NOT push to** develop/main or other task branches.

## Safety rules (hard)
- Work ONLY inside your assigned worktree. Tests may bind loopback servers on
  OS-assigned ports (port 0) ONLY; never bind fixed well-known ports, never
  touch the network beyond 127.0.0.1. No destructive commands; no `rm -rf`
  outside temp dirs you created; no destructive git commands.
- `sbt --client`; `sbt --client shutdown` after the final gate.

## Read first
- `docs/snap/CONTRACT.md` §12 (every pinned HTTP behavior), §15 rulings H/I,
  §2 (exit codes/streams)
- `harness/snap/SPEC.md` §9 (HTTP repository), §7.9 (`--serve`)
- `harness/snap/tests/12-http-server.yaml` (server goldens incl. exact body
  bytes), `13-http-client.yaml` (client discipline), `26-…yaml` (malformed
  HTTP remote never mutates; exactly one GET per attempt)
- Frozen APIs: `Json.writeRepository`/`parseRepository`, `Codec.validateRepository`,
  `Model.Port`, `SnapError.HttpStatus`, `SnapError.InvalidJson`, `SnapError.IoFailure`

---

## Step 1 — Spike (record findings in your report)

Empirically verify with zio-http 3.3.3 before implementing:
1. Exact wire value of the Content-Type header for a JSON response — must be
   `application/json; charset=utf-8` (harness asserts this exact string).
2. Actual-port discovery: bind port 0 via `Server.Config` + `Server.install`,
   read back the bound port.
3. Routing strictness: does `GET /repository.json?query=not-exact` hit the
   `/repository.json` route? If yes, the handler must reject any non-empty
   query/fragment itself with 404 (test 12 requires 404 for the query form).
4. 405 handling: for methods other than GET/HEAD on `/repository.json`, the
   response must carry status 405 and header `Allow: GET, HEAD` (harness
   checks lowercase header name `allow`, value `GET, HEAD`). Determine whether
   zio-http auto-generates 405s from the route table and whether you must set
   `Allow` manually (likely manual via a catch-all handler for the path).
5. HEAD zero-body: HEAD must return 200 + same headers + literally zero body
   bytes (raw-socket assertion in test 12).
6. Default logging: confirm `Server.serve`/`install` and the Netty layer print
   NOTHING to stdout/stderr at startup or per-request (test 12 asserts stdout
   is exactly the URL line and stderr is empty). Suppress any default access
   log / startup banner you find.
7. Client redirect behavior: confirm the plain batched client does NOT follow a
   302 and returns the 302 response itself.
8. Assembly: confirm `sbt --client assembly` still produces a runnable jar
   with zio-http (it did at last gate; re-verify with a smoke run of the jar).

## Step 2 — `HttpServe.scala`

```scala
object HttpServe {
  /** Serve ONE immutable snapshot. `body` must be exactly
    * Json.writeRepository(repo) computed ONCE at startup (two-space pretty,
    * trailing LF — test 12 pins these exact bytes; later commits must not
    * change what is served). L7 validates the repo BEFORE calling this.
    *
    * Binds 127.0.0.1 only. port.value == 0 → OS-assigned.
    * Emits the bound port via the returned value/stream so L7 can print
    * `http://127.0.0.1:<actual-port>/repository.json\n` (always plain, even
    * under SNAP_COLOR=always — ruling I) exactly once, flushed, to stdout.
    *
    * Routes (exact path `/repository.json`, no query allowed):
    *   GET  → 200, Content-Type: application/json; charset=utf-8, body=snapshot
    *   HEAD → 200, same headers, ZERO body bytes
    *   other methods on that path → 405 + `Allow: GET, HEAD`
    *   any other path (incl. query strings on the resource path) → 404
    *
    * Runs until the returned server is interrupted (L7 installs SIGTERM/SIGINT
    * handlers that interrupt cleanly → process exits 0). */
  def serve(body: String, port: Model.Port): <ZIO that yields bound port and runs until interrupted>
}
```

Design note: expose the bound port to the caller BEFORE blocking forever —
e.g. `serve(...): ZIO[Scope, SnapError, Port]` installing the server via
`Server.install`, or a `Promise[Nothing, Int]` hand-off. L7 prints the URL and
awaits a shutdown signal effect. Keep signal handling OUT of this module
(L7 owns it); just make `serve` cleanly interruptible.

## Step 3 — `HttpFetch.scala`

```scala
object HttpFetch {
  /** One GET of the exact URL. No redirects followed — a 3xx (or any
    * non-200) fails with SnapError.HttpStatus(status, url) whose detail
    * contains `HTTP <status>` (test 13 asserts stderr contains `HTTP 302`).
    * Body must parse via Json.parseRepository (malformed → InvalidJson whose
    * detail contains `invalid JSON`, test 13) and validate via
    * Codec.validateRepository (test 26: unknown field on HTTP remote →
    * `snap: ...`, no local mutation, exactly one GET per attempt).
    * Network/connection failures → IoFailure(detail). */
  def fetchRepository(url: String): IO[SnapError, Model.Repository]
}
```

- Use `Client.batched` (or scoped streaming with body fully read) — exactly
  ONE request per call, no retries, no redirect following.
- Accept both `http://` and `https://` URLs syntactically (https never
  exercised offline; ruling 4 — transport-agnostic validation only).
- Reasonable client timeout (e.g. 10–30 s) mapped to IoFailure.

## Step 4 — Tests (`HttpSpec.scala`, zio-test, ≥20 tests)

Loopback only, port 0 for every real server. Cover:
- GET `/repository.json` → 200, exact content-type, body byte-identical to
  `Json.writeRepository` of the served repo (use the test-12 golden repo:
  format 1, frontier [[a@x,1]], one patch with insert ["one\n"])
- snapshot immutability: serving is from the startup string — calling serve
  with a fixed body returns that body regardless of later "commits" (model by
  passing two different bodies in separate tests)
- HEAD → 200 + zero body bytes (read the raw body bytes and assert empty)
- POST/PUT/DELETE → 405 with `Allow: GET, HEAD` header
- unknown path → 404; `/repository.json?query=x` → 404
- bound-port discovery: serve with Port 0 yields a nonzero actual port
- client: 200 + valid body → Right(Repository) structurally equal
- client: 302 → Left(HttpStatus(302, _)) and detail contains `HTTP 302`
- client: 200 + `not-json` → Left(InvalidJson) containing `invalid JSON`
- client: 200 + structurally invalid repo (unknown field) → Left(validation error)
- client: exactly one request observed per fetch (test with a local counting
  server you control in the spec)
- connection refused → IoFailure

## Definition of done

1. Gates: `sbt --client shutdown` (ignore failure), then
   `source scripts/env.sh && cd snap && sbt --client "compile; test; assembly; scalafmtCheckAll"`
   all green (333 pre-existing tests untouched + your new ≥20), then
   `sbt --client shutdown`.
2. Smoke: run `java -Dsun.misc.unsafe.memory.access=allow -jar target/scala-3.3.8/snap-assembly-0.1.0.jar --version`
   still prints `snap 1.0.0` (assembly sanity).
3. Only the three owned files created/modified.
4. Commit on `task/08-http`; `git push -u origin task/08-http`. No
   Co-Authored-By trailers. If push fails on SSH agent, report it.
5. Report: spike findings (all 8 questions with observed zio-http behavior),
   files + line counts, test counts, gate tails, deviations with
   justification, integration notes for L7 (exact serve startup/shutdown call
   sequence incl. where SIGTERM/SIGINT handling and URL printing belong, and
   the fetch→validate call chain for merge/diff --repo).
