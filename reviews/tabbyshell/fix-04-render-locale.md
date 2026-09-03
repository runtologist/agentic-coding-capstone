# Fix 04 — Render: locale-independent `isoDate`

## Change
`Render.isoDate` previously formatted dates with the Scala `f` interpolator:

```scala
f"$y%04d-$m%02d-$d%02d"
```

The `f` interpolator uses the JVM default locale, which is host-dependent. While
`%d` integer formatting is locale-stable for digits, relying on the default
locale is fragile and non-idiomatic for deterministic output. Replaced with an
explicit root-locale format:

```scala
String.format(
  Locale.ROOT,
  "%04d-%02d-%02d",
  java.lang.Long.valueOf(y),
  java.lang.Long.valueOf(m),
  java.lang.Long.valueOf(d)
)
```

`java.util.Locale` was already imported. The civil-from-days arithmetic is
unchanged.

## Files changed
- `tabbyshell/src/main/scala/tabbyshell/Render.scala`

## Verification
- `isoDate(0)` == `1970-01-01`
- `isoDate(951782400)` == `2000-02-29` (leap day)
- `isoDate(1735689600)` == `2025-01-01`

Checked via a scratch `scala` run against the compiled classes (scratch file not
committed).

## Gates
- `sbt --client "compile; test; assembly"` — success, 1/1 unit test passed
- `sbt --client scalafmtCheckAll` — success
- Harness: `50 passed` in ~13.9s
