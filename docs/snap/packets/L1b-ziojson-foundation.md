# Packet L1b — foundation revision: zio-json migration, typed errors, Port, ReplayWarning

**Branch:** `task/02-ziojson-foundation` (from `develop`) — work in the main
checkout, single writer.

## Safety rules (hard)
- Work ONLY under `/Users/sschenk/ziverge/vibe-coding-2-workshop/capstone-scala/snap`
  plus reading `docs/snap/` and `harness/snap/tests/`.
- NEVER run `rm -rf`, `mv`, `git clean`, `git worktree remove`, or any command
  whose path arguments point outside `snap/` — except `git` add/commit/push
  inside the repo.
- If a path looks wrong, STOP and report. Do not "clean up" anything.

## Goal
Bring the foundation layer in line with the approved architecture revision
(docs/snap/ARCHITECTURE.md, "Revision 2026-09-05"). build.sbt already lists
zio-json 0.7.43 and zio-http 3.3.3.

## Contract files (read-only)
- `docs/snap/ARCHITECTURE.md` (frozen ADT + domain types)
- `docs/snap/CONTRACT.md` §6–§8, §15 (pinned messages and rulings)
- `harness/snap/tests/12,15,23,25,27-*.yaml` (exact expected strings)
- Reference spike (informational, do NOT commit): `/tmp/snap-spikes/JsonSpike.scala`

## Files owned (exclusive)
- `snap/build.sbt` (deps already present; adjust assembly merge strategy only if needed)
- `snap/src/main/scala/snap/Json.scala` (rewrite)
- `snap/src/main/scala/snap/SnapError.scala` (refine per ARCHITECTURE ADT)
- `snap/src/main/scala/snap/Model.scala` (add Port, ReplayWarning; keep existing API stable)
- `snap/src/test/scala/snap/{JsonSpec,ModelSpec,SnapErrorSpec}.scala`

## Tasks
1. **Spike first**: run `/tmp/snap-spikes/JsonSpike.scala` via
   `sbt -batch "runMain snap.JsonSpike"` (copy it into src temporarily if
   needed, run, then DELETE the copy). Record: duplicate-key behavior of
   `zio.json.ast.Json` decode (does Obj keep both pairs or error, and with what
   message?), trailing-content behavior, `Json.Num` BigDecimal scale/precision
   for `1`, `1.0`, `1.5`, `1e2`, `9007199254740991`.
2. **Json.scala rewrite** on zio-json:
   - `parseStrict(s): Either[SnapError, Json]` — full parse; duplicate keys →
     `DuplicateJsonKey(name)` with the offending key name; trailing content →
     `InvalidJson(...)` whose detail contains `invalid JSON`; malformed input →
     `InvalidJson` with zio-json's positional detail.
   - `parseConfig(s)` — tolerate trailing bytes after the first complete JSON
     value (test 03: `{"contributor":{"id":"global@example.com"}}}}` parses;
     `not json` fails). Implement by locating the end of the first top-level
     value (string/escape-aware depth scanner), then strict-parsing the prefix.
   - `writeCanonical(json): String` — byte-identical to Node
     `JSON.stringify(v, null, 2)` + trailing `\n`: 2-space indent, one array
     element per line, `"key": value`, empty `{}`/`[]` inline, Node string
     escapes (`"` `\` `\b` `\f` `\n` `\r` `\t`, other `< 0x20` as `\u00XX`
     lowercase hex, non-ASCII raw UTF-8).
   - Typed helpers for Codec later: field lookup with unknown-field detection,
     string/int/bool/array/object extraction; integer extraction rejects
     fractional literals and enforces 1..9007199254740991 for
     revision/count contexts (`NotPositiveSafeInteger`).
3. **SnapError.scala**: replace `RepositoryInvalid(detail0)` with the granular
   typed cases in ARCHITECTURE.md's frozen ADT (one case per failure kind;
   message templates must keep every pinned substring from CONTRACT.md §7).
   Add `InvalidSnapColor`. Keep `InvalidPort(raw: String)` message
   `invalid port: <raw>`.
4. **Model.scala**: add `opaque type Port <: Int` + `Port.parse` (all-digit,
   0..=65535; else `InvalidPort(raw)`); add `enum ReplayWarning` (DeleteWins,
   LaterCreateWins, LaterPutWins, NamespaceWins, PutWins) with `path`,
   `reason`, and `render = "auto-resolved <path>: <reason>"`. Document
   `Version.join` totality in scaladoc.
5. **Tests** (TDD): update JsonSpec (dup keys w/ name, trailing strict vs
   config-lenient, number strictness, writeCanonical golden vs the exact
   repository.json bytes in tests/12-http-server.yaml), ModelSpec (Port
   valid/invalid: 0, 8765, 65535 ok; 65536, -1, abc, 1.5, "07" rejected;
   ReplayWarning renders), SnapErrorSpec (pin every message template).

## Definition of done
1. `source ../scripts/env.sh; cd snap; sbt --client shutdown; sbt --client "compile; test; scalafmtCheckAll; assembly"; sbt --client shutdown` — all green.
2. No `JsonSpike.scala` left in the tree.
3. Commit on `task/02-ziojson-foundation`, then `git push -u origin task/02-ziojson-foundation`.
4. Report: spike findings, files changed, test counts, gate tails, deviations, risks.
