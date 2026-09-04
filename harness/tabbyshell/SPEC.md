# TabbyShell Specification

> A small, NuShell-inspired interactive shell where every command produces and
> consumes typed structured data. Three implementations (TypeScript, Rust,
> Scala) must behave identically against the test suite in `tests/`.

This document is the **canonical contract**. If the spec and an implementation
disagree, the implementation is wrong.

---

## 1. Overview

TabbyShell is an interactive REPL. Users type pipelines, the shell evaluates
them, and the result is rendered as a table (or scalar) using box-drawing
characters.

```
~/projects ❯ ls | where size > 10kb | sort-by size --reverse | first 3
╭───┬─────────────┬──────┬────────┬──────────────╮
│ # │ name        │ type │ size   │ modified     │
├───┼─────────────┼──────┼────────┼──────────────┤
│ 0 │ Cargo.lock  │ file │ 195 KB │ 2 hours ago  │
│ 1 │ README.md   │ file │  12 KB │ 3 days ago   │
│ 2 │ build.log   │ file │  11 KB │ yesterday    │
╰───┴─────────────┴──────┴────────┴──────────────╯
```

**Binary name:** `tabby`
**Module name (in spec, code, tests):** `tabbyshell`

---

## 2. Data model

A single sum type, identical in every implementation:

| Variant     | Carries                            | Notes                                          |
|-------------|------------------------------------|------------------------------------------------|
| `Null`      | —                                  | absence; pipelines start with `Null`           |
| `Bool`      | `bool`                             | `true` / `false`                               |
| `Int`       | 64-bit signed                      |                                                |
| `Float`     | 64-bit IEEE                        |                                                |
| `Str`       | UTF-8 string                       |                                                |
| `Filesize`  | `i64` bytes                        | rendered with SI units (`KB`, `MB`, `GB`)      |
| `Date`      | `i64` Unix seconds (UTC)           | rendered relative or ISO; see §6.4             |
| `List`      | ordered `Vec<Value>`               |                                                |
| `Record`    | ordered `Vec<(String, Value)>`     | preserves insertion order                      |
| `Table`     | ordered `Vec<Record>`              | every record MUST have the same keys, in order |

A `Table` constructor MUST reject ragged inputs (return a `TypeMismatch`
error). `Record` keys are ordered (no hash maps).

---

## 3. Surface language

### 3.1 Grammar

```
pipeline   := command ('|' command)*
command    := IDENT arg*
arg        := flag | literal | bare-ident | op | dash
flag       := '--' IDENT ('=' literal)? | '-' CHAR
literal    := number | filesize | string | bool | 'null'
number     := '-'? [0-9]+ ('.' [0-9]+)?
filesize   := number ('b' | 'kb' | 'mb' | 'gb' | 'kib' | 'mib' | 'gib')
string     := '"' (escape | non-quote-non-backslash)* '"'
            | "'" non-quote* "'"
escape     := '\\' ( '"' | '\\' | 'n' | 't' )
bool       := 'true' | 'false'
bare-ident := [a-zA-Z_./~] [a-zA-Z0-9_./~-]*
op         := '==' | '!=' | '<' | '<=' | '>' | '>='
dash       := '-'                   (standalone; used by `cd -`)
comment    := '#' until end of line
```

**Whitespace:** spaces and tabs separate tokens; newlines end a pipeline.

**Line continuation:** a line ending in a backslash followed immediately by a
newline is joined with the next line (the backslash and the newline are
dropped, then a single `\n` is appended to the buffer). This applies in both
REPL mode (§7.3) and `--eval-file` mode (§8). It is a lexical feature, not
a grammar one — by the time the parser sees the token stream, continuations
have already been resolved.

**Comments:** `#` to end of line (anywhere outside a string literal). Comments
work in both REPL input and `--eval-file` scripts.

**Bare idents** intentionally include `.`, `/`, and `~` so that path-style
arguments tokenize as single idents — `./foo`, `subdir/file`, `~/notes.txt`
are all single tokens. Path semantics are interpreted by the consuming
builtin (`ls`, `cd`, `cat`, `open`, …), not by the parser.

**Comparison operators** (`op`) are recognized as a single token class
wherever an argument is expected. They surface to commands as plain string
tokens (`"=="`, `"!="`, etc.); the only builtin that actually consumes
them is `where` (§5.6).

**Filesize units (case-insensitive):**
- SI: `b`=1, `kb`=1000, `mb`=1 000 000, `gb`=1 000 000 000
- IEC: `kib`=1024, `mib`=1 048 576, `gib`=1 073 741 824

Numbers and filesizes both *parse* as their respective types — `5` is `Int`,
`5b` is `Filesize`.

### 3.2 Out of scope

No variables, blocks, closures, `$in`, string interpolation, ranges, cell
paths beyond a single identifier, or arithmetic in arguments.

### 3.3 Errors

Every error has a stable message format. The participant tests assert on
prefixes; the table below is the contract.

| Kind             | Message format                                       |
|------------------|------------------------------------------------------|
| `Parse`          | `parse error: <detail> at column <n>`                |
| `TypeMismatch`   | `<command>: expected <type>, got <type>`             |
| `MissingColumn`  | `<command>: column not found: <name>`                |
| `MissingArg`     | `<command>: missing required argument: <name>`       |
| `BadArg`         | `<command>: <detail>`                                |
| `IoError`        | `<command>: <os-message>`                            |
| `ExternalFailed` | `<name>: external command exited with status <n>`    |

There is no `UnknownCommand` kind: any head identifier that is not a builtin
is dispatched as an external command (§5.15), which surfaces problems as
`ExternalFailed` (non-zero exit) or `IoError` (binary not found). The
`<name>` slot in `MissingArg` may hold a multi-word phrase when a command
requires several arguments together (e.g. `where: missing required
argument: column op literal`).

Errors abort the current pipeline but never the REPL.

---

## 4. Pipeline semantics

A pipeline is a sequence of commands joined by `|`. Each command receives the
output of the previous command as its **input value**. The first command
receives `Null`.

```
ls                    # input = Null, output = Table
ls | first 3          # input to `first` = Table, output = Table
```

Commands that ignore their input (`ls`, `pwd`, `open`, `cat`, `cd`) still
participate in the pipeline; the user is free to put them mid-pipeline (the
input is discarded).

Errors propagate by aborting the pipeline; subsequent commands do not run.

---

## 5. Built-in commands

In all signatures: `<arg>` is required, `[arg]` optional, `{type}` is the
expected input value type.

### 5.1 `ls [path] [-a] [-l]`

- Input: ignored
- Output: `Table` with columns `name, type, size, modified` (and `mode, uid`
  with `-l`)
- `path` defaults to `.`. Resolved against `cwd`.
- `type` ∈ {`"file"`, `"dir"`, `"symlink"`}.
- `size` is `Filesize`. Directory size is `0b`.
- `modified` is `Date`.
- `-a`/`--all`: include dotfiles.
- `-l`/`--long`: include `mode` (string `"rwxr-xr-x"`) and `uid` (`Int`).
- Rows ordered by name ascending (case-sensitive).

### 5.2 `open <path>`

- Input: ignored
- Output: depends on extension of `path`:
  - `.json` → parsed JSON value (numbers → `Int`/`Float`, arrays → `List`,
    objects → `Record`, arrays of objects with uniform keys → `Table`)
  - `.csv` → `Table` with column names from the header row, all cell values
    as `Str`
  - else → `Str` (raw UTF-8 contents)
- IO errors → `IoError`.

### 5.3 `cat <path>`

- Input: ignored
- Output: `Str` with the raw UTF-8 contents of `path` (no parsing).

### 5.4 `pwd`

- Input: ignored
- Output: `Str` of the current working directory (absolute, no trailing slash
  except for root).

### 5.5 `cd [path]`

- Input: ignored
- Output: `Null`
- Side effect: mutates `ShellState.cwd`.
- `cd` (no arg) → `$HOME`.
- `cd -` → previous cwd (errors with `BadArg` if no previous cwd in this
  session).
- Path is resolved against current `cwd`. `~` expansion: a leading `~/`
  expands to `$HOME`.
- Path that doesn't exist (or any other filesystem-level failure on `stat`)
  → `IoError` carrying the OS message.
- Path that exists but isn't a directory → `BadArg` with message
  `cd: not a directory: <path>`. (Split intentionally: `IoError` reports
  the OS message verbatim; `BadArg` is a structured error from TabbyShell
  itself.)

### 5.6 `where <col> <op> <literal>`

- Input: `Table`
- Output: `Table` (same columns)
- `op` ∈ {`==`, `!=`, `<`, `<=`, `>`, `>=`}
- `<col>` accepts a bare-ident only (NOT a quoted string literal — diverges
  from `select`/`sort-by`/`get`, which accept either form). Must exist in
  every row → otherwise `MissingColumn`.
- The literal is compared against the cell value; types must be compatible
  (Int/Float/Filesize compare numerically across all three; Str compares
  lexicographically; Bool only with `==`/`!=`; Date with anything except
  Date is `TypeMismatch`).

### 5.7 `select <col>...`

- Input: `Table`
- Output: `Table` with only the named columns, in the order given.
- Each `<col>` accepts a bare-ident or a quoted string literal.
- Missing column → `MissingColumn`.

### 5.8 `sort-by <col> [--reverse]`

- Input: `Table`
- Output: `Table` (same columns, rows reordered)
- `<col>` accepts a bare-ident or a quoted string literal.
- Sort is **stable**.
- Numeric types (`Int`, `Float`, `Filesize`) sort numerically; `Str`
  lexicographically; `Date` chronologically; `Bool` `false < true`.
- Mixed-type column → `TypeMismatch`.
- `--reverse`/`-r` reverses the result.

### 5.9 `first [n]`

- Input: `Table` or `List`
- Output:
  - With no argument: the first element (a `Record` for tables, the element
    type for lists). Empty input → `BadArg`.
  - With `n`: a `Table`/`List` containing up to the first `n` elements.
- `first 0` → empty `Table`/`List` of the same shape.

### 5.10 `last [n]`

Symmetric with `first`.

### 5.11 `length`

- Input: `Table` | `List` | `Str` | `Null`
- Output: `Int`
  - `Table`/`List`: number of rows/elements
  - `Str`: number of Unicode code points (NOT UTF-8 bytes — a multi-byte
    code point counts as one).
  - `Null`: `0`
- Other types → `TypeMismatch`.

### 5.12 `get <col>`

- Input: `Table` → `List` of the named column's cells
- Input: `Record` → the named field's value
- `<col>` accepts a bare-ident or a quoted string literal.
- Missing column/field → `MissingColumn`.

### 5.13 `to <fmt>`

- Input: any
- Output: `Str`
- `fmt` ∈ {`json`, `csv`}
- `to json`: pretty-printed (2-space indent), trailing newline.
  Variant → JSON shape:
  - `Null` → `null`
  - `Bool` → `true` / `false`
  - `Int` → JSON integer (no quoting)
  - `Float` → JSON number
  - `Str` → JSON string (escapes per RFC 8259)
  - `Filesize` → bare integer (the byte count); the `kb` / `mb` suffix
    is a render-time concern only.
  - `Date` → bare integer (the Unix-seconds value).
  - `List` → JSON array of recursively serialized values.
  - `Record` → JSON object with keys in insertion order; values
    recursively serialized.
  - `Table` → JSON array, one object per row, each with keys in
    insertion order (equivalent to a `List` of `Record`).
- `to csv`: requires `Table`; emits header row + comma-separated rows;
  RFC 4180 quoting for fields containing `,`, `"`, or newline. Other input
  types → `TypeMismatch`.

### 5.14 `save <path>`

- Input: any
- Output: `Null`
- Behavior:
  - If path ends in `.json`: writes `to json` regardless of input shape
    (a scalar `Int(5)` saves as `5\n`).
  - Else if path ends in `.csv` and input is `Table`: writes `to csv`.
    A `.csv` extension with non-`Table` input falls through to the
    next branch.
  - Else if input is `Str`: writes the raw bytes (no trailing newline
    added).
  - Otherwise: writes the renderer's plain-text output (color stripped).

### 5.15 External commands (AI-formatted)

If the head identifier is not a builtin, TabbyShell forks it as a process
with the literal arguments rendered as strings:

1. Run `<name> <arg>...` in `cwd`. Capture stdout (UTF-8, lossy).
2. If exit ≠ 0 → `ExternalFailed`.
3. Otherwise, send the stdout to the OpenRouter chat-completions endpoint
   with the prompt below. The response is processed leniently before JSON
   parsing: any leading ` ```json ` or ` ``` ` fence and matching trailing
   ` ``` ` are stripped, even though the system prompt forbids them. The
   cleaned text is then parsed as JSON; the result is the command's
   output value.
4. On any AI failure (no `OPENROUTER_API_KEY`, `TABBY_DISABLE_AI` set,
   network error, timeout, parse error, response doesn't match the schema)
   the fallback output is `Str(stdout.trimEnd())` — trailing whitespace
   stripped, leading whitespace preserved — and a single dim warning line
   is written to stderr: `(ai formatting unavailable: <reason>)`. The
   pipeline does **not** error.

**OpenRouter request:**
- Endpoint: `https://openrouter.ai/api/v1/chat/completions`
- Model: `google/gemini-2.5-flash-lite`
- Temperature: `0`
- Timeout: `30s`
- System prompt:
  ```
  You convert raw command output into structured data for a typed shell.
  Reply with ONLY a JSON object, no prose, no markdown fences.

  Schema:
    {"kind":"table","columns":["..."],"rows":[["...", "..."]]}
    {"kind":"string","value":"..."}

  All cells in "rows" are strings. Use "table" only when the output has
  clear tabular structure with consistent columns. Otherwise use "string"
  with the cleaned-up text.
  ```
- User content: `command: <name> <args>\n\noutput:\n<stdout>`

The returned `table` is converted to a `Value::Table` of `Str` cells; the
`string` is converted to `Value::Str`.

**Environment variables:**
- `OPENROUTER_API_KEY` (required for AI formatting; absent → fallback path).
- `OPENROUTER_BASE_URL` (optional) overrides the endpoint host (used by
  tests with a mock server).
- `TABBY_DISABLE_AI` (optional) — when set to any non-empty value, the AI
  call is skipped entirely and the fallback path is taken. The dim
  warning line still goes to stderr with reason `disabled`. Used by
  conformance tests (`tests/45-ai-fallback.yaml`) to exercise the
  fallback deterministically.

---

## 6. Renderer

The renderer is a pure function

```
render(value: Value, opts: RenderOpts) -> String
```

with `RenderOpts { color: bool, max_col_width: usize = 40, now: i64 }`.

When `color: false`, output **must be byte-identical** across all three
implementations.

### 6.1 Box-drawing characters

```
top:    ╭ ─ ┬ ─ ╮
side:   │     │     │
sep:    ├ ─ ┼ ─ ┤
bot:    ╰ ─ ┴ ─ ╯
```

Characters: `╭`, `╮`, `╰`, `╯`, `─`, `│`, `├`, `┤`, `┬`, `┴`, `┼`.

### 6.2 Layout rules

- Each cell has 1 space of left and right padding.
- Numeric columns (every cell is `Int`, `Float`, or `Filesize`) right-align;
  all others left-align.
- Column width = max display width of header and any cell in that column,
  capped at `max_col_width`.
- Strings longer than `max_col_width` are truncated with a trailing `…`
  (counted as 1 column in the visible width).

### 6.3 Per-variant rendering

| Value             | Rendering                                                |
|-------------------|----------------------------------------------------------|
| `Null`            | empty string                                             |
| `Bool`            | `true` / `false`                                         |
| `Int`             | base-10                                                  |
| `Float`           | `%.4f`, then trim trailing zeros and trailing `.`        |
| `Str`             | unquoted, raw                                            |
| `Filesize`        | see §6.5                                                 |
| `Date`            | see §6.4                                                 |
| `List<scalar>`    | 2-column table, headers `#`, `value`                     |
| `Record`          | 2-column table, headers `key`, `value`                   |
| `Table`           | n+1 column table, first column `#` (0-based row index)   |
| `List<non-scalar>`| if every element is a `Record` with identical keys, render as `Table`; otherwise render each element on its own line as `<index>: <inline render>` (compact JSON-ish) |

The compact JSON-ish inline form used in `List<non-scalar>` (and as a cell
value when a `Record` / `List` / `Table` appears inside a row) is:

- `Str` → JSON-escaped and quoted: `"foo"` / `"with \"quotes\""`.
- `Record` → `{key1: value1, key2: value2}` (no key quoting; values
  recursively inlined).
- `List` → `[v1, v2, …]`.
- `Table` → `<table N×M>` where N is the row count and M is the column
  count, using the Unicode multiplication sign U+00D7 (`×`). Nested
  tables never recurse into their cells in the inline form.

Trailing newline after every render call, **except** when the rendered
output is a `Str` that already ends in `\n` — the renderer emits the
string as-is to avoid emitting `\n\n`.

### 6.4 Date formatting

Given `delta = now - ts` in seconds:

| Range                       | Format                       |
|-----------------------------|------------------------------|
| `delta < 60`                | `"just now"`                 |
| `delta < 3600`              | `"<n> minutes ago"`          |
| `delta < 86400`             | `"<n> hours ago"`            |
| `delta < 86400 * 2`         | `"yesterday"`                |
| `delta < 86400 * 30`        | `"<n> days ago"`             |
| `delta < 86400 * 365`       | `"<n> months ago"` (n=days/30) |
| else                        | ISO date, `"YYYY-MM-DD"`     |

Negative deltas (future dates) render as ISO unconditionally.

**Parity carve-out:** byte-identical ISO output is guaranteed only for
`ts >= 0` (dates from 1970-01-01 UTC onward). The TypeScript implementation
uses `Math.floor(ts / 86400)` for the floor-division step; Rust and Scala
use an `if ts >= 0 then ts/86400 else -((-ts + 86399)/86400)` form. These
agree for non-negative `ts` but can differ by one day when `ts < 0`. The
YAML conformance suite uses only post-epoch dates, so the divergence is
unobservable in practice. Implementations are free to use either form for
`ts < 0`; the SPEC does not pick one.

ISO formatting uses Howard Hinnant's `civil_from_days`; no library required.
The +719468 shift moves the days-from-Unix-epoch onto the algorithm's internal
zero point (0000-03-01, proleptic Gregorian).

```
days = ts / 86400 (floor div)
days = days + 719468
era  = days >= 0 ? days / 146097 : (days - 146096) / 146097
doe  = days - era * 146097
yoe  = (doe - doe/1460 + doe/36524 - doe/146096) / 365
y    = yoe + era * 400
doy  = doe - (365*yoe + yoe/4 - yoe/100)
mp   = (5*doy + 2) / 153
d    = doy - (153*mp + 2)/5 + 1
m    = mp < 10 ? mp + 3 : mp - 9
y    = m <= 2 ? y + 1 : y
format: "YYYY-MM-DD" (zero-padded)
```

### 6.5 Filesize formatting

SI units, no IEC on output:

```
if abs(bytes) < 1000:           "<n> B"
elif abs(bytes) < 1_000_000:    "<n.n> KB"   (1 decimal, trim trailing 0 and .)
elif abs(bytes) < 1e9:          "<n.n> MB"
elif abs(bytes) < 1e12:         "<n.n> GB"
else:                           "<n.n> TB"
```

Rounding: round-half-away-from-zero to one decimal (`1949` → `1.9 KB`,
`1950` → `2 KB` after trimming).

### 6.6 ANSI when color=true

| Element             | Style                |
|---------------------|----------------------|
| Box-drawing borders | dim                  |
| Header text         | bold cyan            |
| Index column (`#`)  | dim                  |
| `Filesize`/`Int`    | green                |
| `Date`              | yellow               |
| `Bool true`         | green                |
| `Bool false`        | red                  |
| Errors              | bold red, prefixed `✗ ` |

Variants not listed (`Null`, `Float`, `Str`) render without color. The
omission is intentional: `Float` was deliberately left uncolored to keep
mixed numeric tables visually clean, and `Str` is the most common cell
type so coloring it would dominate the output.

Color is fully suppressed when `color: false` (and never written to the
output buffer at all — not "rendered then stripped").

---

## 7. REPL

### 7.1 Startup

1. Print `banner.txt` to stdout.
2. Print one line: `tabbyshell 0.1.0 — type 'exit' or Ctrl-D to quit`.
3. Initialize history from `~/.tabbyshell_history` (if present, max 1000
   entries).

### 7.2 Prompt

`<short-cwd> ❯ ` where `<short-cwd>` is:
- `~` if cwd == `$HOME`
- `~/sub/path` if cwd is under `$HOME`
- absolute path otherwise

### 7.3 Line continuation

A line ending with `\` (with no following character) is continued: the `\`
is dropped, a newline is appended to the buffer, and the prompt becomes two
spaces. Empty lines flush nothing. A non-continued line evaluates the entire
buffer as one pipeline.

### 7.4 Quit

- `exit` or `quit` (alone on a line) → goodbye. Both are accepted as
  synonyms; tests assert the goodbye line, not which keyword triggered it.
- Ctrl-D / EOF on empty input → goodbye.
- Ctrl-C: discard the current input buffer, fresh prompt; no exit. The
  TypeScript implementation writes a single blank `\n` to stdout before
  the fresh prompt; Rust and Scala do not. This cosmetic difference is
  outside the parity guarantee in §10 (the SIGINT path is interactive-
  only and unobservable through `--eval` / `--eval-file`).

### 7.5 Errors

Errors render as `✗ <message>` (bold red when color is on) on stderr; the
REPL continues.

### 7.6 Goodbye

Print `goodbye.` (dim gray when color is on) and exit 0.

---

## 8. Non-interactive modes (test affordances)

These exist for the test harness; they are not the user-facing experience.

| Flag                         | Behavior                                              |
|------------------------------|-------------------------------------------------------|
| `--eval <pipeline>`          | Run one pipeline. `render(color=false)` to stdout. Exit 0 on success, 1 on user error, 2 on internal error. (See parity note below.) |
| `--eval-file <path>` (`-` for stdin) | Read each line as a pipeline; `\` continuation and `#` comments work (per §3.1 and §7.3). Print rendered output of each. Stops on first error and propagates the underlying exit code: 1 for user error, 2 for internal error. |
| `--no-color`                 | Force color off even in TTY.                          |
| `--interactive`              | Force the REPL even when stdin is not a TTY. Used by tests that need to drive an interactive session deterministically. |
| `--version`                  | Print `tabbyshell <version>`, exit 0.                 |

**Color resolution order** (first match wins):
1. `--no-color` flag → color off.
2. `NO_COLOR` env var set to any non-empty value → color off (honors the
   convention from `https://no-color.org`).
3. `stdout` is a TTY → color on.
4. Otherwise → color off.

**Environment:**
- `TABBY_NOW=<unix-seconds>` overrides `now` (for deterministic Date
  rendering in tests). When unset, `now()` is system time.
- `NO_COLOR` — see color resolution above.

**`--eval` exit-code parity carve-out:** TypeScript and Scala distinguish
"user error" (1) from "internal error" / unexpected exception (2) inside
the `--eval` path. The Rust implementation only emits exit 2 for
arg-parse failures and REPL-fatal errors; an unexpected exception thrown
inside a pipeline aborts the process via `panic`, which the OS surfaces
as a non-2 exit code. The conformance suite asserts only that user
errors exit 1 and successes exit 0; the exit-2 path is permitted to
differ.

---

## 9. State

```
ShellState {
  cwd:       absolute path
  prev_cwd:  optional absolute path (for `cd -`)
  home:      absolute path (read once from $HOME)
  now:       i64 (TABBY_NOW or system time)
  color:     bool
}
```

`cd` is the only command that mutates state.

---

## 10. Cross-language parity

The following must be byte-identical across TypeScript, Rust, and Scala
implementations:

1. Output of `tabby --eval --no-color "<pipeline>"` for any pipeline that
   doesn't depend on system time, FS race conditions, the AI fallback, or
   a pre-epoch (`ts < 0`) Date value (see §6.4 carve-out).
2. Error message strings (per §3.3 and the per-command sections).
3. Banner contents *when* `banner.txt` is found by the implementation's
   search strategy. Each implementation locates `banner.txt` differently
   (TS via `import.meta.url`; Rust by walking up from `current_exe()`;
   Scala from the working directory), so the banner can silently differ
   if the binary is run from an unexpected location. The YAML conformance
   suite asserts banner contents only when invoked through the harness,
   which sets a predictable cwd.
4. Help text (none for v1 — no `--help` flag beyond the five listed in §8).

The following are **explicitly not** part of the parity guarantee:

- Exit codes 2 ("internal error") from `--eval` — Rust's behavior differs
  (see §8 carve-out).
- The `\n` written by TypeScript's SIGINT handler before the fresh prompt
  (§7.4).
- Date rendering for `ts < 0` (see §6.4).

The YAML test suite in `tests/` is the executable form of this contract.
