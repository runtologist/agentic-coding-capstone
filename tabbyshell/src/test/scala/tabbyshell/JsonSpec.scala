package tabbyshell

import zio.test.*

object JsonSpec extends ZIOSpecDefault {
  import Value.*

  override def spec = suite("Json")(
    suite("parse")(
      test("parses scalar values") {
        assertTrue(
          Json.parse("null") == Right(VNull),
          Json.parse("true") == Right(VBool(true)),
          Json.parse("false") == Right(VBool(false)),
          Json.parse("42") == Right(VInt(42L)),
          Json.parse("-7") == Right(VInt(-7L)),
          Json.parse("3.25") == Right(VFloat(3.25)),
          Json.parse("1e3") == Right(VFloat(1000.0)),
          Json.parse("9007199254740993") == Right(VInt(9007199254740993L)),
          Json.parse("\"hi\"") == Right(VStr("hi"))
        )
      },
      test("parses string escapes") {
        assertTrue(
          Json.parse("\"a\\nb\"") == Right(VStr("a\nb")),
          Json.parse("\"a\\tb\"") == Right(VStr("a\tb")),
          Json.parse("\"a\\\\b\"") == Right(VStr("a\\b")),
          Json.parse("\"a\\\"b\"") == Right(VStr("a\"b"))
        )
      },
      test("parses arrays and objects") {
        assertTrue(
          Json.parse("[]") == Right(VList(Nil)),
          Json.parse("[1, \"x\"]") == Right(VList(List(VInt(1L), VStr("x")))),
          Json.parse("{}") == Right(Value.recordTrusted(Nil))
        )
      },
      test("duplicate object keys keep the last value in the first position") {
        Json.parse("{\"a\":1,\"a\":2}") match {
          case Right(VRecord(fields)) => assertTrue(fields == List("a" -> VInt(2L)))
          case _                      => assertTrue(false)
        }
      },
      test("uniform object arrays become tables") {
        Json.parse("[{\"a\":1},{\"a\":2}]") match {
          case Right(VTable(columns, rows)) =>
            assertTrue(
              columns == List("a"),
              rows == List(List(VInt(1L)), List(VInt(2L)))
            )
          case _ => assertTrue(false)
        }
      },
      test("non-uniform object arrays stay lists of records") {
        Json.parse("[{\"a\":1},{\"b\":2}]") match {
          case Right(VList(items)) =>
            assertTrue(
              items == List(
                Value.recordTrusted(List("a" -> VInt(1L))),
                Value.recordTrusted(List("b" -> VInt(2L)))
              )
            )
          case _ => assertTrue(false)
        }
      },
      test("rejects invalid JSON") {
        assertTrue(
          Json.parse("{").isLeft,
          Json.parse("[1,").isLeft,
          Json.parse("tru").isLeft,
          Json.parse("{\"a\":1} extra").isLeft,
          Json.parse("").isLeft
        )
      }
    ),
    suite("pretty")(
      test("pretty-prints a table as an array of objects") {
        val input = Value.table(List("a"), List(List(VInt(1L)))).toOption.get
        assertTrue(Json.pretty(input) == "[\n  {\n    \"a\": 1\n  }\n]\n")
      },
      test("pretty-prints a record as an object") {
        val input = Value.record(List("a" -> VInt(1L), "b" -> VStr("x"))).toOption.get
        assertTrue(Json.pretty(input) == "{\n  \"a\": 1,\n  \"b\": \"x\"\n}\n")
      },
      test("round-trips a table through pretty and parse") {
        val input = Value
          .table(
            List("name", "age"),
            List(List(VStr("Alice"), VInt(30L)), List(VStr("Bob"), VInt(25L)))
          )
          .toOption
          .get
        assertTrue(Json.parse(Json.pretty(input)) == Right(input))
      },
      test("pretty-prints scalars, empty containers and floats") {
        assertTrue(
          Json.pretty(VNull) == "null\n",
          Json.pretty(VList(Nil)) == "[]\n",
          Json.pretty(Value.recordTrusted(Nil)) == "{}\n",
          Json.pretty(VFloat(2.0)) == "2\n",
          Json.pretty(VFloat(1.5)) == "1.5\n"
        )
      }
    ),
    suite("quote")(
      test("escapes quotes, backslashes and newlines") {
        assertTrue(
          Json.quote("plain") == "\"plain\"",
          Json.quote("a\"b\\c\nd") == "\"a\\\"b\\\\c\\nd\""
        )
      },
      test("escapes control characters as unicode escapes") {
        val expected = "\"" + "\\" + "u0001" + "\""
        assertTrue(Json.quote(1.toChar.toString) == expected)
      }
    )
  )
}
