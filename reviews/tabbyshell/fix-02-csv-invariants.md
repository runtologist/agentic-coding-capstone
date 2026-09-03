# Fix 02: CSV invariants

## Summary
Updated `Csv.scala` so CSV parsing constructs tables only through `Value`'s validating smart constructors and no longer pads/truncates ragged rows. Also fixed blank-line handling so blank lines do not produce phantom one-empty-field rows.

## Changed files
- `tabbyshell/src/main/scala/tabbyshell/Csv.scala`

## Details

### Smart constructor usage in `Csv.parse`
Previous behavior:
- Empty parsed rows returned `Value.VTable(List.empty, List.empty)` directly.
- Non-empty rows used `Value.VTable(columns, dataRows)` directly.
- Data rows were padded/truncated to the header width.

New behavior:
- Empty parsed rows return `Value.tableTrusted(List.empty, List.empty)`.
- Non-empty rows map each field to `Value.VStr` and call `Value.table(columns, dataRows)`.
- Ragged rows or duplicate header columns now return `Left` with the construction error message, which `Executor.openFile` surfaces as a `BadArg` error prefixed by `open:`.

This means malformed CSV like:

```csv
name,age,city
Alice,30
Bob,25,Seattle
```

now fails with:

```text
open: row 0 has 2 columns, expected 3
```

instead of silently padding the short row.

### Blank-line handling in `Csv.parseRows`
Previous `endRow()` always called `endField()`, so a blank line produced a phantom row `List("")`. The final `rows.toList.filter(_.nonEmpty)` did not remove that because `List("")` is non-empty.

New behavior tracks `rowHasContent`:
- set true when a normal character is appended to a field
- set true when a comma is seen
- set true when a quote is opened or processed inside a quoted field

`endRow()` emits a row only when `rowHasContent` is true.

Behavior requirements verified:
- blank lines produce no row
- `,` produces a row of two empty fields
- quoted empty fields still count as content
- trailing newline does not create an extra row
- BOM is still stripped

## Validation

### sbt gates
From `tabbyshell/`:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
sbt --client "compile; test; assembly"
sbt --client scalafmtCheckAll
```

Result:
- compile: success
- tests: 1 passed, 0 failed
- assembly: success
- scalafmtCheckAll: success

### Harness
From worktree root:

```bash
/Users/sschenk/ziverge/vibe-coding-2-workshop/capstones/tabbyshell/verify \
  --lang scala \
  --implementation-root "$(pwd)/tabbyshell"
```

Result: `50 passed`.

### Manual fixture checks

Fixture `/tmp/blank.csv`:

```csv
name,age,city
Alice,30,Portland

Bob,25,Seattle
```

Command:

```bash
TABBY_NOW=1700000000 java -jar tabbyshell/target/scala-3.3.8/tabbyshell-assembly-0.1.0.jar --eval "open /tmp/blank.csv"
```

Result: exit 0, table contains Alice and Bob only, no blank phantom row.

Observed table rows:

```text
│ 0 │ Alice │ 30  │ Portland │
│ 1 │ Bob   │ 25  │ Seattle  │
```

Fixture `/tmp/ragged.csv`:

```csv
name,age,city
Alice,30
Bob,25,Seattle
```

Command:

```bash
TABBY_NOW=1700000000 java -jar tabbyshell/target/scala-3.3.8/tabbyshell-assembly-0.1.0.jar --eval "open /tmp/ragged.csv"
```

Result: exit 1 with:

```text
✗ open: row 0 has 2 columns, expected 3
```

Fixture `/tmp/quoted.csv`:

```csv
name,note
a,"x,y"
b,"say ""hi"""
```

Result: exit 0, quoted comma and escaped quote parsed correctly.

Fixture `/tmp/header_only.csv`:

```csv
name,age
```

Result: exit 0, renders header-only table.

Fixture `/tmp/only_blank.csv` containing only blank lines:

Result: exit 0, renders empty table, matching the previous empty-table rendering behavior.

Fixture `/tmp/bom.csv` with UTF-8 BOM:

Result: exit 0, BOM stripped and header parsed as `name`.

## Residual risks
- CSV files with only blank lines now produce an empty table rather than a table with one empty-string column and blank rows; this is believed to be more correct and is not covered by the public harness.
- Ragged CSVs now fail instead of being padded; this is the intended invariant behavior but is a user-visible behavior change for malformed CSV input.
