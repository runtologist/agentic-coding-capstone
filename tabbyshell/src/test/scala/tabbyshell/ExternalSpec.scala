package tabbyshell

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

object ExternalSpec extends ZIOSpecDefault {
  import Value.*

  private val esc = "\u001b"

  private def state(color: Boolean, cwd: String): ShellState =
    ShellState(cwd = cwd, prevCwd = None, home = cwd, now = 0L, color = color)

  private def tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("tabbyshell-external-spec")))(
      dir => ZIO.attemptBlocking(deleteRecursively(dir)).ignore
    )

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      val stream = Files.list(path)
      try stream.forEach(p => deleteRecursively(p))
      finally stream.close()
    }
    Files.deleteIfExists(path)
    ()
  }

  private def envFrom(map: Map[String, String])(key: String): UIO[Option[String]] =
    ZIO.succeed(map.get(key))

  private val noEnv: String => UIO[Option[String]] = envFrom(Map.empty)

  override def spec = suite("External")(
    suite("resolveUrl")(
      test("uses the OpenRouter default when no base URL is set") {
        assertTrue(
          External.resolveUrl(None) == "https://openrouter.ai/api/v1/chat/completions"
        )
      },
      test("keeps a base URL that already targets chat/completions") {
        assertTrue(
          External.resolveUrl(Some("https://example.com/chat/completions")) ==
            "https://example.com/chat/completions",
          External.resolveUrl(Some("https://example.com/v1/chat/completions")) ==
            "https://example.com/v1/chat/completions"
        )
      },
      test("appends chat/completions to a base ending in /v1") {
        assertTrue(
          External.resolveUrl(Some("https://example.com/v1")) ==
            "https://example.com/v1/chat/completions"
        )
      },
      test("appends the full API path to any other base") {
        assertTrue(
          External.resolveUrl(Some("https://example.com")) ==
            "https://example.com/api/v1/chat/completions"
        )
      },
      test("ignores a single trailing slash before routing") {
        assertTrue(
          External.resolveUrl(Some("https://example.com/")) ==
            "https://example.com/api/v1/chat/completions",
          External.resolveUrl(Some("https://example.com/v1/")) ==
            "https://example.com/v1/chat/completions",
          External.resolveUrl(Some("https://example.com/chat/completions/")) ==
            "https://example.com/chat/completions"
        )
      }
    ),
    suite("buildRequestBody")(
      test("produces valid JSON with the expected model and temperature") {
        val body = External.buildRequestBody("ls", List("-l"), "file.txt")
        Json.parse(body) match {
          case Right(parsed) =>
            assertTrue(
              External.getField(parsed, "model") == Some(VStr("google/gemini-2.5-flash-lite")),
              External.getField(parsed, "temperature") == Some(VInt(0L))
            )
          case Left(_) => assertTrue(false)
        }
      },
      test("includes the system prompt and the command line with stdout") {
        val body = External.buildRequestBody("ps", List("aux"), "PID CMD\n1 init")
        Json.parse(body) match {
          case Right(parsed) =>
            External.getField(parsed, "messages") match {
              case Some(VTable(List("role", "content"), List(systemRow, userRow))) =>
                (systemRow, userRow) match {
                  case (
                        List(VStr("system"), VStr(systemContent)),
                        List(VStr("user"), VStr(userContent))
                      ) =>
                    assertTrue(
                      systemContent.startsWith("You convert raw command output"),
                      systemContent.contains("\"kind\":\"table\""),
                      userContent == "command: ps aux\n\noutput:\nPID CMD\n1 init"
                    )
                  case _ => assertTrue(false)
                }
              case _ => assertTrue(false)
            }
          case Left(_) => assertTrue(false)
        }
      },
      test("quotes special characters so the body stays valid JSON") {
        val tricky = "she said \"hi\" \\ backslash\nnew\ttab"
        val body = External.buildRequestBody("echo", List("x"), tricky)
        Json.parse(body) match {
          case Right(parsed) =>
            External.getField(parsed, "messages") match {
              case Some(VTable(_, List(_, List(VStr("user"), VStr(userContent))))) =>
                assertTrue(userContent == s"command: echo x\n\noutput:\n$tricky")
              case _ => assertTrue(false)
            }
          case Left(_) => assertTrue(false)
        }
      }
    ),
    suite("parseAiResponse")(
      test("parses a valid string kind") {
        assertTrue(
          External.parseAiResponse("""{"kind":"string","value":"hello world"}""") ==
            Right(VStr("hello world"))
        )
      },
      test("parses a valid table kind") {
        val json =
          """{"kind":"table","columns":["name","age"],
            |"rows":[["Alice","30"],["Bob","25"]]}""".stripMargin.replace("\n", "")
        val expected = Value.tableTrusted(
          List("name", "age"),
          List(List(VStr("Alice"), VStr("30")), List(VStr("Bob"), VStr("25")))
        )
        assertTrue(External.parseAiResponse(json) == Right(expected))
      },
      test("parses a table with no rows") {
        val json = """{"kind":"table","columns":["a"],"rows":[]}"""
        assertTrue(External.parseAiResponse(json) == Right(Value.tableTrusted(List("a"), Nil)))
      },
      test("parses a fenced JSON response") {
        val body = "```json\n{\"kind\":\"string\",\"value\":\"ok\"}\n```"
        assertTrue(External.parseAiResponse(body) == Right(VStr("ok")))
      },
      test("rejects invalid JSON") {
        assertTrue(External.parseAiResponse("not json at all").isLeft)
      },
      test("rejects a missing kind") {
        assertTrue(External.parseAiResponse("""{"value":"x"}""") == Left("missing kind"))
      },
      test("rejects a non-string kind") {
        assertTrue(External.parseAiResponse("""{"kind":42}""") == Left("missing kind"))
      },
      test("rejects an unknown kind") {
        assertTrue(
          External.parseAiResponse("""{"kind":"magic"}""") == Left("unknown kind: magic")
        )
      },
      test("rejects a string kind with a missing value") {
        assertTrue(
          External.parseAiResponse("""{"kind":"string"}""") == Left("missing string value")
        )
      },
      test("rejects a table kind with missing columns") {
        assertTrue(
          External.parseAiResponse("""{"kind":"table","rows":[]}""") == Left("missing columns")
        )
      },
      test("rejects a table kind with missing rows") {
        assertTrue(
          External.parseAiResponse("""{"kind":"table","columns":["a"]}""") == Left("missing rows")
        )
      },
      test("rejects non-array columns") {
        val json = """{"kind":"table","columns":"a","rows":[]}"""
        assertTrue(External.parseAiResponse(json) == Left("columns must be an array"))
      },
      test("rejects non-string column entries") {
        val json = """{"kind":"table","columns":["a",1],"rows":[["x","y"]]}"""
        assertTrue(External.parseAiResponse(json) == Left("columns must be strings"))
      },
      test("rejects non-array rows") {
        val json = """{"kind":"table","columns":["a"],"rows":"x"}"""
        assertTrue(External.parseAiResponse(json) == Left("rows must be an array"))
      },
      test("rejects row entries that are not arrays") {
        val json = """{"kind":"table","columns":["a"],"rows":["x"]}"""
        assertTrue(External.parseAiResponse(json) == Left("rows must be arrays of strings"))
      },
      test("rejects non-string row cells") {
        val json = """{"kind":"table","columns":["a"],"rows":[[1]]}"""
        assertTrue(External.parseAiResponse(json) == Left("columns must be strings"))
      },
      test("rejects duplicate columns") {
        val json = """{"kind":"table","columns":["a","a"],"rows":[["x","y"]]}"""
        assertTrue(External.parseAiResponse(json) == Left("duplicate column: a"))
      },
      test("rejects ragged rows") {
        val json = """{"kind":"table","columns":["a","b"],"rows":[["x"]]}"""
        assertTrue(
          External.parseAiResponse(json) == Left("row 0 has 1 columns, expected 2")
        )
      }
    ),
    suite("stripFences")(
      test("trims text with no fences") {
        assertTrue(External.stripFences("  {\"a\":1}  ") == "{\"a\":1}")
      },
      test("strips opening and closing fence markers") {
        assertTrue(External.stripFences("```\nbody\n```") == "body")
      },
      test("strips fences with a language tag") {
        assertTrue(External.stripFences("```json\n{\"a\":1}\n```") == "{\"a\":1}")
      },
      test("keeps the body when the opening fence has no closing fence") {
        assertTrue(
          External.stripFences("```\nbody") == "body",
          External.stripFences("```body") == "body"
        )
      },
      test("returns empty for fence-only input") {
        assertTrue(
          External.stripFences("```") == "",
          External.stripFences("```\n```") == ""
        )
      },
      test("handles leading and trailing whitespace around fences") {
        assertTrue(External.stripFences("  ```\ncontent\n```  ") == "content")
      }
    ),
    suite("getField")(
      test("returns a field that is present in a record") {
        val record = Value.recordTrusted(List("name" -> VStr("Alice"), "age" -> VInt(30L)))
        assertTrue(External.getField(record, "name") == Some(VStr("Alice")))
      },
      test("returns None when the field is absent") {
        val record = Value.recordTrusted(List("name" -> VStr("Alice")))
        assertTrue(External.getField(record, "age") == None)
      },
      test("returns None for non-record input") {
        assertTrue(
          External.getField(VStr("text"), "name") == None,
          External.getField(VInt(42L), "name") == None,
          External.getField(VList(Nil), "name") == None,
          External.getField(VNull, "name") == None
        )
      }
    ),
    suite("fallbackMessage")(
      test("formats the plain message without color") {
        assertTrue(
          External.fallbackMessage("disabled", color = false) ==
            "(ai formatting unavailable: disabled)"
        )
      },
      test("wraps the message in dim escape codes with color") {
        assertTrue(
          External.fallbackMessage("no API key", color = true) ==
            s"$esc[2m(ai formatting unavailable: no API key)$esc[0m"
        )
      }
    ),
    suite("fallback")(
      test("returns stripped stdout and writes the reason to stderr") {
        for {
          value <- External.fallback("output \n\n", "disabled", color = false)
          err <- TestConsole.outputErr
        } yield assertTrue(
          value == VStr("output"),
          err == Vector("(ai formatting unavailable: disabled)\n")
        )
      },
      test("writes the dimmed message to stderr when color is enabled") {
        for {
          value <- External.fallback("out", "ai request failed", color = true)
          err <- TestConsole.outputErr
        } yield assertTrue(
          value == VStr("out"),
          err == Vector(s"$esc[2m(ai formatting unavailable: ai request failed)$esc[0m\n")
        )
      }
    ),
    suite("formatWithEnv")(
      test("falls back with 'disabled' when TABBY_DISABLE_AI is set") {
        val env = envFrom(Map("TABBY_DISABLE_AI" -> "1", "OPENROUTER_API_KEY" -> "some-key"))
        for {
          value <- External.formatWithEnv("ls", Nil, "out\n", state(color = false, cwd = "/"))(env)
          err <- TestConsole.outputErr
        } yield assertTrue(
          value == VStr("out"),
          err == Vector("(ai formatting unavailable: disabled)\n")
        )
      },
      test("treats an empty TABBY_DISABLE_AI as not disabled") {
        val env = envFrom(Map("TABBY_DISABLE_AI" -> ""))
        for {
          value <- External.formatWithEnv("ls", Nil, "out", state(color = false, cwd = "/"))(env)
          err <- TestConsole.outputErr
        } yield assertTrue(
          value == VStr("out"),
          err == Vector("(ai formatting unavailable: no API key)\n")
        )
      },
      test("falls back with 'no API key' when the key is missing") {
        for {
          value <- External.formatWithEnv("ls", Nil, "out", state(color = false, cwd = "/"))(noEnv)
          err <- TestConsole.outputErr
        } yield assertTrue(
          value == VStr("out"),
          err == Vector("(ai formatting unavailable: no API key)\n")
        )
      },
      test("treats a blank API key as missing") {
        val env = envFrom(Map("OPENROUTER_API_KEY" -> ""))
        for {
          value <- External.formatWithEnv("ls", Nil, "out", state(color = false, cwd = "/"))(env)
          err <- TestConsole.outputErr
        } yield assertTrue(
          value == VStr("out"),
          err == Vector("(ai formatting unavailable: no API key)\n")
        )
      },
      test("falls back with 'ai request failed' when the request cannot be built") {
        // An invalid base URL makes URI.create throw before any network activity.
        val env = envFrom(
          Map(
            "OPENROUTER_API_KEY" -> "test-key",
            "OPENROUTER_BASE_URL" -> "http://invalid url with spaces"
          )
        )
        for {
          value <- External.formatWithEnv("ls", Nil, "out", state(color = false, cwd = "/"))(env)
          err <- TestConsole.outputErr
        } yield assertTrue(
          value == VStr("out"),
          err == Vector("(ai formatting unavailable: ai request failed)\n")
        )
      }
    ),
    suite("run")(
      test("fails with IoError for a nonexistent command") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- External
              .run(
                "/definitely/missing/tabby-binary",
                Nil,
                state(color = false, cwd = dir.toString)
              )
              .either
          } yield result match {
            case Left(TabbyError.IoError(name, _)) =>
              assertTrue(name == "/definitely/missing/tabby-binary")
            case _ => assertTrue(false)
          }
        }
      },
      test("fails with ExternalFailed when the command exits non-zero") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- External
              .run("/usr/bin/false", Nil, state(color = false, cwd = dir.toString))
              .either
          } yield result match {
            case Left(TabbyError.ExternalFailed(name, status)) =>
              assertTrue(name == "/usr/bin/false", status == 1)
            case _ => assertTrue(false)
          }
        }
      },
      test("returns stripped stdout when AI is disabled via the environment") {
        val env = envFrom(Map("TABBY_DISABLE_AI" -> "1"))
        ZIO.scoped {
          for {
            dir <- tempDir
            value <- External
              .runWithEnv("/bin/echo", List("hello"), state(color = false, cwd = dir.toString))(env)
            err <- TestConsole.outputErr
          } yield assertTrue(
            value == VStr("hello"),
            err == Vector("(ai formatting unavailable: disabled)\n")
          )
        }
      },
      test("falls back with 'no API key' for a successful command without configuration") {
        ZIO.scoped {
          for {
            dir <- tempDir
            value <- External.runWithEnv(
              "/bin/echo",
              List("hello"),
              state(color = false, cwd = dir.toString)
            )(noEnv)
            err <- TestConsole.outputErr
          } yield assertTrue(
            value == VStr("hello"),
            err == Vector("(ai formatting unavailable: no API key)\n")
          )
        }
      }
    )
  )
}
