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
      test("color output applies the exact styles from spec section 6.6") {
        val esc = "\u001b"
        val input = Value
          .table(
            List("i", "s", "d", "t", "f", "g", "b"),
            List(
              List(
                VInt(1L),
                VStr("x"),
                VDate(0L),
                VBool(true),
                VBool(false),
                VFloat(1.5),
                VFilesize(1500L)
              )
            )
          )
          .toOption
          .get
        val rendered = Render.render(input, RenderOpts(color = true, now = 0L))
        val allEscapes = "\u001b\\[".r.findAllIn(rendered).length
        val resetCodes = "\u001b\\[0m".r.findAllIn(rendered).length
        val openCodes = allEscapes - resetCodes
        assertTrue(
          // borders and separators are dim
          rendered.contains(s"$esc[2m\u256d$esc[0m"),
          rendered.contains(s"$esc[2m\u2502$esc[0m"),
          // header text is bold cyan
          rendered.contains(s"$esc[1;36m # $esc[0m"),
          rendered.contains(s"$esc[1;36m i $esc[0m"),
          rendered.contains(s"$esc[1;36m s $esc[0m"),
          // index column cells are dim
          rendered.contains(s"$esc[2m 0 $esc[0m"),
          // Int and Filesize cells are green
          rendered.contains(s"$esc[32m 1 $esc[0m"),
          rendered.contains(s"$esc[32m 1.5 KB $esc[0m"),
          // Date cells are yellow
          rendered.contains(s"$esc[33m just now $esc[0m"),
          // Bool true is green, Bool false is red
          rendered.contains(s"$esc[32m true $esc[0m"),
          rendered.contains(s"$esc[31m false $esc[0m"),
          // Str, Null, and Float cells are unstyled
          rendered.contains(" x "),
          !rendered.contains(s"$esc[32m x $esc[0m"),
          !rendered.contains(s"$esc[33m x $esc[0m"),
          rendered.contains(" 1.5 "),
          !rendered.contains(s"$esc[32m 1.5 $esc[0m"),
          // every styled run is closed with a reset
          openCodes == resetCodes,
          openCodes > 0
        )
      }
    )
  )
}
