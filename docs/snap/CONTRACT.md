# Snap Contract (frozen intake, Scala capstone)

Sources: `harness/snap/SPEC.md` (§1–§12), all 28 YAML cases in
`harness/snap/tests/`, and the harness environment in
`harness/snap/test-harness/src/{runner,process}.ts` (documented in the workshop
`capstones/snap/TEST-HARNESS.md`). Every quoted string below was verified
verbatim against those files. Tests override spec prose where they conflict
(conflicts are listed in §15).

Plain mode is the byte-stable contract; the acceptance harness always runs in
plain mode unless a case sets `SNAP_COLOR=always` (only test 28 does).

---

## 1. CLI grammar

Binary: `snap`. Repository discovery: walk from cwd up to filesystem root looking
for `.snap/`; commands needing a repo fail outside one.

| Command | Form | Notes |
|---|---|---|
| init | `snap init [path]` | path defaults to `.`; created if absent (incl. intermediates: `init new/repository` works, test 02) |
| config | `snap config [--global] contributor.id <id>` | `--global` only in first position, at most once |
| status | `snap status` | no args |
| log | `snap log` | no args |
| commit | `snap commit <message>` | exactly one operand |
| diff | `snap diff` / `snap diff <old> <new> [--repo <repository>]` | 0 or 2 version operands, optional single `--repo` after both versions |
| revert | `snap revert <version>` | exactly one operand |
| merge | `snap merge <repository>` | exactly one operand (local path or `http(s)://` URL) |
| serve | `snap --serve [port]` | port defaults to 8765; `0` = OS-chosen |
| version | `snap --version` | no other args |

Grammar matrix (test 24 + test 14): every one of these exits **1** with empty
stdout and stderr exactly `snap: invalid command or arguments\n`:

- `--version extra`
- `init a b`, `init --unknown`
- `config contributor.id a@x --global` (option misplaced), `config --global --global contributor.id a@x` (duplicate), `config --global contributor.id` (missing value)
- `status extra`, `log --unknown`
- `commit` (missing message), `commit message extra`
- `revert`, `revert () extra`
- `merge`, `merge repo extra`
- `--serve 0 extra`
- unknown first token (e.g. `snap unknown`)

`diff` grammar errors are different — exit 1, empty stdout, stderr matching
`^snap: usage: snap diff .+\n$` (tests 14, 24):

- `diff ()` (one operand)
- `diff () () --unknown repo` (unknown option)
- `diff () () --repo repo --repo repo` (duplicate option)

No grammar error may create files/directories: test 24 asserts `path_not_exists: --unknown`.

`--serve 65536` → exit 1, stderr exactly `snap: invalid port: 65536\n` (test 14).

Status outside any repository → exit 1, stderr exactly
`snap: not a Snap repository\n` (test 14).

Options occur exactly in the positions shown and at most once; unknown options,
extra operands, missing option values → error. Local repository operands resolve
against the process cwd (tests use relative `../right`, `../remote`).

## 2. Exit codes and streams

- **0** success; **1** expected errors; **2** unexpected internal failures (spec §10; no test asserts 2).
- Results → stdout. Warnings and errors → stderr.
- Plain-mode errors are one line: `snap: <detail>\n`.
- Output is UTF-8 with LF line endings. Harness decodes stdout/stderr as strict
  UTF-8 and caps each stream at 16 MiB.
- stdin is closed/empty for every `run` step (harness ends stdin immediately);
  no command reads stdin.

## 3. Plain-mode output (byte-exact)

- `init` → stdout `()\n`, stderr empty (tests 01/02). On reinit: stderr contains
  `repository already exists`; inside an existing repo: contains
  `cannot initialize inside repository`; exit 1, no `.snap` created inside (test 02).
- `config` → stdout empty, stderr empty on success (tests 03/25).
- `status` → `version <version>\n` then one line per change sorted by path:
  `<A|M|D> <path>\n`. Clean repo prints only the version line.
  Exact (test 04): `version ()\nA a.txt\nA z.txt\n`; after changes:
  `version (alice@example.com->1)\nM a.txt\nA m.txt\nD z.txt\n`.
- `log` → one line per patch in **reverse canonical integration order**,
  tab-separated: `<result-version>\t<author>\t<escaped-message>\n` (test 04):
  `(alice@example.com->2)\talice@example.com\tsecond\n(alice@example.com->1)\talice@example.com\tfirst\\tline\\nsecond\\\\tail\n`
  Message escaping order: `\` → `\\` FIRST, then TAB → `\t`, then LF → `\n`
  (spec §7.4). Test 04 proves the order: the raw commit message contains a
  literal TAB, a literal LF, and a literal backslash
  (`first<TAB>line<LF>second<\>tail`) and must render as
  `first\tline\nsecond\\tail` — escaping the backslash first is what prevents
  the TAB/LF escape sequences from being double-escaped.
- `commit` → stdout `<new-version>\n` e.g. `(alice@example.com->1)\n` (tests 03–06).
- `diff` → unified blocks or empty (see §10). No differences → empty stdout, exit 0
  (test 05: `diff (a@x->2) (a@x->2)`).
- `revert` → stdout `<new-version>\n` (the NEW version, tests 07/19/28).
- `merge` → joined version on stdout, e.g. `(alice@x->1,bob@x->1,seed@x->1)\n`;
  new warnings on stderr (tests 09–11, 13, 17, 18, 28). No-op merge prints the
  unchanged version with empty stderr.
- `--serve` → prints `http://127.0.0.1:<actual-port>/repository.json\n` to stdout
  and flushes; nothing else on stdout, empty stderr (tests 12/28).
- `--version` → stdout matches `^snap [0-9]+\.[0-9]+\.[0-9]+\n$`; test 28 pins
  exactly `snap 1.0.0` in terminal mode, so version **must be 1.0.0**.

## 4. Terminal-mode formats (test 28, `SNAP_COLOR=always`, all byte-exact)

`S(n, text)` = `ESC[<n>m` + text + `ESC[0m` (ESC = `\x1b`).

- init: `\x1b[32m✓\x1b[0m \x1b[1mInitialized repository\x1b[0m \x1b[36m()\x1b[0m\n`
- commit: `\x1b[32m✓\x1b[0m \x1b[1mCommitted\x1b[0m \x1b[36m(alice@x->1)\x1b[0m\n`
- revert: `\x1b[32m✓\x1b[0m \x1b[1mReverted\x1b[0m \x1b[36m(alice@x->3)\x1b[0m\n`
- merge: `\x1b[32m✓\x1b[0m \x1b[1mMerged\x1b[0m \x1b[36m(a@x->1,b@x->1)\x1b[0m\n`
- status header: `\x1b[1mSnap status\x1b[0m  \x1b[36m<version>\x1b[0m\n\n`
  (two literal spaces between the two styled segments)
- status clean row: `  \x1b[32m✓\x1b[0m Working tree clean\n`
- status dirty rows: `  \x1b[<color>]<symbol>\x1b[0m <path> \x1b[2m(<label>)\x1b[0m\n`
  with (32,`+`,added), (31,`−` U+2212 MINUS SIGN,deleted), (33,`~`,modified);
  example: `  \x1b[31m−\x1b[0m gone.txt \x1b[2m(deleted)\x1b[0m\n`.
  Trailing spaces in paths are preserved inside the row: path `trailing ` →
  `  \x1b[32m+\x1b[0m trailing  \x1b[2m(added)\x1b[0m\n` (two spaces before the dim label).
- log entry: `\x1b[36m●\x1b[0m \x1b[1m<message>\x1b[0m\n  \x1b[36m<version>\x1b[0m \x1b[2mby\x1b[0m \x1b[35m<author>\x1b[0m\n`
  with one additional LF between entries (`\n\n` joining; trailing-space messages
  keep the space inside the bold wrap: `\x1b[1mmessage \x1b[0m`).
- --version: `\x1b[1msnap 1.0.0\x1b[0m\n`
- diff: every plain byte preserved; whole line text (excluding LF) wrapped by the
  first applicable prefix style:
  `--- ` and `+++ ` → 1 (`\x1b[1m--- a/added.txt\x1b[0m\n`),
  `@@ ` → 36 (`\x1b[36m@@ -1,2 +1,2 @@\x1b[0m\n`),
  `-` → 31, `+` → 32, `\ ` → 2 (`\x1b[2m\\ No newline at end of file\x1b[0m\n`),
  `Binary files ` → 33. Context lines (` ` prefix) are unchanged.
- warning (stderr): `\x1b[33m⚠\x1b[0m \x1b[33m<detail>\x1b[0m\n` where `<detail>`
  is the plain warning text **without** the `warning: ` prefix, e.g.
  `\x1b[33m⚠\x1b[0m \x1b[33mauto-resolved same: later-create-wins\x1b[0m\n`.
- error (stderr): `\x1b[31m✗ <plain error line>\x1b[0m\n`, i.e. the whole plain
  line including `snap: ` is wrapped:
  `\x1b[31m✗ snap: invalid command or arguments\x1b[0m\n`.
- config stays silent; `--serve` URL stays plain even under `SNAP_COLOR=always`
  (test 28 asserts `^http://127\.0\.0\.1:[0-9]+/repository\.json\n$` with the
  case-level `SNAP_COLOR: always`).

Presentation never changes execution, side effects, warning selection/order, or
exit status (test 28: identical semantics under `always`).

## 5. Color / presentation selection

| `SNAP_COLOR` | Behavior |
|---|---|
| unset or `auto` | terminal mode independently on stdout/stderr when **that stream is a TTY**, unless `NO_COLOR` is present |
| `always` | terminal mode on both streams even when piped; overrides `NO_COLOR` |
| `never` | plain mode on both streams |
| anything else | error **before command execution**: exit 1, empty stdout, stderr exactly `snap: SNAP_COLOR must be auto, always, or never\n` — this error itself is plain |

`NO_COLOR` is conservative: presence with **any value including empty** forces
plain in auto mode (test 28: `NO_COLOR: ""` and `NO_COLOR: "1"` both plain).

The harness default environment sets `NO_COLOR=1` and pipes both streams, so
default harness runs are plain unless a case sets `SNAP_COLOR: always`
(test 28) or a step overrides env.

TTY behavior of `auto` is not exercised by the harness (no PTY); spec §11
requires implementation-level unit tests for TTY/non-TTY selection on both
streams.

## 6. Version model

Canonical CLI syntax: `()` or `(id->rev,id->rev,...)` — contributors sorted by
unsigned UTF-8 bytes of the ID, **no spaces** anywhere, exactly one `@` per ID.
JSON form: ordered array of `[id, rev]` pairs.

Contributor ID rules (spec §3.1): ASCII email-shaped; exactly one `@` with
nonempty sides; no control char, no whitespace, no `,` `(` `)`, no substring
`->`; ≤ 254 bytes; spelling preserved exactly. Invalid IDs → exit 1, stderr
matches `^snap: invalid contributor id: .+\n$` (tests 03, 25). Tested bad IDs:
`bad-id`, `two@@x`, `space @x`, `a,b@x`, `a(b)@x`, `a->b@x`, `not-an-id`.

Revision: positive integer ≤ 9007199254740991 (JS max safe int).

Invalid version errors → exit 1, stderr matches `^snap: invalid version: .+\n$`
(tests 19, 25). Tested cases: explicit zero `(good@x->0)`, negative
`(good@x->-1)`, overflow `(good@x->9007199254740992)`, unsorted
`(b@x->1,a@x->1)`, space after comma `(a@x->1, b@x->1)`, leading zero
`(a@x->01)`, duplicate id `(a@x->1,a@x->2)`.

Syntactically valid but unknown version → exit 1, stderr exactly
`snap: unknown version: (a@x->2)\n` (test 19) — the version string is echoed.
"Known" = every patch `(c,n)` with `n <= V[c]` exists and the selected set
contains each selected patch's complete base (spec §4.1).

Missing contributor ID for commit/revert → exit 1, stderr exactly
`snap: contributor.id is required; configure it locally or globally\n` (test 19).

Comparison: four outcomes equal / before / after / concurrent (componentwise;
absent = 0). `join(V,W)[c] = max(V[c], W[c])`.

Snap order: sorted union of contributor IDs; compare counters at each ID; first
unequal counter decides. Extends causal order; concurrent ordering has no
chronological meaning.

## 7. Repository format & validation

Layout: `.snap/repository.json` (complete repository value), optional
`.snap/config.json`. Extra files under `.snap/` are ignored/untracked (test 25:
`.snap/untracked` invisible to status).

repository.json shape:
```json
{"format": 1,
 "frontier": [["id", rev], ...],
 "patches": [{"author", "revision", "base", "message", "changes": [...]}]}
```
Change variants: `{"type":"text","path","edit":[ops]}`,
`{"type":"put","path","content":"<canonical padded base64>"}`,
`{"type":"delete","path"}`. Edit ops: `{"retain":n}`, `{"delete":n}`,
`{"insert":["tok",...]}` — exactly one key each.

Readers accept ordinary JSON whitespace and key order; the parsed typed value is
authoritative (test 26: identical patches with shuffled keys/whitespace merge as
duplicates, no collision). **Unique object keys are required** — duplicates are
errors (tests 15, 25).

Validation runs on every command that loads a repository (even `status`), and
**never mutates working files** on failure (tests 15, 23 assert untouched files;
test 20 asserts unchanged repository.json after failed merge).

Observed error contracts (stderr `contains` or `matches`, all exit 1, empty stdout):

| Condition | Exact / pattern | Test |
|---|---|---|
| duplicate object key | contains `duplicate JSON key` / `^snap: duplicate JSON key .+\n$` | 15, 25 |
| missing base patch (gap) | contains `missing a@x` | 15 |
| invalid tracked path (`.snap/secret`) | contains `path is invalid` | 15 |
| non-canonical base64 (`"abc"`) | contains `canonical base64` | 15 |
| edit under-consumes old tokens | contains `does not consume old content` | 15 |
| prefix conflict within one patch (`a` + `a/b`) | contains `tree paths conflict` | 15 |
| dependency cycle | contains `cyclic or incomplete patch history` | 15 |
| no-op put (same bytes) | contains `no-op change` | 15 |
| adjacent same-kind ops (insert+insert) | contains `adjacent insert` | 15 |
| unknown repo field | exactly `^snap: repository has unknown field: unknown\n$` | 23 |
| frontier not canonically sorted | `^snap: .*canonical.*\n$` | 23 |
| non-integer/fractional revision (1.5) | `^snap: .+positive safe integer\n$` | 23 |
| zero/negative op count (`retain: 0`) | `^snap: .+positive safe integer\n$` | 23 |
| unreachable patch (not in frontier closure) | `^snap: unreachable patch: .+\n$` | 23 |
| empty message | `^snap: .+message is empty\n$` | 23 |
| empty changes array | `^snap: .+changes is empty\n$` | 23 |
| unknown change field | `^snap: .+unknown field: extra\n$` | 23 |
| edit op with two keys | `^snap: .+must have one operation\n$` | 23 |
| empty insert array | `^snap: .+insert is empty\n$` | 23 |
| edit consumes beyond old content | `^snap: .+consumes beyond old content\n$` | 23 |
| delete of absent path | exactly `^snap: delete of absent path: f\n$` | 23 |
| patch with unknown field; unsorted patches; unsorted changes; `revision != base[author]+1`; non-canonical insert tokens (`["a","b"]` — non-final token lacks LF); text-create of present path (empty edit over existing); text change over binary base | `^snap: .+\n$` (any snap error) | 27 |
| cross-repo same dot, different values | contains `patch collision: a@x revision 1` | 16 |

Dot-collision checks apply to both `merge` and cross-repo `diff --repo` and fail
**before** any local mutation (test 16 asserts local file and repository.json
unchanged after both failures).

Tracked path rules (spec §2): nonempty UTF-8, `/` separators, no ASCII control
char, no backslash, no empty/`.`/`..` segments, first segment ≠ `.snap`; no
Unicode/case normalization; sort by unsigned lexicographic UTF-8 bytes
(test 25 order: `nested/file` < `z` < `é` < `😀`). Trees are prefix-free by
segment. Text tokens: UTF-8 without NUL, split after every LF retaining LF
(`"a\r\nb"` → `["a\r\n","b"]`); empty file → no tokens; edit scripts must
consume the whole old sequence and produce canonical tokens (every non-final
token ends in LF). Empty script valid only to create an empty file (test 06:
empty file committed as `text` with `edit: []`, diff shows `@@ -1,0 +1,0 @@`).

## 8. Configuration

Shape exactly `{"contributor":{"id":"<id>"}}` (test 03 json_equals on both
local and global files after `config`).

- Local: `<repo>/.snap/config.json`; global: `$HOME/.snapconfig.json`.
- Precedence: local read first; **if local provides an ID, global is never
  read** (test 03: local wins while global contains `not json`); a missing local
  file falls through to global.
- Local file present but invalid → error, **no fallback to global** (test 25:
  local `not-an-id` with valid global → `^snap: invalid contributor id: .+\n$`).
- Malformed global JSON → error containing `invalid JSON` (test 03, `not json`).
- Duplicate keys in config → `^snap: duplicate JSON key .+\n$` (test 25).
- `config` writes drop unknown fields: pre-existing
  `{"contributor":{"id":"old@x"},"unknown":true}` becomes exactly
  `{"contributor":{"id":"new@x"}}` after a write (test 25).
- Config parsing tolerates **trailing bytes after the first complete JSON value**:
  `{"contributor":{"id":"global@example.com"}}}}` is accepted and used (test 03),
  while `not json` fails. See §15 item A.
- `$HOME` absent → global config unavailable (`env: HOME: null` steps in test 19).
- Success prints nothing (empty stdout/stderr).

## 9. Working tree semantics

- Tracked: every regular file below repo root except `.snap/` and contents.
  Empty directories are not tracked (test 25: empty dirs invisible in status).
- Unsupported entries (symlink, FIFO, any non-regular file) → any command that
  scans the tree fails exit 1 with stderr exactly
  `snap: unsupported working tree entry: <path>\n` (tests 08, 20: `link`, `pipe`).
  Applies to read-only commands too (status, diff). No mutation occurs (test 08:
  repository.json still `{"format":1,"frontier":[],"patches":[]}`).
- Status codes: `A` absent→present, `M` bytes changed, `D` present→absent; rows
  sorted by path after `version <v>` line.
- Clean tree: commit fails `snap: working tree is clean\n` (test 04); merge and
  revert fail `snap: working tree is dirty\n` (tests 07, 20).
- Dirty/unsupported-tree merge refuses **before importing history**: local files
  untouched, remote files absent, repository.json unchanged (test 20).

## 10. Diff rendering (plain mode)

Modes: `snap diff` (current vs working tree); `snap diff <old> <new>` (two known
local versions); `snap diff <old> <new> --repo <repository>` (old local, new in
another local or HTTP repository, **without importing it**; local repo stays
unmodified — test 26 asserts local repository.json and file tree unchanged).

Changed paths sorted by path. Text path block:

```
--- a/<path>
+++ b/<path>
@@ -1,<old-token-count> +1,<new-token-count> @@
 <retained token>
-<deleted token>
+<inserted token>
```

- Absent side header uses `/dev/null` (no `a/`/`b/` prefix on the null side):
  `--- /dev/null\n+++ b/added.txt`.
- Hunk header is always `-1,<n> +1,<m>` (whole-file diff).
- A token without final LF is printed followed by LF and then a line
  `\ No newline at end of file` (literal backslash-space prefix).
- Binary change (either side): one line
  `Binary files a/<path> and b/<path> differ` with `/dev/null` substitution.
- Token contents are printed verbatim (CRLF retained: `+a\r` line, test 26;
  Unicode retained: `+hé`).
- No differences → empty stdout, exit 0.

Golden outputs (verbatim):

Test 05 (working-tree diff and identical version-pair diff):
```
--- /dev/null
+++ b/added.txt
@@ -1,0 +1,1 @@
+new
\ No newline at end of file
--- a/repeated.txt
+++ b/repeated.txt
@@ -1,3 +1,3 @@
-a
 b
 a
+a
\ No newline at end of file
```
(repeated.txt: old `a\nb\na\n` → new `b\na\na`; stored edit for that commit is
`[{"delete":1},{"retain":2},{"insert":["a"]}]` — the deletion-on-tie rule.)

Test 06: empty+binary create →
`Binary files /dev/null and b/data.bin differ\n--- /dev/null\n+++ b/empty\n@@ -1,0 +1,0 @@\n`;
binary delete → `Binary files a/data.bin and /dev/null differ\n`.
(NUL byte ⇒ binary ⇒ stored as `put`; test 26: `nul.bin` content `a\x00b`
stored `{"type":"put",...,"content":"YQBi"}`.)

Test 26 cross-repo golden:
```
--- /dev/null
+++ b/crlf.txt
@@ -1,0 +1,2 @@
+a<CR>
+b
\ No newline at end of file
Binary files /dev/null and b/nul.bin differ
--- /dev/null
+++ b/unicode.txt
@@ -1,0 +1,1 @@
+hé
```

Test 21 (version-vs-version):
`(a@x->1)`→`(a@x->2,b@x->2)`:
```
--- a/story.txt
+++ b/story.txt
@@ -1,1 +1,4 @@
 base
+B1
+B2
+A2
```
reverse direction `(a@x->2,b@x->2)`→`(a@x->1,b@x->2)`:
```
--- a/story.txt
+++ b/story.txt
@@ -1,4 +1,3 @@
 base
 B1
 B2
-A2
```

Canonical diff algorithm (spec §5): token-level LCS DP with
`delete 1` chosen when `D(i+1,j) <= D(i,j+1)` (delete on ties), then coalesce
adjacent same-kind ops. Commit stores exactly this script (tests 05, 06, 26 pin
stored edits via json_equals).

## 11. Replay / OT semantics (merge convergence)

Materialization (spec §6.1): select patches `(c,n)` with `n <= V[c]`; start from
empty tree; repeatedly integrate the least ready patch (base fully integrated)
ordered by (1) Snap order of result versions, (2) author UTF-8 bytes,
(3) revision.

Integrating a patch (spec §6.2/6.3/6.4), per path with base `B`, current
canonical `C`, authored result `T`:

1. Namespace first: paths the patch makes present that conflict with current
   ancestor/descendant paths → incoming installed, conflicting current paths
   removed, each removed path warns `namespace-wins`.
2. `B==C` → apply authored change directly (no warning).
3. `C==T` → keep unchanged, no warning (identical concurrent changes collapse).
4. All text + text change → OT: `Q = diff(B, C)` (§5), transform `P` through `Q`
   (§6.3 table: Q-insert ⇒ retain(len) consuming Q only and taking priority;
   P-insert ⇒ same insert consuming P only; P-retain/Q-retain ⇒ retain(min) both;
   P-delete/Q-retain ⇒ delete(min) both; P-retain/Q-delete ⇒ nothing both;
   P-delete/Q-delete ⇒ nothing both; split counts as needed; coalesce). No warning.
5. Path-level fallbacks with warnings: `T` absent → `delete-wins`; `B` present &
   `C` absent → `delete-wins` (earlier concurrent delete); `B` absent & `C`,`T`
   present → `later-create-wins`; incoming `put` → `later-put-wins`; else
   (incoming text vs non-text current) → `put-wins`.

Warnings: unique pairs `(path, reason)` sorted by path then reason; merge prints
only pairs present in the joined replay but absent in the pre-merge local
replay — re-merging the same history prints nothing (tests 09, 10). Plain line:
`warning: auto-resolved <path>: <reason>\n` to stderr.

Exact observed behaviors:

- **Test 09** (concurrent text edits, base `base\n`, left `base\nleft\n`
  alice, right `base\nright\n` bob): merge stdout
  `(alice@x->1,bob@x->1,seed@x->1)\n`, stderr empty (line OT, no warning);
  merged file `base\nright\nleft\n` — bob's line first because bob's patch is
  canonically earlier (Snap order: at `alice@x`, bob's result has 0 < 1);
  bidirectional convergence (`trees_equal` ignoring `.snap/config.json`);
  re-merge is a no-op printing the same version with empty stderr.
- **Test 10** (all whole-file rules): stderr exactly
  `warning: auto-resolved delete.txt: delete-wins\nwarning: auto-resolved incompatible.txt: put-wins\nwarning: auto-resolved later-put.txt: later-put-wins\n`;
  `identical.txt` no warning; final bytes: delete.txt gone,
  incompatible.txt = `AP8=` (bob's binary beat alice's text edit via `put-wins`),
  later-put.txt = `AAE=` (alice's put beat bob's text via `later-put-wins`),
  identical.txt = `same\n`. Re-merge: empty stderr.
- **Test 11** (namespace): file `a` vs `a/b` from disconnected repos.
  Merge into alice's repo (alice owns `a`): stdout
  `(alice@x->1,bob@x->1)\n`-style joined version, stderr exactly
  `warning: auto-resolved a/b: namespace-wins\n`, result keeps file `a`
  (`ancestor\n`) and removes `a/b`. Reverse direction (bob owns `x`, alice owns
  `x/y`): stderr `warning: auto-resolved x: namespace-wins\n`, result `x`
  becomes a directory containing `x/y` = `descendant\n`. Both directions
  converge to the canonical winner.
- **Test 16** (dot collision): same `(a@x, 1)` with different message/content →
  `patch collision: a@x revision 1` for both diff --repo and merge, no mutation.
- **Test 17** (concurrent creates of same path): both directions print
  `warning: auto-resolved same.txt: later-create-wins\n`, joined version
  `(alice@x->1,bob@x->1)\n`, winner content `alice\n` (alice canonically later),
  trees converge.
- **Test 18** (three-way OT, base `start\nend\n`; a inserts `A\n` after start,
  b inserts `B\n` after start, c deletes `start\n`): all six merge association
  orders converge with **empty stderr** (pure OT, no warnings) to final content
  `B\nA\nend\n` and joined version `(a@x->1,b@x->1,c@x->1,seed@x->1)\n`.
  Canonical integration order: c (delete) → b → a.
- **Test 22** (OT matrix, base `0\n1\n2\n3\n4\n`): all merges exit 0 with empty
  stderr and exact results: P-delete/Q-delete overlap → `0\n3\n4\n`;
  complex split/insert-priority/trailing-insert → `A\n0\nB\n3\n4\nTAIL\n`;
  P-retain/Q-delete → `0\n2\n3\n4\nA\n`; Q-insert before P-delete survives →
  `0\nB\n2\n3\n4\n`.
- **Test 21**: merge `(a@x->2)` with `(a@x->1,b@x->2)` → `(a@x->2,b@x->2)` both
  directions; final `base\nB1\nB2\nA2\n`.

`merge` stdout is the joined frontier version in canonical order
(`(a@x->2,b@x->2)\n`, `(a@x->1,b@x->1,c@x->1,seed@x->1)\n`, ...).

## 12. HTTP

Server (`snap --serve [port]`, test 12):

- Bind 127.0.0.1 only; port default 8765; `0` → OS-assigned; invalid (65536) →
  `snap: invalid port: 65536\n`, exit 1.
- Startup: validate repository; invalid → exit 1, empty stdout,
  stderr `^snap: .+\n$` (tested with unknown field `bad`).
- Print once and flush: `http://127.0.0.1:<actual-port>/repository.json\n`
  (always plain, even under `SNAP_COLOR=always`).
- GET `/repository.json` → 200, header `Content-Type: application/json; charset=utf-8`,
  body = **startup snapshot** as pretty-printed JSON — test 12 pins the exact
  bytes: two-space indentation, every array element on its own line
  (Node `JSON.stringify(value, null, 2)` style), and a trailing LF. The
  snapshot is taken at startup; subsequent commits must NOT change the served
  body.
- HEAD `/repository.json` → 200, same headers, **zero body bytes** (harness uses
  a raw socket so any body bytes would fail `body_base64_equals: ""`).
- Other paths (incl. query strings: `/repository.json?query=not-exact`) → 404.
- Other methods (POST) → 405 with header `Allow: GET, HEAD` (asserted as
  lowercase name `allow`, value `GET, HEAD`).
- SIGTERM **and** SIGINT → clean shutdown, exit **0**, complete stdout exactly
  the URL line, stderr empty (harness kills the process group).

Client (`merge`/`diff --repo` with `http://` or `https://` operand, test 13):

- One GET of the exact URL; redirects are **not followed** — a 302 fails with
  stderr containing `HTTP 302` and exactly one request is observed at the
  original target.
- Non-JSON body → stderr contains `invalid JSON`, exit 1.
- Body parsed as repository value and fully validated (unknown field →
  `^snap: .+\n$`, no mutation; test 26 asserts local repo unchanged and exactly
  one GET per attempt).
- Successful remote merge prints joined version (test 13: `(remote@x->1)\n`) and
  materializes files (test 13: `local/file.txt` = `remote\n`).
- Cross-repo `diff` over HTTP works identically to local `--repo` (test 13).

## 13. Commit / revert mechanics

Commit (spec §7.5 + tests):
- Requires contributor ID (§8 message if missing) and a **dirty** tree
  (`snap: working tree is clean\n` otherwise).
- Message: nonempty, ≤ 4096 UTF-8 bytes; empty → stderr exactly
  `snap: invalid commit message\n`, exit 1 (test 25). Tab and LF are allowed
  inside messages (test 04 commits a message containing a literal TAB, LF, and
  backslash successfully).
- Change selection per path: new content is text (valid UTF-8, no NUL) and old
  path absent or text → `text` change with canonical edit script; otherwise
  `put` with canonical padded base64; removed paths → `delete`.
- Diffs the complete current tree vs complete working tree in one patch
  (changes sorted by path — test 05 stored patch lists `added.txt` before
  `repeated.txt`).
- Atomically replaces repository.json; prints new version.

Revert (spec §7.7 + tests):
- Requires contributor ID, **clean** tree (`snap: working tree is dirty\n`), and
  locally known target version (`snap: unknown version: <v>\n`).
- Authors one patch with message `revert to <version>` using the canonical
  version string (test 07 log: `revert to (a@x->2)`, `revert to (a@x->1)`).
- Installs target contents (including file↔directory transitions, test 07),
  updates repository, prints the **new** version. Target `()` empties the tree
  (test 19).
- Target tree equal to current → exit 1, stderr exactly
  `snap: target tree is already current\n`.
- Additive only: never removes patches, never moves frontier backward.
- Observed check order for `revert`: parse/validate the version argument and
  check it is locally known **before** checking contributor config (test 14:
  `revert (unknown@x->1)` in a repo with no config errors
  `snap: unknown version: (unknown@x->1)`, not the missing-contributor error).
  A safe order is: parse version → known-version check → working-tree clean
  check → contributor check → no-op check (`target tree is already current`) →
  diff, write files, write repository.json.

## 14. Determinism & harness environment

Per-case sandbox environment (harness `deterministicEnvironment`, only these
vars survive; case/step `env` may override or null-delete):

```
PATH=<inherited>          HOME=<sandbox>/home     TMPDIR=<sandbox>/tmp
NO_COLOR=1                LANG=C                  LC_ALL=C
NO_PROXY=127.0.0.1,localhost
```

- Fresh process per command; each `run`/`stop` asserts an exact exit code.
- Every command runs with cwd inside the sandbox (default sandbox root);
  repository discovery must therefore work from nested cwds (test 19: status
  from `repo/sub/deep` finds the repo).
- Mutation ordering (spec §10): merge/revert/commit complete parsing,
  validation, replay, dirty-tree checks and target-tree construction **before
  writing**; on mutation, working files first, then `repository.json` replaced
  via a same-directory temp file. Commit only replaces metadata (files already
  present).
- Writers SHOULD emit repository.json with two-space indentation and trailing LF
  (test 12 pins the served snapshot to exactly that pretty format; `json_equals`
  on files is parse-based so on-disk format is otherwise free).
- Deterministic replay guarantee: same patch set + frontier ⇒ same bytes and
  warning set; merge direction and association order independent (tests 09, 11,
  17, 18, 21).
- No wall-clock, locale, or timezone dependence anywhere (versions, ordering and
  output are fully determined by repository contents; `LANG=C` in env).

## 15. Ambiguities & rulings

Resolved by tests (tests win over spec prose):

| # | Item | Resolution | Evidence |
|---|---|---|---|
| A | Config files with trailing bytes after the first complete JSON value | **Accepted** (parse first value, ignore trailing garbage); only unparseable-leading content is "malformed" (`invalid JSON`) | test 03 step with `{"contributor":{"id":"global@example.com"}}}}` succeeds |
| B | diff grammar errors vs generic grammar errors | diff uses `snap: usage: snap diff …`; all other commands use `snap: invalid command or arguments` | tests 14, 24 |
| C | Terminal-mode error line format | Whole plain line (including `snap: ` prefix) wrapped: `S(31,"✗ " + line)` | test 28 |
| D | Terminal-mode warning format | `warning: ` prefix dropped; `S(33,"⚠") + " " + S(33,detail)` | test 28 |
| E | Deleted-symbol in status | U+2212 MINUS SIGN `−`, not ASCII hyphen | test 28 |
| F | Empty file commits as text with empty edit | `{"type":"text","edit":[]}`; diff hunk `@@ -1,0 +1,0 @@` | tests 06, 27 |
| G | Identity = parsed typed value | Key order/whitespace differences are duplicates, not collisions | test 26 |
| H | HEAD must send zero body bytes | raw-socket assertion would see any bytes | test 12 |
| I | `--serve` URL plain under `SNAP_COLOR=always` | exact plain URL asserted with case env always | test 28 |
| J | Version string echoed in unknown-version error | `snap: unknown version: (a@x->2)` | test 19 |

Genuinely open (spec-silent; choose conservatively, note in ledger):

1. **Trailing-byte tolerance scope**: only demonstrated for *config* files.
   repository.json tests always present well-formed JSON; spec §4.1 says readers
   accept "ordinary JSON whitespace and object-key order" (implies otherwise
   strict). Ruling adopted: repository.json parsing is strict (no trailing
   tolerance needed by any test); config parsing is lenient per test 03.
2. `--serve` with non-numeric or negative port: unspecified; any
   `snap: invalid port: <value>` / `snap: invalid command or arguments` exit-1
   behavior is safe.
3. Exact wording beyond the pinned substrings/regexes for validation errors is
   free (patterns like `^snap: .+positive safe integer\n$` allow any prefix).
4. HTTPS client: never exercised offline; URL recognition + transport-agnostic
   validation only.
5. Commit message with control chars other than tab/LF: spec says invalid;
   presumably `snap: invalid commit message` (only empty message is tested).
6. 4096-byte message boundary (≤ 4096 ok vs > 4096 reject) not tested at the edge.
7. Exit code 2 (internal failures): specified but untested.
8. Merge check order when the working tree is dirty AND the remote repository
   is invalid is not tested. Test 20 uses a valid remote + dirty tree
   (→ dirty error); test 26 uses a clean tree + invalid remote (→ validation
   error). Spec §10 lists validation/replay before the dirty check; either
   order passes the suite. Adopted: validate local repo, load+validate remote,
   dot-collision check, joined replay, then dirty/unsupported-tree check,
   then write.

## 16. Test inventory (28 cases)

| File | Name | Covers |
|---|---|---|
| 01-init.yaml | init creates an empty repository | init output `()`, .snap layout, empty repository.json |
| 02-init-paths.yaml | initialization preserves files and rejects nested or existing repositories | existing files kept, reinit/inside-repo errors, intermediate dir creation |
| 03-configuration.yaml | local and global contributor configuration have strict precedence | config write/read, local-over-global, malformed global, trailing-JSON tolerance, invalid id |
| 04-commit-status-log.yaml | commit status and log expose exact deterministic history | status codes/sort, commit versions, log format + escaping, clean-tree error |
| 05-diff-goldens.yaml | diff renders canonical repeated-line edits and missing final newlines | canonical diff goldens, stored edit scripts, version-pair diff, no-diff empty |
| 06-binary-and-empty.yaml | binary and empty files are versioned byte exactly | NUL→put, empty-file text edit, binary diff lines |
| 07-revert.yaml | revert is additive and restores file-directory transitions | revert versions/messages, file↔dir, no-op + dirty errors |
| 08-unsupported-entries.yaml | working tree scans reject symlinks and special files without mutation | symlink/fifo rejection exact errors, no mutation |
| 09-merge-text.yaml | local merge converges concurrent text changes and is idempotent | line OT, merge stdout, bidirectional convergence, re-merge no-op |
| 10-merge-conflicts.yaml | merge applies every whole-file conflict rule with sorted warnings | delete-wins/put-wins/later-put-wins, identical no-warning, warning order, final bytes |
| 11-namespace-conflicts.yaml | canonical namespace winners replace conflicting files in both directions | namespace-wins both directions, file↔directory replacement |
| 12-http-server.yaml | server exposes one immutable repository snapshot and exits on SIGTERM | serve URL, GET/HEAD/404/405, snapshot immutability + exact pretty bytes, SIGTERM/SIGINT exit 0, invalid repo startup |
| 13-http-client.yaml | HTTP merge and diff use one exact validated GET without redirects | remote merge/diff, no redirect following, invalid JSON, single-request discipline |
| 14-cli-errors.yaml | command grammar and common failures use stable exit channels | --version regex, not-a-repo, unknown command, diff usage, unknown version, invalid port |
| 15-repository-validation.yaml | repository reader rejects malformed schemas histories paths and edits | duplicate keys, gap, path invalid, base64, underconsume, prefix conflict, cycle, no-op, adjacent insert; files untouched |
| 16-dot-collision.yaml | cross-repository dot collisions fail before changing local state | patch collision message for diff --repo and merge, no mutation |
| 17-concurrent-creates.yaml | concurrent creates choose the canonical later value independent of merge direction | later-create-wins, direction independence |
| 18-three-way-convergence.yaml | three-way text history converges across different merge association orders | 3-patch OT across 6 association orders, exact merged text |
| 19-version-boundaries.yaml | CLI versions are canonical known causal frontiers | repo discovery from nested cwd, invalid/unknown version errors, revert to (), missing-id error |
| 20-dirty-merge.yaml | merge refuses dirty and unsupported working trees without importing history | dirty + symlink merge refusal, zero mutation |
| 21-version-algebra.yaml | vector clocks use causal closure componentwise join and canonical Snap order | joined frontier strings, version-vs-version diffs both directions |
| 22-ot-matrix.yaml | text OT covers overlapping deletes split counts insert priority and trailing inserts | OT transform matrix with exact outputs |
| 23-strict-validation-matrix.yaml | repository validation rejects every malformed layer before mutation | exact/pattern error strings for 12 malformed layers |
| 24-cli-grammar-matrix.yaml | every command rejects unknown misplaced duplicate and extra arguments | full grammar matrix, no stray file creation |
| 25-config-version-path-boundaries.yaml | configuration versions paths and text use their exact canonical boundaries | config rewrite drops unknown fields, duplicate config key, invalid ids/versions, unicode path sort, empty commit message |
| 26-portability-and-failure-safety.yaml | local exchange preserves text bytes and malformed remotes never mutate | CRLF/NUL/unicode bytes, cross-repo diff read-only, malformed local/HTTP remote no-mutation, typed-value identity |
| 27-history-canonicality.yaml | patch histories require exact schemas canonical order and valid base transitions | patch unknown field, patch/change sorting, dot consistency, token canonicality, create-over-present, text-over-binary |
| 28-terminal-presentation.yaml | terminal presentation is colorful readable and explicitly controllable | every terminal-mode layout, SNAP_COLOR/NO_COLOR matrix, serve URL plain |
