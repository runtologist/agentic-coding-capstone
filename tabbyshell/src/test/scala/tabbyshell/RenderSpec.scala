package tabbyshell

import zio.test.*

object RenderSpec extends ZIOSpecDefault {
  import Value.*

  private val opts = RenderOpts(color = false)

  override def spec = suite("Render")(
    suite("isoDate")(
      test("formats epoch and known dates with zero padding") {
        assertTrue(
          Render.isoDate(0L) == "1970-01-01",
          Render.isoDate(86399L) == "1970-01-01",
          Render.isoDate(86400L) == "1970-01-02",
          Render.isoDate(951782400L) == "2000-02-29",
          Render.isoDate(1735689600L) == "2025-01-01",
          Render.isoDate(-86400L) == "1969-12-31"
        )
      }
    ),
    suite("formatFloat")(
      test("trims trailing zeros and normalizes zero") {
        assertTrue(
          Render.formatFloat(0.0) == "0",
          Render.formatFloat(-0.0) == "0",
          Render.formatFloat(1.5) == "1.5",
          Render.formatFloat(2.0) == "2",
          Render.formatFloat(0.1) == "0.1",
          Render.formatFloat(100.0) == "100",
          Render.formatFloat(1.0 / 3.0) == "0.3333",
          Render.formatFloat(1.23456) == "1.2346",
          Render.formatFloat(-1.5) == "-1.5"
        )
      },
      test("passes through non-finite values") {
        assertTrue(
          Render.formatFloat(Double.NaN) == "NaN",
          Render.formatFloat(Double.PositiveInfinity) == "Infinity"
        )
      }
    ),
    suite("formatFilesize")(
      test("formats byte sizes with SI units and half-up rounding") {
        assertTrue(
          Render.formatFilesize(0L) == "0 B",
          Render.formatFilesize(999L) == "999 B",
          Render.formatFilesize(-999L) == "-999 B",
          Render.formatFilesize(1000L) == "1 KB",
          Render.formatFilesize(1500L) == "1.5 KB",
          Render.formatFilesize(1949L) == "1.9 KB",
          Render.formatFilesize(1950L) == "2 KB",
          Render.formatFilesize(-1500L) == "-1.5 KB",
          Render.formatFilesize(999999L) == "1000 KB",
          Render.formatFilesize(1000000L) == "1 MB",
          Render.formatFilesize(1500000L) == "1.5 MB",
          Render.formatFilesize(1000000000L) == "1 GB",
          Render.formatFilesize(1073741824L) == "1.1 GB",
          Render.formatFilesize(1000000000000L) == "1 TB"
        )
      }
    ),
    suite("formatDate")(
      test("renders relative dates against the injected now") {
        val now = 1735689600L
        assertTrue(
          Render.formatDate(now, now) == "just now",
          Render.formatDate(now - 30L, now) == "just now",
          Render.formatDate(now - 60L, now) == "1 minutes ago",
          Render.formatDate(now - 120L, now) == "2 minutes ago",
          Render.formatDate(now - 3599L, now) == "59 minutes ago",
          Render.formatDate(now - 3600L, now) == "1 hours ago",
          Render.formatDate(now - 7200L, now) == "2 hours ago",
          Render.formatDate(now - 86399L, now) == "23 hours ago",
          Render.formatDate(now - 86400L, now) == "yesterday",
          Render.formatDate(now - 172799L, now) == "yesterday",
          Render.formatDate(now - 172800L, now) == "2 days ago",
          Render.formatDate(now - 86400L * 5L, now) == "5 days ago",
          Render.formatDate(now - 86400L * 31L, now) == "1 months ago",
          Render.formatDate(now - 86400L * 90L, now) == "3 months ago",
          Render.formatDate(now - 86400L * 365L, now) == "2024-01-02",
          Render.formatDate(now + 100L, now) == "2025-01-01"
        )
      }
    ),
    suite("output")(
      test("adds a trailing newline unless the string already ends with one") {
        assertTrue(
          Render.output(VStr("hi"), opts) == "hi\n",
          Render.output(VStr("hi\n"), opts) == "hi\n",
          Render.output(VInt(5L), opts) == "5\n"
        )
      }
    ),
    suite("table layout")(
      test("renders a small table with box drawing characters") {
        val input = Value.table(List("name", "qty"), List(List(VStr("a"), VInt(1L)))).toOption.get
        val rendered = Render.render(input, opts)
        val top =
          "\u256d" + "\u2500" * 3 + "\u252c" + "\u2500" * 6 + "\u252c" + "\u2500" * 5 + "\u256e"
        val header = "\u2502 # \u2502 name \u2502 qty \u2502"
        val separator =
          "\u251c" + "\u2500" * 3 + "\u253c" + "\u2500" * 6 + "\u253c" + "\u2500" * 5 + "\u2524"
        val row = "\u2502 0 \u2502 a    \u2502   1 \u2502"
        val bottom =
          "\u2570" + "\u2500" * 3 + "\u2534" + "\u2500" * 6 + "\u2534" + "\u2500" * 5 + "\u256f"
        assertTrue(rendered == List(top, header, separator, row, bottom).mkString("\n"))
      },
      test("renders an empty table without a separator row") {
        val input = Value.table(List("a", "b"), Nil).toOption.get
        val rendered = Render.render(input, opts)
        val top =
          "\u256d" + "\u2500" * 3 + "\u252c" + "\u2500" * 3 + "\u252c" + "\u2500" * 3 + "\u256e"
        val header = "\u2502 # \u2502 a \u2502 b \u2502"
        val bottom =
          "\u2570" + "\u2500" * 3 + "\u2534" + "\u2500" * 3 + "\u2534" + "\u2500" * 3 + "\u256f"
        assertTrue(rendered == List(top, header, bottom).mkString("\n"))
      },
      test("truncates cells wider than maxColWidth with an ellipsis") {
        val narrow = RenderOpts(color = false, maxColWidth = 4)
        val input = Value.table(List("name"), List(List(VStr("abcdef")))).toOption.get
        val rendered = Render.render(input, narrow)
        val top = "\u256d" + "\u2500" * 3 + "\u252c" + "\u2500" * 6 + "\u256e"
        val header = "\u2502 # \u2502 name \u2502"
        val separator = "\u251c" + "\u2500" * 3 + "\u253c" + "\u2500" * 6 + "\u2524"
        val row = "\u2502 0 \u2502 abc\u2026 \u2502"
        val bottom = "\u2570" + "\u2500" * 3 + "\u2534" + "\u2500" * 6 + "\u256f"
        assertTrue(rendered == List(top, header, separator, row, bottom).mkString("\n"))
      },
      test("color output wraps styled cells in ANSI codes") {
        val input = Value.table(List("n"), List(List(VInt(1L)))).toOption.get
        val rendered = Render.render(input, RenderOpts(color = true))
        assertTrue(rendered.contains("\u001b["), rendered.contains("\u001b[0m"))
      }
    )
  )
}
