# Fix 03 — External: validate AI tables via Value.table; resolveUrl handles /v1 suffix

## Scope
Only `tabbyshell/src/main/scala/tabbyshell/External.scala` was modified.

## Changes

### 1. `parseAiResponse` — table construction now goes through the validating smart constructor
Before, rows were padded/truncated to the header width and passed to the private `VTable` constructor:
```scala
val normalizedRows = rows.map { row =>
  columns.indices.map(i => row.lift(i).getOrElse(VStr(""))).toList
}
VTable(columns, normalizedRows)
```
Now, the parsed rows are handed directly to `Value.table`, which enforces the
invariants (unique columns, uniform row width). Any violation becomes a `Left`
error string, which `callAi` wraps in a `RuntimeException` and the existing
`catchAll` in `formatWithAi` converts to the standard AI-fallback behavior
(spec §5.15: any AI failure falls back to `Str(stdout)` with a dim stderr warning):
```scala
table <- Value.table(columns, rows).left.map(_.message)
} yield table
```

### 2. `resolveUrl` — handle bare `/v1` suffix
Previously, only `/api/v1` was recognized as an API-version suffix; a base URL
like `https://x/v1` would become `https://x/v1/api/v1/chat/completions`.
The check is now `endsWith("/v1")`, which covers both `/v1` and `/api/v1`.

## resolveUrl verification (identical logic run in scala)
| Input | Output |
|---|---|
| `None` | `https://openrouter.ai/api/v1/chat/completions` |
| `Some("https://x/api/v1")` | `https://x/api/v1/chat/completions` |
| `Some("https://x/v1")` | `https://x/v1/chat/completions` |
| `Some("https://x/v1/")` | `https://x/v1/chat/completions` |
| `Some("https://x/api/v1/chat/completions")` | `https://x/api/v1/chat/completions` (unchanged) |
| `Some("https://x")` | `https://x/api/v1/chat/completions` |

All six match the expected values.

## Gates
- `sbt --client "compile; test; assembly"` — success (1 unit test passed, assembly built)
- `sbt --client scalafmtCheckAll` — success
- Harness: `50 passed` in ~12.8s

## Remaining direct constructor references in External.scala
Only pattern matches remain (e.g. `case VRecord(fields) => ...` in `getField`),
which do not construct values. No direct `VTable(...)`/`VRecord(...)` constructor
calls remain in this file.
