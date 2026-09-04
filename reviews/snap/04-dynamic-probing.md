# Lane E4 — Dynamic Adversarial Probing (Snap / Scala)

**Status:** PARTIAL — worker run `e250d42c` executed ~90 probe commands over 71 turns but
hit its 30-minute timeout before writing this report. Parent reconstructed findings from
the worker transcript/output log. Untested remainder: broken-pipe/stdin edges, explicit
determinism double-run (indirectly covered by ReplaySpec permutation tests), merge
directionality beyond cases already probed.
**Artifact dir:** `/tmp/snap-probe-e4/` (probe scripts + results retained for reference)
**Target:** main @ 8ab113e, jar rebuilt by worker from HEAD.

## Confirmed findings

### P1 — MINOR: `--serve` on an in-use port exits 2 with `snap: internal error` (expected-error channel is exit 1)
- **Spec:** §7.9 (serve), §10 ("expected errors exit 1, unexpected internal failures exit 2").
- **Evidence (worker probe S3):** started server on fixed port, launched second `--serve` on same port:
  ```
  === S3_second_serve (exit=2) ===
  -- stderr --
  snap: internal error
  ```
- **Code:** `HttpServe.serve` maps errors with `.mapError(t => SnapError.IoFailure(...))`
  (`HttpServe.scala:67-69`), but the bind failure evidently surfaces as a ZIO **defect**
  (not a typed failure), so `Commands.finish` renders the exit-2 internal-error path.
- **Fix:** catch the bind failure on the defect channel too (`.catchAllCause` / `.sandbox`
  and map `Cause.fail`+`Cause.die` to `SnapError.IoFailure("failed to start HTTP server: …")`),
  yielding exit 1 with a one-line `snap: <detail>` message. Add a CommandsSpec/HttpSpec test
  that binds a port then asserts the second serve exits 1 with the typed message.

### P2 — MINOR: HEAD response carries `content-length: 0` instead of GET's value
- **Spec:** §9 "HEAD returns the same status and headers without a body."
- **Evidence (worker raw-socket probe):**
  ```
  --- GET:  content-length: 429
  --- HEAD: content-length: 0
  ```
- **Code:** `HttpServe.snapshotRoutes` HEAD branch returns `Body.empty` with only the
  Content-Type header (`HttpServe.scala:51-52`); zio-http computes Content-Length from the
  empty body.
- **Fix:** set an explicit `Content-Length: <snapshot byte length>` header on HEAD (and GET)
  so headers are identical; body stays empty for HEAD. Verify on the wire via the existing
  raw-socket HttpSpec tests; keep harness test 12 green (it pins status/content-type/empty
  body but not Content-Length).

### P3 — MINOR: invalid UTF-8 bytes in `repository.json` are silently replaced with U+FFFD instead of rejected
- **Spec:** §4 (repository file is UTF-8 JSON); RFC 8259 requires valid UTF-8; §4.5 strict parsing posture.
- **Evidence (worker probes D3b/D3c):** repository.json crafted with invalid UTF-8 bytes in a
  change path parsed successfully with replacement characters and failed later with an unrelated
  semantic error:
  ```
  === D3c_bad_utf8_path (exit=1) ===
  snap: delete of absent path: a<U+FFFD>b.txt
  ```
- **Code:** `RepoIo.loadRepository` decodes with `new String(Files.readAllBytes(file), UTF_8)`
  (`RepoIo.scala:68`) and `Config.readConfigId` likewise (`Config.scala:89`) — the
  `String(byte[], Charset)` constructor uses REPLACE, not REPORT. `Model.decodeUtf8`
  (strict REPORT decoder) already exists but is only used for file-content text detection.
- **Fix:** decode repository.json/config.json bytes with the strict decoder and fail with a
  typed `snap: invalid JSON: …`-class error (exit 1) on malformed UTF-8. Check
  `HttpFetch` body decoding for the same leniency and harden if feasible.

## Verified-correct probe results (no findings)

- **CLI grammar (A1–A27):** all malformed invocations exit 1 with exact `snap: invalid command or arguments` / usage messages; `--version` exits 0 with `snap 1.0.0` and empty stderr.
- **Outside-repo errors (B1–B8):** all commands exit 1, `snap: not a Snap repository` where expected.
- **Corrupt repository matrix (D0–D15, D44–D50):** truncation, trailing garbage, unknown fields (top/patch/change), string revision, float revision, format≠1, unsorted frontier, missing patch, unreachable patch, cycle, dot collision, unsorted patches, revision gap, inconsistent dot, duplicate edit keys, unknown change type — all exit 1 with the pinned `snap: …` messages. Structurally-equal duplicate dots accepted (matches ruling on E1-N1).
- **Boundaries (E1–E11):** empty commit message rejected; empty-file create via `edit: []` OK; long path & deep nesting OK; file↔dir transitions OK; symlink and FIFO rejected with `snap: unsupported working tree entry: …` on both status and commit; 4096-byte message accepted, 4097 rejected; missing contributor rejected.
- **Unicode/binary (U1–U4):** UTF-8 file contents and names, CJK/emoji commit messages with tab escaping in log all correct (macOS runs UTF-8 jnu; the Linux LANG=C regression is tracked separately as the CI test-25 bug).
- **HTTP client (H1–H7):** 302 → `snap: HTTP 302 fetching <url>` (no redirect follow), non-JSON body → `snap: invalid JSON: …`, HTTP 500 → `snap: HTTP 500 fetching <url>`, refused → `snap: Connection refused (connect failed): /127.0.0.1:<port>`, bad URL → `snap: invalid URL: http://`; `diff --repo http://…` works and does not import; served bytes match repository.json exactly.
- **Serve behavior:** GET 200 with exact `content-type: application/json; charset=utf-8`, snapshot immutable across later commits, 405 + `allow: GET, HEAD` for POST/PUT/DELETE, 404 for other paths and query strings, SIGTERM and SIGINT both exit 0 with clean stderr.
- **Scale:** 50 sequential commits (~340 ms/inv on this machine), merge of 100 patches / 101 files in 419 ms, large multi-file diffs rendered fine. The 64M-cell diff cap (E5-F1) was not reached by these probes and remains the top code finding.
- **Colors (COL suite):** SNAP_COLOR=always emits exact ANSI sequences; never/NO_COLOR/invalid handled per spec (`snap: SNAP_COLOR must be auto, always, or never`).

## Ranked summary

| Rank | Count | Items |
|---|---|---|
| Blocker | 0 | — |
| Major | 0 | (E5-F1 diff cap remains the top major from E5) |
| Minor | 3 | P1 port-in-use exit 2; P2 HEAD Content-Length; P3 invalid-UTF-8 replacement |
| Nit | 0 | — |
