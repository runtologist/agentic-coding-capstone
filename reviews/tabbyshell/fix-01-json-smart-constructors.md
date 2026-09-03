# Fix 01 — Json: route record construction through validating smart constructors

## Scope
Only `tabbyshell/src/main/scala/tabbyshell/Json.scala` was modified.

## Changes
1. `writeValue` — `VTable` case: replaced direct `VRecord(columns.zip(row))` construction with `Value.recordTrusted(columns.zip(row))`.
   - Rationale: the source `VTable` already guarantees unique columns and uniform row width, so each `columns.zip(row)` record is valid by construction.
2. `parseObject` — empty object early return: replaced `return VRecord(Nil)` with `return Value.recordTrusted(Nil)`.
   - Rationale: an empty field list trivially satisfies the no-duplicate-key invariant.
3. `parseObject` — final object construction: replaced `VRecord(fields.toList)` with
   `Value.record(fields.toList).fold(e => error(e.message), identity)`.
   - Rationale: parse input is external data, so the construction is routed through the validating smart constructor; any invariant violation is converted into the parser's normal `JsonParseError` channel.

## Gates
- `sbt --client "compile; test; assembly"`: success
- `sbt --client scalafmtCheckAll`: success
- `./capstones/tabbyshell/verify --lang scala --implementation-root capstone-scala/tabbyshell`: **50 passed** in 12723ms

## Remaining direct constructor uses
`grep -n "VRecord(" src/main/scala/tabbyshell/Json.scala` now shows only a pattern match:
- `case VRecord(fields) => writeObject(fields, indent, sb)`

No direct constructor calls remain in `Json.scala`.
