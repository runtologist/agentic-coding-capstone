# Packet J1 — Thin JSON codec layer on zio-json (no internal JSON model)

**Branch:** `task/05-json-thin` (from `develop`)
**Depends on:** frozen `Model.scala` and `SnapError.scala`. Do NOT modify Model/SnapError; if a contract change is needed, STOP and report.

## Goal (user directive, 2026-09-05)

Simplify `Json.scala` to add **as little as possible on top of zio-json**. The
current L1b implementation parses into `zio.json.ast.Json` and then walks the
AST with custom extraction helpers (`asObject`, `asArray`, `field`,
`unknownFields`, `asNumber`, …), effectively an internal JSON model. Replace
this with zio-json `JsonDecoder`/`JsonEncoder` instances that decode
repository/config JSON **directly into Model types**. The guide is: **pass all
validations of the repository format** (SPEC §4, CONTRACT §7) with the minimum
custom code.

## Files owned (exclusive)
- `snap/src/main/scala/snap/Json.scala` (rewrite)
- `snap/src/test/scala/snap/JsonSpec.scala` (rewrite)

## Safety rules (hard)
- Work ONLY under `snap/` in this checkout. No `rm -rf`/`mv` outside the repo; no edits outside owned files; no destructive git commands (no reset --hard, no branch -D on others, no push --force).
- Use `sbt --client`; run `sbt --client shutdown` after your final gate. Never interactive sbt tasks.

## Read first
- `docs/snap/CONTRACT.md` §7 (every pinned validation error string), §8 (config), §12 (HTTP snapshot golden), §15 ruling A (config trailing tolerance)
- `harness/snap/SPEC.md` §3.2, §4.1–§4.5
- `harness/snap/tests/03-configuration.yaml`, `12-http-server.yaml`, `15-repository-validation.yaml`, `23-strict-validation-matrix.yaml`, `25-config-version-path-boundaries.yaml`, `26-portability-and-failure-safety.yaml`, `27-history-canonicality.yaml`
- Frozen interfaces: `Model.scala` (`ContributorId`, `Revision`, `Version`, `EditOp`, `Change`, `Patch`, `Repository`, `Model.positiveSafeInteger`, `Model.decodeCanonicalBase64`, `Model.validateStoredMessage`, `Model.validatePath`, `Model.isCanonicalTokenSeq`), `SnapError.scala`

## Step 1 — Spike zio-json behavior (record findings in your report)
Build small throwaway checks (as unit tests, not scratch mains) to determine empirically:
1. Duplicate object keys when decoding to `zio.json.ast.Json` and to case-class decoders: does zio-json reject them, keep last, or keep all? Can the offending key name be surfaced to produce exactly `duplicate JSON key <name>`?
2. Trailing content after the first complete JSON value: does `decodeJson`/`fromJson` reject it, and with what error shape? (Strict for repository.json; tolerated for config per ruling A — if zio-json rejects trailing content, implement a minimal string-aware first-value scanner used ONLY for config parsing.)
3. `Json.Num` representation: does BigDecimal preserve scale/precision enough to distinguish `1` vs `1.0` vs `1.5` vs `1e2`, and hold `9007199254740991` exactly?
4. Pretty printing: is zio-json's `toJsonPretty` byte-identical to Node `JSON.stringify(v, null, 2)` for the repository shape in test 12 (2-space indent, one array element per line, `"key": value` spacing, empty containers inline, string escaping incl. `\u00XX` lowercase for <0x20 and raw non-ASCII)? Compare against the exact `body_text_equals` golden in `harness/snap/tests/12-http-server.yaml`.

## Step 2 — Rewrite Json.scala as a thin codec layer
Public surface (all return `Either[SnapError, T]` for parses):
- `parseRepository(input: String): Either[SnapError, Repository]` — strict: malformed JSON → `InvalidJson` (detail must contain `invalid JSON`); duplicate keys at any depth → `DuplicateJsonKey(name)`; unknown fields → `UnknownRepoField` / `UnknownPatchField` / `UnknownChangeField`; revision and edit-op counts via `Model.positiveSafeInteger` (fractional/zero/negative/>max → `NotPositiveSafeInteger`); `put.content` via `Model.decodeCanonicalBase64` (→ `NonCanonicalBase64`); message via `Model.validateStoredMessage`; path via `Model.validatePath`; edit-op shape (exactly one of retain/delete/insert → `EditOpWrongArity`; empty insert array → `EmptyField`); insert token canonicality via `Model.isCanonicalTokenSeq`/`isValidInsertToken` (→ `NonCanonicalTokens`). Structural type mismatches map to the closest pinned `SnapError`.
- `parseConfig(input: String): Either[SnapError, ConfigFile]` — same strictness EXCEPT trailing bytes after the first complete value are tolerated (test 03: `{"contributor":{"id":"global@example.com"}}}}` parses; `not json` → `InvalidJson`). Define the minimal `ConfigFile` shape here (`contributor.id` only; unknown fields are errors per test 25; duplicate keys → `DuplicateJsonKey`).
- `writeRepository(repo: Repository): String` and `writeConfig(cfg: ConfigFile): String` — byte-identical to Node `JSON.stringify(value, null, 2)` + trailing `\n`, with canonical field order: repository `format, frontier, patches`; patch `author, revision, base, message, changes`; change `type, path, edit|content`; edit op `retain|delete|insert`. Use zio-json's encoder if the spike proves byte parity; otherwise implement a minimal writer over Model values. Golden-test against the exact test-12 bytes.
- After the rewrite, `Json.scala` must NOT expose `type Value`, AST constructors (`obj/arr/str/num/bool/nul`), or generic extraction helpers.

## Step 3 — Tests (TDD; red first)
Rewrite `JsonSpec.scala` to cover, at minimum:
- Golden round-trip: parse the test-12 repository JSON, re-serialize, byte-compare against the exact `body_text_equals` block (including trailing LF).
- Duplicate keys at root, patch, change, and edit-op levels → `DuplicateJsonKey(name)` with the correct key.
- Unknown fields at repo/patch/change levels → the three `Unknown*Field` cases (test 23/27 pinned substrings).
- Integer strictness: `1.5`, `0`, negative, `9007199254740992` rejected with `NotPositiveSafeInteger` wording; `1`, `9007199254740991` accepted.
- Canonical base64: unpadded / non-alphabet / bad trailing bits → `NonCanonicalBase64`; valid padded base64 round-trips bytes (test 15/25).
- Config parsing: trailing-garbage case accepted (test 03 golden), `not json` rejected, unknown fields and duplicate keys rejected (test 25).
- Malformed JSON variants → `InvalidJson` containing `invalid JSON`.
- Key-order and whitespace variations of semantically identical repositories parse to structurally equal values (test 26 typed-value identity).

## Definition of done
1. Gates green: `source ../scripts/env.sh && cd snap && sbt --client shutdown; sbt --client "compile; test; assembly; scalafmtCheckAll"` then `sbt --client shutdown`. All pre-existing ModelSpec/SnapErrorSpec tests must still pass unchanged.
2. No internal JSON model remains (`Json.Value`, constructors, extraction helpers removed).
3. Commit on branch `task/05-json-thin`, push: `git push -u origin task/05-json-thin`. No Co-Authored-By trailers.
4. Report: spike findings (the four questions with observed zio-json behavior), files changed, test counts, gate tails, whether zio-json pretty printing was byte-compatible (and what, if anything, remained custom), deviations with justification, risks for L3 Codec.
