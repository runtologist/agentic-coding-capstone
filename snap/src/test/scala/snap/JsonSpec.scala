package snap

import zio.test.*

object JsonSpec extends ZIOSpecDefault {

  // Golden bytes for the served repository snapshot (test 12 body_text_equals).
  private val test12Golden: String = Vector(
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

  // Compact valid repository used across tests (one text change inserting "one\n").
  private val compactRepo =
    """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"one","changes":[{"type":"text","path":"file.txt","edit":[{"insert":["one\n"]}]}]}]}"""

  private def leftDetail[A](e: Either[SnapError, A]): Option[String] =
    e.left.toOption.map(_.detail)

  private def parseOk(input: String): Model.Repository =
    Json.parseRepository(input).toOption.get

  def spec = suite("Json thin zio-json codecs")(
    suite("parseRepository: valid documents & golden round-trip")(
      test("parses the compact test-12 repository into typed Model values") {
        val r = Json.parseRepository(compactRepo)
        assertTrue(
          r.isRight,
          r.toOption.get.frontier.components.map { case (id, rev) => (id.value, rev) } ==
            Vector(("a@x", 1L)),
          r.toOption.get.patches.length == 1,
          r.toOption.get.patches.head.author.value == "a@x",
          r.toOption.get.patches.head.revision == 1L,
          r.toOption.get.patches.head.base.isEmpty,
          r.toOption.get.patches.head.message == "one",
          r.toOption.get.patches.head.changes == Vector(
            Model.Change.Text("file.txt", Vector(Model.EditOp.Insert(Vector("one\n"))))
          )
        )
      },
      test("writeRepository reproduces the test-12 snapshot bytes exactly") {
        val repo = parseOk(compactRepo)
        assertTrue(Json.writeRepository(repo) == test12Golden)
      },
      test("parse(write(repo)) round-trips to a structurally equal repository") {
        val repo = parseOk(compactRepo)
        assertTrue(Json.parseRepository(Json.writeRepository(repo)) == Right(repo))
      },
      test("accepts pretty whitespace and shuffled object key order (typed-value identity)") {
        val shuffled =
          """{ "patches": [ { "changes": [{"edit": [{"insert": ["one\n"]}], "path": "file.txt", "type": "text"}], "message": "one", "base": [], "revision": 1, "author": "a@x" } ], "frontier": [["a@x", 1]], "format": 1 }"""
        val a = parseOk(compactRepo)
        val b = parseOk(shuffled)
        assertTrue(
          a.frontier == b.frontier,
          a.patches.length == b.patches.length,
          Model.Patch.sameValue(a.patches.head, b.patches.head)
        )
      },
      test("accepts trailing whitespace after the top-level value") {
        assertTrue(Json.parseRepository(compactRepo + " \n\t ").isRight)
      },
      test("parses put change with canonical base64 and delete change") {
        val putRepo =
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"put","path":"img.bin","content":"AAEC"}]}]}"""
        val delRepo =
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"delete","path":"gone.txt"}]}]}"""
        val p = Json.parseRepository(putRepo).toOption.get.patches.head.changes.head
        val d = Json.parseRepository(delRepo).toOption.get.patches.head.changes.head
        assertTrue(
          p match {
            case Model.Change.Put("img.bin", bytes) => bytes.sameElements(Array[Byte](0, 1, 2))
            case _                                  => false
          },
          d == Model.Change.Del("gone.txt")
        )
      }
    ),
    suite("parseRepository: malformed JSON")(
      test("rejects malformed documents with InvalidJson") {
        assertTrue(
          Json.parseRepository("").isLeft,
          Json.parseRepository("{").isLeft,
          Json.parseRepository("[1,2").isLeft,
          Json.parseRepository("tru").isLeft,
          Json.parseRepository("""{"a":}""").isLeft,
          Json.parseRepository("[1,2,]").isLeft,
          Json.parseRepository("{,}").isLeft,
          Json.parseRepository("\"\\x\"").isLeft,
          Json.parseRepository("not json").isLeft
        )
      },
      test("malformed errors carry the 'invalid JSON' substring") {
        assertTrue(
          leftDetail(Json.parseRepository("{")).exists(_.contains("invalid JSON")),
          leftDetail(Json.parseRepository("not json")).exists(_.contains("invalid JSON"))
        )
      },
      test("rejects non-object top-level repository") {
        assertTrue(
          Json.parseRepository("[1,2]").isLeft,
          Json.parseRepository("\"hello\"").isLeft
        )
      }
    ),
    suite("strict trailing content")(
      test("rejects trailing non-whitespace after the repository value") {
        assertTrue(
          Json.parseRepository(compactRepo + "}").isLeft,
          Json.parseRepository(compactRepo + " extra").isLeft,
          Json.parseRepository(compactRepo + """{"format":1}""").isLeft,
          leftDetail(Json.parseRepository(compactRepo + " x")).exists(_.contains("invalid JSON"))
        )
      }
    ),
    suite("duplicate object keys")(
      test("rejects duplicate root key with the offending key name") {
        val r = Json.parseRepository("""{"format":1,"format":1,"frontier":[],"patches":[]}""")
        assertTrue(r == Left(SnapError.DuplicateJsonKey("format")))
      },
      test("rejects duplicate keys inside a patch") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[],"patches":[{"author":"a@x","author":"b@x","revision":1,"base":[],"message":"m","changes":[{"type":"delete","path":"f"}]}]}"""
        )
        assertTrue(r == Left(SnapError.DuplicateJsonKey("author")))
      },
      test("rejects duplicate keys inside a change") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"delete","path":"f","path":"g"}]}]}"""
        )
        assertTrue(r == Left(SnapError.DuplicateJsonKey("path")))
      },
      test("rejects duplicate keys inside an edit op") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"text","path":"f","edit":[{"retain":1,"retain":2}]}]}]}"""
        )
        assertTrue(r == Left(SnapError.DuplicateJsonKey("retain")))
      },
      test("allows identical keys in sibling objects") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1],["b@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"delete","path":"x"}]},{"author":"b@x","revision":1,"base":[["a@x",1]],"message":"m2","changes":[{"type":"delete","path":"y"}]}]}"""
        )
        assertTrue(r.isRight)
      }
    ),
    suite("unknown fields")(
      test("rejects unknown repository field with exact pinned message") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[],"patches":[],"unknown":true}"""
        )
        assertTrue(
          r == Left(SnapError.UnknownRepoField("unknown")),
          leftDetail(r).contains("repository has unknown field: unknown")
        )
      },
      test("rejects unknown patch field") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"delete","path":"f"}],"extra":1}]}"""
        )
        assertTrue(r.isLeft, leftDetail(r).exists(_.contains("unknown field: extra")))
      },
      test("rejects unknown change field with pinned substring") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"put","path":"f","content":"YQ==","extra":1}]}]}"""
        )
        assertTrue(r.isLeft, leftDetail(r).exists(_.endsWith("unknown field: extra")))
      },
      test("rejects missing required fields") {
        assertTrue(
          Json.parseRepository("""{"frontier":[],"patches":[]}""").isLeft,
          Json.parseRepository("""{"format":1,"patches":[]}""").isLeft,
          Json.parseRepository("""{"format":1,"frontier":[]}""").isLeft,
          Json
            .parseRepository(
              """{"format":1,"frontier":[],"patches":[{"revision":1,"base":[],"message":"m","changes":[{"type":"delete","path":"f"}]}]}"""
            )
            .isLeft
        )
      },
      test("rejects wrong format value and wrong format type") {
        assertTrue(
          Json.parseRepository("""{"format":2,"frontier":[],"patches":[]}""").isLeft,
          Json.parseRepository("""{"format":"1","frontier":[],"patches":[]}""").isLeft
        )
      }
    ),
    suite("integer literal strictness")(
      test("rejects fractional revision with positive safe integer error") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1.5,"base":[],"message":"m","changes":[{"type":"delete","path":"f"}]}]}"""
        )
        assertTrue(r.isLeft, leftDetail(r).exists(_.endsWith("positive safe integer")))
      },
      test("rejects zero and negative and over-max revisions") {
        def repoWith(rev: String) =
          s"""{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":$rev,"base":[],"message":"m","changes":[{"type":"delete","path":"f"}]}]}"""
        assertTrue(
          Json.parseRepository(repoWith("0")).isLeft,
          Json.parseRepository(repoWith("-1")).isLeft,
          Json.parseRepository(repoWith("9007199254740992")).isLeft
        )
      },
      test("accepts 1 and the max safe integer") {
        def repoWith(rev: String) =
          s"""{"format":1,"frontier":[["a@x",$rev]],"patches":[{"author":"a@x","revision":$rev,"base":[],"message":"m","changes":[{"type":"delete","path":"f"}]}]}"""
        assertTrue(
          Json.parseRepository(repoWith("1")).isRight,
          Json.parseRepository(repoWith("9007199254740991")).isRight
        )
      },
      test("rejects zero retain count and string-typed count") {
        assertTrue(
          Json
            .parseRepository(
              """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"text","path":"f","edit":[{"retain":0}]}]}]}"""
            )
            .left
            .toOption
            .exists(_.detail.endsWith("positive safe integer")),
          Json
            .parseRepository(
              """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"text","path":"f","edit":[{"retain":"1"}]}]}]}"""
            )
            .isLeft
        )
      }
    ),
    suite("base64 canonicality")(
      test("rejects unpadded, non-alphabet, and non-canonical trailing-bit content") {
        def putRepo(content: String) =
          s"""{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"put","path":"f","content":"$content"}]}]}"""
        assertTrue(
          leftDetail(Json.parseRepository(putRepo("abc"))).exists(_.contains("canonical base64")),
          leftDetail(Json.parseRepository(putRepo("YQ"))).exists(_.contains("canonical base64")),
          leftDetail(Json.parseRepository(putRepo("!!=="))).exists(_.contains("canonical base64")),
          leftDetail(Json.parseRepository(putRepo("AB=="))).exists(_.contains("canonical base64"))
        )
      },
      test("accepts canonical padded base64 and round-trips bytes") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"put","path":"f","content":"YQBi"}]}]}"""
        )
        val bytes = r.toOption.get.patches.head.changes.head
          .asInstanceOf[Model.Change.Put]
          .bytes
        assertTrue(new String(bytes, "UTF-8") == "a\u0000b")
      }
    ),
    suite("edit op shape")(
      test("rejects an edit op with two operation keys") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"text","path":"f","edit":[{"retain":1,"delete":1}]}]}]}"""
        )
        assertTrue(r.isLeft, leftDetail(r).exists(_.contains("must have one operation")))
      },
      test("rejects an edit op with an unknown operation key") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"text","path":"f","edit":[{"foo":1}]}]}]}"""
        )
        assertTrue(r.isLeft, leftDetail(r).exists(_.contains("must have one operation")))
      },
      test("rejects an empty insert array") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"text","path":"f","edit":[{"insert":[]}]}]}]}"""
        )
        assertTrue(r.isLeft, leftDetail(r).exists(_.endsWith("insert is empty")))
      },
      test("rejects non-canonical insert tokens") {
        // interior LF inside a token
        val interior = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"text","path":"f","edit":[{"insert":["a\nb"]}]}]}]}"""
        )
        // empty token
        val emptyTok = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"text","path":"f","edit":[{"insert":[""]}]}]}]}"""
        )
        assertTrue(interior.isLeft, emptyTok.isLeft)
      },
      test("accepts an empty edit script (empty file creation)") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"text","path":"f","edit":[]}]}]}"""
        )
        assertTrue(
          r.isRight,
          r.toOption.get.patches.head.changes.head == Model.Change.Text("f", Vector.empty)
        )
      }
    ),
    suite("message and path validation")(
      test("rejects empty patch message") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"","changes":[{"type":"delete","path":"f"}]}]}"""
        )
        assertTrue(r.isLeft, leftDetail(r).exists(_.endsWith("message is empty")))
      },
      test("rejects forbidden control character in message") {
        val r = Json.parseRepository(
          "{\"format\":1,\"frontier\":[[\"a@x\",1]],\"patches\":[{\"author\":\"a@x\",\"revision\":1,\"base\":[],\"message\":\"bad" + 1.toChar + "msg\",\"changes\":[{\"type\":\"delete\",\"path\":\"f\"}]}]}"
        )
        assertTrue(r.isLeft)
      },
      test("rejects .snap path, backslash, control char, and bad segments") {
        def putRepo(path: String) =
          s"""{"format":1,"frontier":[["a@x",1]],"patches":[{"author":"a@x","revision":1,"base":[],"message":"m","changes":[{"type":"put","path":"$path","content":"YQ=="}]}]}"""
        assertTrue(
          leftDetail(Json.parseRepository(putRepo(".snap/secret")))
            .exists(_.contains("path is invalid")),
          Json.parseRepository(putRepo("a\\\\b")).isLeft,
          Json.parseRepository(putRepo("a//b")).isLeft,
          Json.parseRepository(putRepo("./a")).isLeft,
          Json.parseRepository(putRepo("")).isLeft
        )
      }
    ),
    suite("version vectors")(
      test("rejects non-canonical frontier ordering") {
        val r =
          Json.parseRepository("""{"format":1,"frontier":[["b@x",1],["a@x",1]],"patches":[]}""")
        assertTrue(r.isLeft, leftDetail(r).exists(_.contains("canonical")))
      },
      test("rejects duplicate contributor in frontier") {
        val r =
          Json.parseRepository("""{"format":1,"frontier":[["a@x",1],["a@x",2]],"patches":[]}""")
        assertTrue(r.isLeft)
      },
      test("rejects invalid contributor id in frontier") {
        val r = Json.parseRepository("""{"format":1,"frontier":[["not-an-id",1]],"patches":[]}""")
        assertTrue(r.isLeft, leftDetail(r).exists(_.contains("invalid contributor id")))
      },
      test("rejects malformed frontier pair shape") {
        assertTrue(
          Json.parseRepository("""{"format":1,"frontier":[["a@x"]],"patches":[]}""").isLeft,
          Json.parseRepository("""{"format":1,"frontier":[[1,2]],"patches":[]}""").isLeft,
          Json.parseRepository("""{"format":1,"frontier":["a@x"],"patches":[]}""").isLeft
        )
      },
      test("rejects non-canonical base ordering") {
        val r = Json.parseRepository(
          """{"format":1,"frontier":[["a@x",1],["b@x",1]],"patches":[{"author":"a@x","revision":1,"base":[["b@x",1],["a@x",1]],"message":"m","changes":[{"type":"delete","path":"f"}]}]}"""
        )
        assertTrue(r.isLeft)
      }
    ),
    suite("parseConfig")(
      test("parses a valid config and returns the contributor id") {
        val r = Json.parseConfig("""{"contributor":{"id":"alice@example.com"}}""")
        assertTrue(r.isRight, r.toOption.get.id.value == "alice@example.com")
      },
      test("tolerates trailing bytes after the first complete value (test 03)") {
        val r = Json.parseConfig("""{"contributor":{"id":"global@example.com"}}}}""")
        assertTrue(r.isRight, r.toOption.get.id.value == "global@example.com")
      },
      test("rejects unparseable config with invalid JSON") {
        val r = Json.parseConfig("not json")
        assertTrue(r.isLeft, leftDetail(r).exists(_.contains("invalid JSON")))
      },
      test("rejects duplicate config keys") {
        val r = Json.parseConfig("""{"contributor":{"id":"a@x","id":"b@x"}}""")
        assertTrue(r == Left(SnapError.DuplicateJsonKey("id")))
      },
      test("rejects invalid contributor id") {
        val r = Json.parseConfig("""{"contributor":{"id":"not-an-id"}}""")
        assertTrue(r.isLeft, leftDetail(r).exists(_.contains("invalid contributor id")))
      },
      test("rejects unknown config fields and bad shape") {
        assertTrue(
          Json.parseConfig("""{"contributor":{"id":"a@x"},"unknown":true}""").isLeft,
          Json.parseConfig("""{"contributor":"a@x"}""").isLeft,
          Json.parseConfig("""{}""").isLeft,
          Json.parseConfig("""{"contributor":{}}""").isLeft
        )
      },
      test("writeConfig emits canonical two-space JSON with trailing LF") {
        val cfg = Json.ConfigFile(Model.ContributorId.parse("a@x").toOption.get)
        assertTrue(
          Json.writeConfig(cfg) == "{\n  \"contributor\": {\n    \"id\": \"a@x\"\n  }\n}\n"
        )
      }
    )
  )
}
