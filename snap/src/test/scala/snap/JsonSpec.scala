package snap

import zio.test.*

import snap.Json.*

object JsonSpec extends ZIOSpecDefault {

  private def leftDetail[A](e: Either[SnapError, A]): Option[String] =
    e.left.toOption.map(_.detail)

  def spec = suite("Json")(
    suite("parse: values")(
      test("parses scalars") {
        assertTrue(
          Json.parse("null", "t") == Right(JNull),
          Json.parse("true", "t") == Right(JBool(true)),
          Json.parse("false", "t") == Right(JBool(false)),
          Json.parse("123", "t") == Right(JNum("123")),
          Json.parse("-7", "t") == Right(JNum("-7")),
          Json.parse("1.5", "t") == Right(JNum("1.5")),
          Json.parse("1e2", "t") == Right(JNum("1e2")),
          Json.parse("\"hi\"", "t") == Right(JStr("hi"))
        )
      },
      test("keeps number literals raw so integer-ness is decidable later") {
        val v = Json.parse("""{"a":1,"b":1.5,"c":1e2}""", "t").toOption.get.asInstanceOf[JObj]
        assertTrue(
          v.get("a").contains(JNum("1")),
          v.get("a").get.asInstanceOf[JNum].isIntegralLiteral,
          !v.get("b").get.asInstanceOf[JNum].isIntegralLiteral,
          !v.get("c").get.asInstanceOf[JNum].isIntegralLiteral,
          v.get("a").get.asInstanceOf[JNum].asLong.contains(1L),
          v.get("b").get.asInstanceOf[JNum].asLong.isEmpty
        )
      },
      test("parses nested objects and arrays preserving key order") {
        val v = Json.parse("""{"b":[1,{"x":true}],"a":"s"}""", "t").toOption.get.asInstanceOf[JObj]
        assertTrue(
          v.keys == Vector("b", "a"),
          v.get("b").contains(JArr(Vector(JNum("1"), JObj(Vector(("x", JBool(true)))))))
        )
      },
      test("parses string escapes") {
        val jsonText = "\"a\\nb\\t\\\"c\\\"\\\\\\/\\b\\f\\ré\""
        assertTrue(
          Json.parse(jsonText, "t") == Right(JStr("a\nb\t\"c\"\\/\b\f\ré"))
        )
      },
      test("parses unicode escapes") {
        assertTrue(Json.parse("\"\\u00e9\"", "t") == Right(JStr("é")))
      },
      test("rejects unescaped control characters in strings") {
        assertTrue(Json.parse("\"a\u0001b\"", "t").isLeft)
      },
      test("rejects invalid escapes") {
        assertTrue(Json.parse("\"\\x\"", "t").isLeft)
      },
      test("rejects malformed documents") {
        assertTrue(
          Json.parse("", "t").isLeft,
          Json.parse("{", "t").isLeft,
          Json.parse("[1,2", "t").isLeft,
          Json.parse("tru", "t").isLeft,
          Json.parse("""{"a":}""", "t").isLeft,
          Json.parse("[1,2,]", "t").isLeft,
          Json.parse("{,}", "t").isLeft
        )
      },
      test("rejects leading-zero and malformed numbers") {
        assertTrue(
          Json.parse("01", "t").isLeft,
          Json.parse("1.", "t").isLeft,
          Json.parse("-", "t").isLeft,
          Json.parse("1e", "t").isLeft
        )
      },
      test("malformed errors carry the source label and invalid JSON substring") {
        assertTrue(
          leftDetail(Json.parse("{", "repo.json")).exists(d =>
            d.startsWith("invalid JSON: repo.json:")
          )
        )
      }
    ),
    suite("parse: duplicate keys")(
      test("rejects duplicate object keys at top level") {
        assertTrue(
          leftDetail(Json.parse("""{"format":1,"format":1}""", "t"))
            .contains("duplicate JSON key format")
        )
      },
      test("rejects duplicate keys at nested depth") {
        assertTrue(
          leftDetail(Json.parse("""{"a":{"x":1,"x":2}}""", "t"))
            .exists(_.contains("duplicate JSON key x"))
        )
      }
    ),
    suite("parse: trailing content")(
      test("strict mode rejects trailing content after the first value") {
        assertTrue(
          leftDetail(Json.parse("""{"a":1}}}""", "t", allowTrailing = false))
            .exists(_.contains("trailing content"))
        )
      },
      test("lenient mode accepts trailing garbage after a complete value (config, test 03)") {
        val r = Json.parse(
          """{"contributor":{"id":"global@example.com"}}}}""",
          "cfg",
          allowTrailing = true
        )
        assertTrue(
          r.map {
            case JObj(es) => es.head._1
            case _        => "?"
          } == Right("contributor")
        )
      },
      test("lenient mode still rejects unparseable leading content") {
        assertTrue(Json.parse("not json", "cfg", allowTrailing = true).isLeft)
      }
    ),
    suite("renderPretty")(
      test("empty containers render inline") {
        assertTrue(
          Json.renderPretty(JObj(Vector.empty)) == "{}\n",
          Json.renderPretty(JArr(Vector.empty)) == "[]\n"
        )
      },
      test("string escaping matches JSON.stringify") {
        assertTrue(Json.renderPretty(JStr("a\nb")) == "\"a\\nb\"\n")
      },
      test("escapes control characters as lowercase \\u00xx") {
        val s = "x" + 1.toChar + "y"
        assertTrue(Json.renderPretty(JStr(s)) == "\"x\\u0001y\"\n")
      },
      test("renders the test-12 served-snapshot bytes exactly") {
        val input =
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"one","changes":[{"type":"text","path":"file.txt","edit":[{"insert":["one\n"]}]}]}]}"""
        val v = Json.parse(input, "t").toOption.get
        val expected =
          "{\n" +
            "  \"format\": 1,\n" +
            "  \"frontier\": [\n" +
            "    [\n" +
            "      \"a@x\",\n" +
            "      1\n" +
            "    ]\n" +
            "  ],\n" +
            "  \"patches\": [\n" +
            "    {\n" +
            "      \"author\": \"a@x\",\n" +
            "      \"revision\": 1,\n" +
            "      \"base\": [],\n" +
            "      \"message\": \"one\",\n" +
            "      \"changes\": [\n" +
            "        {\n" +
            "          \"type\": \"text\",\n" +
            "          \"path\": \"file.txt\",\n" +
            "          \"edit\": [\n" +
            "            {\n" +
            "              \"insert\": [\n" +
            "                \"one\\n\"\n" +
            "              ]\n" +
            "            }\n" +
            "          ]\n" +
            "        }\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}\n"
        assertTrue(Json.renderPretty(v) == expected)
      },
      test("round-trips parsed values") {
        val input = """{"a":[1,"x",true,null,{"b":[]}],"c":"héllo"}"""
        val v = Json.parse(input, "t").toOption.get
        assertTrue(Json.parse(Json.renderPretty(v), "rt") == Right(v))
      }
    )
  )
}
