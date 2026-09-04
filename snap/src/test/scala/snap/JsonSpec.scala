package snap

import zio.test.*
import zio.json.ast.{Json => ZJson}

import java.math.{BigDecimal => JBigDecimal}

object JsonSpec extends ZIOSpecDefault {

  private def leftDetail[A](e: Either[SnapError, A]): Option[String] =
    e.left.toOption.map(_.detail)

  private def parseOk(input: String): Json.Value =
    Json.parseStrict(input, "t").toOption.get

  def spec = suite("Json (zio-json backed)")(
    suite("parseStrict: scalars and values")(
      test("parses null, booleans, and strings") {
        assertTrue(
          Json.parseStrict("null", "t") == Right(Json.nul),
          Json.parseStrict("true", "t") == Right(Json.bool(true)),
          Json.parseStrict("false", "t") == Right(Json.bool(false)),
          Json.parseStrict("\"hi\"", "t") == Right(Json.str("hi"))
        )
      },
      test("parses numbers into BigDecimal preserving value and scale") {
        val v = Json
          .asObject(parseOk("""{"a":1,"b":1.5,"c":1e2,"big":9007199254740991}"""), "root")
          .toOption
          .get
        val a = Json.asNumber(v.get("a").get, "a").toOption.get
        val b = Json.asNumber(v.get("b").get, "b").toOption.get
        val c = Json.asNumber(v.get("c").get, "c").toOption.get
        val big = Json.asNumber(v.get("big").get, "big").toOption.get
        assertTrue(
          a.compareTo(new JBigDecimal("1")) == 0,
          a.scale == 0,
          b.compareTo(new JBigDecimal("1.5")) == 0,
          c.compareTo(new JBigDecimal("100")) == 0,
          big.longValueExact == 9007199254740991L
        )
      },
      test("preserves object key order") {
        val v = Json.asObject(parseOk("""{"b":1,"a":2}"""), "root").toOption.get
        assertTrue(v.fields.map(_._1).toList == List("b", "a"))
      },
      test("parses string escapes") {
        val jsonText = "\"a\\nb\\t\\\"c\\\"\\\\\\/\\b\\f\\ré\""
        assertTrue(Json.parseStrict(jsonText, "t") == Right(Json.str("a\nb\t\"c\"\\/\b\f\ré")))
      },
      test("parses unicode escapes") {
        assertTrue(Json.parseStrict("\"\\u00e9\"", "t") == Right(Json.str("é")))
      },
      test("rejects unescaped control characters in strings") {
        assertTrue(Json.parseStrict("\"a\u0001b\"", "t").isLeft)
      },
      test("rejects malformed documents") {
        assertTrue(
          Json.parseStrict("", "t").isLeft,
          Json.parseStrict("{", "t").isLeft,
          Json.parseStrict("[1,2", "t").isLeft,
          Json.parseStrict("tru", "t").isLeft,
          Json.parseStrict("""{"a":}""", "t").isLeft,
          Json.parseStrict("[1,2,]", "t").isLeft,
          Json.parseStrict("{,}", "t").isLeft,
          Json.parseStrict("\"\\x\"", "t").isLeft
        )
      },
      test("malformed errors carry the source label and 'invalid JSON' substring") {
        assertTrue(
          leftDetail(Json.parseStrict("{", "repo.json")).exists(d =>
            d.startsWith("invalid JSON: repo.json:")
          )
        )
      }
    ),
    suite("parseStrict: duplicate keys")(
      test("rejects duplicate object keys at top level with the key name") {
        val r = Json.parseStrict("""{"format":1,"format":1}""", "t")
        assertTrue(r == Left(SnapError.DuplicateJsonKey("format")))
      },
      test("rejects duplicate keys at nested depth") {
        val r = Json.parseStrict("""{"a":{"x":1,"x":2}}""", "t")
        assertTrue(r == Left(SnapError.DuplicateJsonKey("x")))
      },
      test("allows identical keys in sibling objects") {
        assertTrue(Json.parseStrict("""{"a":{"x":1},"b":{"x":2}}""", "t").isRight)
      }
    ),
    suite("trailing content")(
      test("strict mode rejects trailing non-whitespace after the first value") {
        assertTrue(
          Json.parseStrict("""{"a":1}}""", "t").isLeft,
          Json.parseStrict("""{"a":1} extra""", "t").isLeft,
          Json.parseStrict("""{"a":1}{"b":2}""", "t").isLeft,
          leftDetail(Json.parseStrict("""{"a":1} x""", "t")).exists(_.contains("invalid JSON"))
        )
      },
      test("strict mode allows trailing whitespace") {
        assertTrue(Json.parseStrict("{\"a\":1} \n\t ", "t").isRight)
      },
      test("config mode tolerates trailing bytes after the first complete value (test 03)") {
        val r = Json.parseConfig("""{"contributor":{"id":"global@example.com"}}}}""", "cfg")
        val ok = r.toOption
          .flatMap(j => Json.asObject(j, "cfg").toOption)
          .flatMap(o => o.get("contributor"))
          .isDefined
        assertTrue(r.isRight, ok)
      },
      test("config mode still rejects unparseable leading content") {
        assertTrue(Json.parseConfig("not json", "cfg").isLeft)
      },
      test("config mode still rejects duplicate keys") {
        val r = Json.parseConfig("""{"contributor":{"id":"a@x","id":"b@x"}}""", "cfg")
        assertTrue(r == Left(SnapError.DuplicateJsonKey("id")))
      }
    ),
    suite("typed extraction helpers")(
      test("asObject/asArray/asString/asBoolean succeed on matching types") {
        val v = parseOk("""{"a":[1,"x",true,null]}""")
        val obj = Json.asObject(v, "root").toOption.get
        assertTrue(
          Json.asArray(obj.get("a").get, "a").isRight,
          Json.asString(Json.arr(Json.str("x")).asInstanceOf[ZJson.Arr].elements.head, "x").isRight,
          Json.asBoolean(ZJson.Bool(true), "f") == Right(true),
          Json.asString(ZJson.Str("hi"), "m") == Right("hi")
        )
      },
      test("type mismatches produce InvalidJson with context") {
        val v = parseOk("""{"a":1}""")
        assertTrue(
          leftDetail(Json.asArray(v, "frontier")).exists(_.contains("frontier")),
          Json.asString(ZJson.Num(new JBigDecimal("1")), "message").isLeft,
          Json.asBoolean(ZJson.Null, "format").isLeft,
          Json.asObject(ZJson.Arr(zio.Chunk.empty), "repository").isLeft
        )
      },
      test("field reports missing fields and optionalField returns None") {
        val o = Json.asObject(parseOk("""{"a":1}"""), "root").toOption.get
        assertTrue(
          Json.field(o, "b", "repository").isLeft,
          Json.field(o, "a", "repository").isRight,
          Json.optionalField(o, "b").isEmpty,
          Json.optionalField(o, "a").isDefined
        )
      },
      test("unknownFields lists distinct unknown keys in first-seen order") {
        val dup = Json.parseStrict("""{"format":1,"unknown":true,"extra":2,"format":1}""", "r")
        // duplicate key makes strict parse fail, so use a unique-key object
        val o2 =
          Json.asObject(parseOk("""{"format":1,"unknown":true,"extra":2}"""), "r").toOption.get
        assertTrue(
          dup == Left(SnapError.DuplicateJsonKey("format")),
          Json.unknownFields(o2, Set("format")) == Vector("unknown", "extra")
        )
      },
      test("asNumber returns the raw BigDecimal and rejects non-numbers") {
        val n = Json.asNumber(parseOk("42"), "n").toOption.get
        assertTrue(
          n.compareTo(new JBigDecimal("42")) == 0,
          Json.asNumber(ZJson.Str("42"), "n").isLeft
        )
      }
    ),
    suite("writeCanonical")(
      test("empty containers render inline") {
        assertTrue(
          Json.writeCanonical(Json.obj()) == "{}\n",
          Json.writeCanonical(Json.arr()) == "[]\n"
        )
      },
      test("string escaping matches JSON.stringify") {
        assertTrue(Json.writeCanonical(Json.str("a\nb")) == "\"a\\nb\"\n")
      },
      test("escapes control characters as lowercase \\u00xx") {
        val s = "x" + 1.toChar + "y"
        assertTrue(Json.writeCanonical(Json.str(s)) == "\"x\\u0001y\"\n")
      },
      test("integer-valued numbers render without fraction or exponent") {
        assertTrue(
          Json.writeCanonical(Json.num(1L)) == "1\n",
          Json.writeCanonical(ZJson.Num(new JBigDecimal("1e2"))) == "100\n",
          Json.writeCanonical(ZJson.Num(new JBigDecimal("1.0"))) == "1\n",
          Json.writeCanonical(ZJson.Num(new JBigDecimal("0.0"))) == "0\n",
          Json.writeCanonical(ZJson.Num(new JBigDecimal("1.5"))) == "1.5\n"
        )
      },
      test("renders the test-12 served-snapshot bytes exactly") {
        val repo = Json.obj(
          "format" -> Json.num(1L),
          "frontier" -> Json.arr(Json.arr(Json.str("a@x"), Json.num(1L))),
          "patches" -> Json.arr(
            Json.obj(
              "author" -> Json.str("a@x"),
              "revision" -> Json.num(1L),
              "base" -> Json.arr(),
              "message" -> Json.str("one"),
              "changes" -> Json.arr(
                Json.obj(
                  "type" -> Json.str("text"),
                  "path" -> Json.str("file.txt"),
                  "edit" -> Json.arr(Json.obj("insert" -> Json.arr(Json.str("one\n"))))
                )
              )
            )
          )
        )
        val expected = Vector(
          "{",
          "  \"format\": 1,",
          "  \"frontier\": [",
          "    [",
          "      \"a@x\",",
          "      1",
          "    ]",
          "  ],",
          "  \"patches\": [",
          "    {",
          "      \"author\": \"a@x\",",
          "      \"revision\": 1,",
          "      \"base\": [],",
          "      \"message\": \"one\",",
          "      \"changes\": [",
          "        {",
          "          \"type\": \"text\",",
          "          \"path\": \"file.txt\",",
          "          \"edit\": [",
          "            {",
          "              \"insert\": [",
          "                \"one\\n\"",
          "              ]",
          "            }",
          "          ]",
          "        }",
          "      ]",
          "    }",
          "  ]",
          "}"
        ).mkString("\n") + "\n"
        assertTrue(Json.writeCanonical(repo) == expected)
      },
      test("round-trips parsed values") {
        val compact =
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"one","changes":[{"type":"text","path":"file.txt","edit":[{"insert":["one\n"]}]}]}]}"""
        val v = parseOk(compact)
        assertTrue(Json.parseStrict(Json.writeCanonical(v), "rt") == Right(v))
      }
    )
  )
}
