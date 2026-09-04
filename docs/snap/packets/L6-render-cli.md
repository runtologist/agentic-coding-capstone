# Packet L6 — CLI grammar & Render (plain + terminal)

**Branch:** `task/04-render-cli` (from `develop`)
**Depends on:** frozen `Model.scala` (incl. `opaque type Port`, `Version`, `ReplayWarning`), `SnapError.scala`. Do NOT modify Model/SnapError/Json; if a contract change is needed, STOP and report.

## Files owned (exclusive)
- `snap/src/main/scala/snap/Cli.scala`
- `snap/src/main/scala/snap/Render.scala`
- `snap/src/test/scala/snap/CliSpec.scala`
- `snap/src/test/scala/snap/RenderSpec.scala`

## Safety rules (hard)
- Work ONLY under `snap/` in this checkout. No `rm -rf`/`mv` outside the repo; no edits outside owned files; no destructive git commands (no reset --hard, no branch -D on others, no push --force).
- Use `sbt --client`; run `sbt --client shutdown` after your final gate. Never interactive sbt tasks.

## Cli.scala — argument grammar → Command ADT (CONTRACT §1; SPEC §7)

`sealed trait Command` with cases: `Init(path: Option[String])`, `Config(global: Boolean, id: String)`, `Status`, `Log`, `Commit(message: String)`, `Diff(oldRaw: Option[String], newRaw: Option[String], repo: Option[String])`, `Revert(versionRaw: String)`, `Merge(repo: String)`, `Serve(port: Option[Port])`, `ShowVersion`.

Keep version operands as raw strings in the Command ADT; parsing to `Version` happens in the Commands layer so error ordering matches CONTRACT.md §13 (revert: unknown-version error precedes missing-contributor error).

Grammar rules — every violation returns `Left(SnapError.InvalidCommandOrArguments)` EXCEPT diff misuse, which returns `Left(SnapError.DiffUsage(...))` rendering `usage: snap diff <usage text>`:

- Bare `snap` / unknown first token / extra operands / unknown options / duplicated options / missing operand values → `invalid command or arguments`
- `--version` accepts no other args (`--version extra` is an error)
- `init [path]` — 0 or 1 operand, no options
- `config [--global] contributor.id <id>` — `--global` only in FIRST position, at most once; literal token `contributor.id` required; id required (`config --global contributor.id` with no id → error)
- `status`, `log` — no args at all
- `commit <message>` — exactly one operand (it is a single argv element; may contain spaces)
- `diff` valid forms: no args | `<old> <new>` | `<old> <new> --repo <repository>`; `--repo` only after both version operands, at most once, value required. Exactly one version operand → DiffUsage; unknown option or extra token in diff → DiffUsage (tests 14/24 pin `^snap: usage: snap diff .+\n$`)
- `revert <version>` — exactly one operand
- `merge <repository>` — exactly one operand
- `--serve [port]` — port optional; when present validate via `Port.parse` (all-digit, 0..=65535; else `InvalidPort(raw)` rendering `invalid port: <raw>` — test 14 pins `snap: invalid port: 65536`). `--serve 0 extra` → invalid command or arguments
- Parsing is pure: grammar errors must imply no side effects (test 24 asserts e.g. no `--unknown` file/dir created)

Pin every row of the test-24 grammar matrix in CliSpec:
`--version extra`; `init a b`; `init --unknown`; `config contributor.id a@x --global` (misplaced flag); `config --global --global contributor.id a@x` (duplicate flag); `config --global contributor.id` (missing value); `status extra`; `log --unknown`; `commit` (missing message); `commit message extra`; `revert` (missing version); `revert () extra`; `merge` (missing operand); `merge repo extra`; `--serve 0 extra`; unknown first token.

## Render.scala — pure rendering (CONTRACT §3, §4, §5; SPEC §7.11)

`enum Presentation { case Plain, Terminal }`. Provide a pure resolver:
`resolvePresentation(colorEnv: Option[String], noColorPresent: Boolean, stdoutIsTty: Boolean, stderrIsTty: Boolean): Either[SnapError, (Presentation, Presentation)]`
(stdout presentation, stderr presentation):
- `None` or `Some("auto")` → Terminal per stream iff that stream is a TTY AND `noColorPresent == false`
- `Some("always")` → Terminal for both streams regardless of NO_COLOR
- `Some("never")` → Plain for both streams
- any other value → `Left(SnapError.InvalidSnapColor)`; this error itself is rendered PLAIN
Unit-test all four TTY combinations (stdout/stderr independently) with injected booleans (SPEC §11 mandates this since the harness has no PTY).

Plain mode (byte-stable):
- init success: `()\n`; commit/revert success: `<version>\n`; merge success: `<joined version>\n`
- status: `version <v>\n` then one line per change `<A|M|D> <path>\n`, rows sorted by unsigned UTF-8 path; clean repo prints only the version line
- log: one line per patch, reverse canonical integration order: `<result-version>\t<author>\t<escaped-message>\n`; escaping order is `\`→`\\` FIRST, then TAB→`\t`, then LF→`\n` (test 04 golden: raw message `first<TAB>line<LF>second<\>tail` renders `first\tline\nsecond\\tail`)
- diff blocks:
  - headers `--- a/<path>` / `+++ b/<path>`; absent side uses `/dev/null` without `a/`/`b/` prefix
  - hunk header always `@@ -1,<old-token-count> +1,<new-token-count> @@`
  - body lines: retained ` <token>`, deleted `-<token>`, inserted `+<token>` (print token without its trailing LF, if any)
  - a token lacking final LF is printed, followed by LF, then the line `\ No newline at end of file`
  - binary change (either side non-text): one line `Binary files a/<path> and b/<path> differ` with `/dev/null` substitution for absent side
  - no differences → empty output
- warning (stderr): `warning: auto-resolved <path>: <reason>\n`
- error (stderr): `snap: <detail>\n`

Terminal mode (exact bytes; golden-test against harness/snap/tests/28-terminal-presentation.yaml — read that file and quote expected strings verbatim). Define `S(n, text)` = ESC[ + n + m + text + ESC[0m:
- init/commit/revert/merge success: `S(32,"✓") + " " + S(1,label) + " " + S(36,version) + "\n"` with label `Initialized repository` / `Committed` / `Reverted` / `Merged`
- status header: `S(1,"Snap status") + "  " + S(36,version) + "\n\n"` (two literal spaces between styled segments); clean tree appends `"  " + S(32,"✓") + " Working tree clean" + "\n"`; dirty rows: `"  " + S(color,symbol) + " " + path + " " + S(2,"(" + label + ")") + "\n"` with `(32,"+","added")`, `(31,"−" U+2212 MINUS SIGN,"deleted")`, `(33,"~","modified")`; trailing spaces in paths are preserved inside the row
- log entry: `S(36,"●") + " " + S(1,escapedMessage) + "\n  " + S(36,version) + " " + S(2,"by") + " " + S(35,author) + "\n"`; one additional LF between entries (entries joined with extra blank line)
- diff: preserve every plain byte; wrap the complete text of each matching line (excluding LF) with the FIRST applicable style in this precedence order: `--- ` or `+++ ` → 1; `@@ ` → 36; `-` → 31; `+` → 32; `\ ` → 2; `Binary files ` → 33. Context lines (` ` prefix) unchanged.
- `--version`: `S(1,"snap 1.0.0") + "\n"`
- warning (stderr): `S(33,"⚠") + " " + S(33,detail) + "\n"` where detail is the plain warning text WITHOUT the `warning: ` prefix (e.g. `auto-resolved same: later-create-wins`)
- error (stderr): wrap the whole plain line including `snap: ` prefix → `S(31,"✗ snap: <detail>") + "\n"`
- config success remains silent in both modes; `--serve` startup URL line is ALWAYS plain even under `SNAP_COLOR=always`

Presentation MUST NOT change command execution, repository/filesystem effects, warning selection/order, or exit status.

## Definition of done
1. Gates green: `source ../scripts/env.sh && cd snap && sbt --client shutdown; sbt --client "compile; test; scalafmtCheckAll"` then `sbt --client shutdown`.
2. ≥40 new focused tests across CliSpec/RenderSpec: full grammar matrix from test 24, all four TTY/presentation combinations, exact ANSI goldens from test 28, plain-mode goldens from tests 04/05/06 (render side only).
3. Commit on branch, push: `git push -u origin task/04-render-cli`. No Co-Authored-By trailers.
4. Report: files changed, test counts, gate tails, deviations (with justification), risks.
