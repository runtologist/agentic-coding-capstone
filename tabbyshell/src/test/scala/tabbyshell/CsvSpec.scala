package tabbyshell

import zio.test.*

object CsvSpec extends ZIOSpecDefault {
  import Value.*

  private def table(columns: List[String], rows: List[List[Value]]): VTable =
    Value.table(columns, rows).toOption.get

  override def spec = suite("Csv")(
    suite("parse")(
      test("parses a header and rows into a table of strings") {
        val expected = table(
          List("name", "age"),
          List(List(VStr("Alice"), VStr("30")), List(VStr("Bob"), VStr("25")))
        )
        assertTrue(Csv.parse("name,age\nAlice,30\nBob,25\n") == Right(expected))
      },
      test("blank lines do not produce rows") {
        val expected = table(List("a", "b"), List(List(VStr("1"), VStr("2"))))
        assertTrue(Csv.parse("a,b\n\n1,2\n\n") == Right(expected))
      },
      test("a missing trailing newline still parses the last row") {
        val expected = table(List("a", "b"), List(List(VStr("1"), VStr("2"))))
        assertTrue(Csv.parse("a,b\n1,2") == Right(expected))
      },
      test("CRLF line endings are handled") {
        val expected = table(List("a", "b"), List(List(VStr("1"), VStr("2"))))
        assertTrue(Csv.parse("a,b\r\n1,2\r\n") == Right(expected))
      },
      test("a leading BOM is stripped") {
        val expected = table(List("a", "b"), List(List(VStr("1"), VStr("2"))))
        assertTrue(Csv.parse("\uFEFFa,b\n1,2") == Right(expected))
      },
      test("quoted fields keep commas and escaped quotes") {
        val expected = table(
          List("a", "b"),
          List(List(VStr("x,y"), VStr("he said \"hi\"")))
        )
        assertTrue(Csv.parse("a,b\n\"x,y\",\"he said \"\"hi\"\"\"\n") == Right(expected))
      },
      test("a header-only file produces an empty table") {
        val expected = table(List("a", "b"), Nil)
        assertTrue(Csv.parse("a,b\n") == Right(expected))
      },
      test("empty input produces an empty table") {
        assertTrue(Csv.parse("") == Right(table(Nil, Nil)))
      },
      test("ragged rows are rejected") {
        assertTrue(
          Csv.parse("a,b\n1\n") == Left("row 0 has 1 columns, expected 2"),
          Csv.parse("a,b\n1,2,3\n") == Left("row 0 has 3 columns, expected 2")
        )
      },
      test("duplicate header columns are rejected") {
        assertTrue(Csv.parse("a,a\n1,2\n") == Left("duplicate column: a"))
      },
      test("quoted fields can contain embedded newlines") {
        val expected = table(List("h1", "h2"), List(List(VStr("line1\nline2"), VStr("x"))))
        assertTrue(Csv.parse("h1,h2\n\"line1\nline2\",x\n") == Right(expected))
      },
      test("quoted fields preserve CRLF inside quotes") {
        val expected = table(List("h"), List(List(VStr("a\r\nb"))))
        assertTrue(Csv.parse("h\n\"a\r\nb\"\n") == Right(expected))
      },
      test("bare CR line endings are accepted") {
        val expected = table(List("a", "b"), List(List(VStr("1"), VStr("2"))))
        assertTrue(Csv.parse("a,b\r1,2\r") == Right(expected))
      },
      test("empty quoted fields parse as empty strings") {
        val expected = table(List("a", "b"), List(List(VStr(""), VStr("x"))))
        assertTrue(Csv.parse("a,b\n\"\",x\n") == Right(expected))
      },
      test("escaped quotes at field boundaries parse") {
        val expected = table(List("a"), List(List(VStr("\""))))
        assertTrue(Csv.parse("a\n\"\"\"\"\n") == Right(expected))
      },
      test("unterminated quoted input flushes into a ragged row") {
        assertTrue(Csv.parse("a,b\n\"x,y") == Left("row 0 has 1 columns, expected 2"))
      },
      test("empty header names are allowed") {
        val expected = table(List("", "b"), List(List(VStr("1"), VStr("2"))))
        assertTrue(Csv.parse(",b\n1,2\n") == Right(expected))
      },
      test("BOM and CRLF work without trailing newline") {
        val expected = table(List("a", "b"), List(List(VStr("1"), VStr("2"))))
        assertTrue(Csv.parse("\uFEFFa,b\r\n1,2") == Right(expected))
      }
    ),
    suite("toCsv")(
      test("escapes commas and quotes and renders null as an empty cell") {
        val input = table(
          List("a", "b"),
          List(List(VStr("x,y"), VStr("q\"z")), List(VNull, VInt(5L)))
        )
        assertTrue(Csv.toCsv(input) == "a,b\n\"x,y\",\"q\"\"z\"\n,5\n")
      },
      test("writes a header row and one row per table row") {
        val input = table(List("name", "qty"), List(List(VStr("apple"), VInt(10L))))
        assertTrue(Csv.toCsv(input) == "name,qty\napple,10\n")
      },
      test("quotes fields containing newlines and carriage returns") {
        val input = table(List("h"), List(List(VStr("a\nb")), List(VStr("c\rd"))))
        assertTrue(Csv.toCsv(input) == "h\n\"a\nb\"\n\"c\rd\"\n")
      },
      test("quotes headers containing commas") {
        val input = table(List("a,b"), List(List(VStr("x"))))
        assertTrue(Csv.toCsv(input) == "\"a,b\"\nx\n")
      },
      test("serializes scalar variants as plain CSV cells") {
        val input = table(
          List("c"),
          List(
            List(VNull),
            List(VBool(true)),
            List(VInt(-7L)),
            List(VFloat(2.0)),
            List(VFilesize(1500L)),
            List(VDate(0L))
          )
        )
        assertTrue(Csv.toCsv(input) == "c\n\ntrue\n-7\n2\n1500\n0\n")
      },
      test("serializes nested values with inline rendering and escaping") {
        val input = table(
          List("c"),
          List(
            List(VList(List(VInt(1L), VInt(2L)))),
            List(Value.recordTrusted(List("k" -> VBool(false)))),
            List(Value.tableTrusted(List("a"), Nil))
          )
        )
        val expected = "c\n\"[1, 2]\"\n{k: false}\n<table 0\u00d71>\n"
        assertTrue(Csv.toCsv(input) == expected)
      },
      test("writes only the header for an empty table") {
        val input = table(List("a", "b"), Nil)
        assertTrue(Csv.toCsv(input) == "a,b\n")
      }
    )
  )
}
