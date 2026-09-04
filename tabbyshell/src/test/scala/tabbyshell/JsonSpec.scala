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
      },
      test("escapes empty strings and remaining named controls") {
        val named = "\"" + "\\" + "b" + "\\" + "f" + "\\" + "r" + "\""
        val nul = "\"" + "\\" + "u0000" + "\""
        assertTrue(
          Json.quote("") == "\"\"",
          Json.quote("\b\f\r") == named,
          Json.quote(0.toChar.toString) == nul
        )
      }
    ),
    suite("parse whitespace and nesting")(
      test("accepts whitespace and newlines around tokens") {
        val text = " \t\r\n { \"a\" : [ 1 , 2 ] } \n "
        val expected = Value.recordTrusted(List("a" -> VList(List(VInt(1L), VInt(2L)))))
        assertTrue(Json.parse(text) == Right(expected))
      },
      test("parses nested arrays and objects") {
        val text = "{\"a\":{\"b\":[1,{\"c\":null}]}}"
        val expected = Value.recordTrusted(
          List(
            "a" -> Value.recordTrusted(
              List(
                "b" -> VList(
                  List(VInt(1L), Value.recordTrusted(List("c" -> VNull)))
                )
              )
            )
          )
        )
        assertTrue(Json.parse(text) == Right(expected))
      },
      test("arrays of empty objects become empty-column tables") {
        assertTrue(
          Json.parse("[{}, {}]") == Right(Value.tableTrusted(Nil, List(Nil, Nil)))
        )
      }
    ),
    suite("parse errors")(
      test("reports exact positions for structural errors") {
        assertTrue(
          Json.parse(" ") == Left("unexpected end of JSON at position 1"),
          Json.parse("[") == Left("unexpected end of JSON at position 1"),
          Json.parse("[1,") == Left("unexpected end of JSON at position 3"),
          Json.parse("tru") == Left("expected 'true' at position 0"),
          Json.parse("fals") == Left("expected 'false' at position 0"),
          Json.parse("nul") == Left("expected 'null' at position 0"),
          Json.parse("truex") == Left("unexpected trailing characters at position 4"),
          Json.parse("{1:2}") == Left("expected object key string at position 1"),
          Json.parse("{\"a\" 1}") == Left("expected ':' at position 5"),
          Json.parse("{\"a\":1 \"b\":2}") == Left("expected ',' or '}' in object at position 7"),
          Json.parse("[1 2]") == Left("expected ',' or ']' in array at position 3"),
          Json.parse(".5") == Left("unexpected character '.' at position 0")
        )
      },
      test("reports string and number errors") {
        val unterminatedEscape = "\"" + "\\"
        val invalidEscape = "\"" + "\\" + "x" + "\""
        assertTrue(
          Json.parse("-") == Left("expected digit at position 1"),
          Json.parse("1.") == Left("expected digit at position 2"),
          Json.parse("1e") == Left("expected digit at position 2"),
          Json.parse("\"abc") == Left("unterminated string at position 4"),
          Json.parse(unterminatedEscape) == Left("unterminated escape at position 2"),
          Json.parse(invalidEscape) == Left("invalid escape character 'x' at position 2")
        )
      },
      test("reports invalid unicode escapes") {
        val badHex = "\"" + "\\" + "u12G4" + "\""
        val truncated = "\"" + "\\" + "u12" + "\""
        assertTrue(
          Json.parse(badHex) == Left("invalid unicode escape '12G4' at position 2"),
          Json.parse(truncated) == Left("invalid unicode escape at position 2")
        )
      }
    ),
    suite("parse numbers")(
      test("parses integer and float forms") {
        assertTrue(
          Json.parse("-0") == Right(VInt(0L)),
          Json.parse("01") == Right(VInt(1L)),
          Json.parse("-3.25") == Right(VFloat(-3.25)),
          Json.parse("1E+2") == Right(VFloat(100.0)),
          Json.parse("1.5e-2") == Right(VFloat(0.015))
        )
      },
      test("falls back to float for integers outside Long range") {
        Json.parse("9223372036854775808") match {
          case Right(VFloat(d)) => assertTrue(d > 9.2e18)
          case _                => assertTrue(false)
        }
      },
      test("overflowing exponents become infinities") {
        assertTrue(
          Json.parse("1e309") == Right(VFloat(Double.PositiveInfinity)),
          Json.parse("-1e309") == Right(VFloat(Double.NegativeInfinity))
        )
      }
    ),
    suite("pretty extras")(
      test("renders non-finite floats as null and keeps special scalars raw") {
        assertTrue(
          Json.pretty(VFloat(Double.NaN)) == "null\n",
          Json.pretty(VFloat(Double.PositiveInfinity)) == "null\n",
          Json.pretty(VFloat(Double.NegativeInfinity)) == "null\n",
          Json.pretty(VFloat(-0.0)) == "0\n",
          Json.pretty(VFloat(9007199254740991.0)) == "9007199254740991\n",
          Json.pretty(VFilesize(1500L)) == "1500\n",
          Json.pretty(VDate(1735689600L)) == "1735689600\n"
        )
      },
      test("pretty-prints nested empty lists") {
        val input = VList(List(VList(Nil), VList(List(VNull))))
        assertTrue(Json.pretty(input) == "[\n  [],\n  [\n    null\n  ]\n]\n")
      },
      test("round-trips escaped strings and control characters") {
        val s = "a\n\"b\"\t" + 1.toChar
        Json.parse(Json.pretty(VStr(s))) match {
          case Right(VStr(parsed)) => assertTrue(parsed == s)
          case _                   => assertTrue(false)
        }
      }
    )
  )
}
