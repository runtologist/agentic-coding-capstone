# Fix 05: Parser & Main Strictness

## Changes

### Parser.scala
1. **joinContinuations** rewritten to return `List[String]` (one element per logical pipeline). Strict rule: a line continues only when `\` is the very last character (no trailing-whitespace tolerance). The backslash is dropped and a `\n` is kept in the buffer per spec §3.1/§7.3.
2. **Tokenizer whitespace**: `\n` and `\r` are now treated as whitespace, enabling multi-line logical lines to parse correctly.
3. **Comments**: `#` now skips to the next `\n` (not end-of-input), so comments on continued lines don't swallow subsequent physical lines.
4. **`.5` tokenization**: Removed the `(c == '.' && next.isDigit)` branch from both `tokenize()` and `parseLiteralValue()`. `.5` now falls through to `isWordStart('.')` and tokenizes as a bare-ident, per spec grammar (`number := '-'? [0-9]+ ('.' [0-9]+)?`).
5. **TBool/TNull as command heads**: `parseCommand` now accepts `TBool` and `TNull` tokens as command names (`"true"`, `"false"`, `"null"`), dispatching them as external commands per spec §3.3 ("no UnknownCommand kind").

### Main.scala
1. **`--eval` forces color=false**: The `(Some(script), _)` branch now passes `renderOpts.copy(color = false)` per spec §8.
2. **TABBY_NOW validation**: Invalid values now produce `TabbyError.BadArg("TABBY_NOW", "invalid unix seconds: <raw>")` and exit code 2, instead of silently falling back to system time. Extracted into `setupState` / `resolveNow` helpers.
3. **REPL continuation**: `readLogicalLine` now uses strict `line.endsWith("\\")` (no trailing-whitespace tolerance) and appends `"\n"` (not a space) to the buffer, consistent with Parser.joinContinuations.
4. **History**: `appendHistory` flattens embedded newlines to spaces so history file stays one-entry-per-line.
5. Removed unused `import scala.util.Try` and the now-deleted `endsWithContinuation`/`stripTrailingContinuation` helpers.

## Verification

- `sbt compile` — success
- `sbt test` — 1 test passed
- `sbt assembly` — jar built
- `sbt scalafmtCheckAll` — pass (after auto-format)
- Harness: **50/50 passed**

### Manual checks
| Test | Result |
|------|--------|
| `--eval-file` with continued pipeline (`ls \` + `| length`) | exit 0, correct output |
| `--eval "ls |"` | exit 1, parse error message |
| `TABBY_NOW=banana --eval "pwd"` | exit 2, stderr: "TABBY_NOW: invalid unix seconds: banana" |
| `TABBY_NOW` unset, `--eval "pwd"` | exit 0, correct output |

### --eval color verification
`--eval` now passes `renderOpts.copy(color = false)` unconditionally. In non-TTY environments color was already off; this ensures color is suppressed even when stdout is a TTY.
