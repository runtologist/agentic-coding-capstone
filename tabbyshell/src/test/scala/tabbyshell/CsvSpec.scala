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
      }
    )
  )
}
