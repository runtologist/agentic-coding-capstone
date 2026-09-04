package tabbyshell

import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{FileTime, PosixFilePermissions}
import java.nio.file.{Files, Path, Paths}

object ExecutorSpec extends ZIOSpecDefault {
  import Parser.{Arg, Literal}
  import TabbyError.*
  import Value.*

  private val X = tabbyshell.Executor

  private val peopleJson =
    """[{"name":"Alice","age":30},{"name":"Bob","age":25},{"name":"Carol","age":40}]"""

  private val ranksJson =
    """[{"name":"a","rank":2},{"name":"b","rank":1},{"name":"c","rank":2}]"""

  private val bigIntJson =
    """[{"n":9007199254740993,"tag":"big"},{"n":9007199254740992,"tag":"small"}]"""

  private val boolJson =
    """[{"active":true,"name":"a"},{"active":false,"name":"b"}]"""

  private val mixedListJson = """[1,"two",true,null]"""

  private val nullsJson = """[{"x":null},{"x":null}]"""

  private val peopleTable = table(
    List("name", "age"),
    List(
      List(VStr("Alice"), VInt(30L)),
      List(VStr("Bob"), VInt(25L)),
      List(VStr("Carol"), VInt(40L))
    )
  )

  private def tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("tabbyshell-spec")))(dir =>
      ZIO.attemptBlocking(deleteRecursively(dir)).ignore
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

  private def write(dir: Path, name: String, content: String): Path = {
    val path = dir.resolve(name)
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    path
  }

  private def mkdir(dir: Path, name: String): Path =
    Files.createDirectory(dir.resolve(name))

  private def setModifiedSeconds(path: Path, seconds: Long): Unit = {
    Files.setLastModifiedTime(path, FileTime.fromMillis(seconds * 1000L))
    ()
  }

  private def readText(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def state(dir: Path): ShellState =
    ShellState(cwd = dir.toString, prevCwd = None, home = dir.toString, now = 0L, color = false)

  private def parse(script: String): Either[TabbyError, Parser.Pipeline] =
    Parser.parseLine(script) match {
      case Right(Some(pipeline)) => Right(pipeline)
      case Right(None)           => Left(Parse("empty pipeline", 0))
      case Left(error)           => Left(error)
    }

  private def run(script: String, shellState: ShellState): IO[TabbyError, (Value, ShellState)] =
    for {
      pipeline <- ZIO.fromEither(parse(script))
      result <- X.runPipeline(pipeline, shellState)
    } yield result

  private def runValue(script: String, dir: Path): IO[TabbyError, Value] =
    run(script, state(dir)).map(_._1)

  private def runWithFile(fileName: String, content: String, script: Path => String) =
    ZIO.scoped {
      for {
        dir <- tempDir
        path <- ZIO.attemptBlocking(write(dir, fileName, content))
        value <- runValue(script(path), dir).either
      } yield value
    }

  private def namesOf(value: Value): List[String] = value match {
    case VList(items) => items.collect { case VStr(name) => name }
    case _            => Nil
  }

  private def table(columns: List[String], rows: List[List[Value]]): VTable =
    Value.table(columns, rows).toOption.get

  private def record(fields: List[(String, Value)]): VRecord =
    Value.record(fields).toOption.get

  override def spec = suite("Executor")(
    suite("ls")(
      test("lists visible entries sorted by name with type, size, and modified") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking {
              write(dir, "b.txt", "bbb")
              write(dir, "a.txt", "aaaa")
              mkdir(dir, "sub")
              write(dir, ".hidden", "h")
              setModifiedSeconds(dir.resolve("a.txt"), 1000L)
              setModifiedSeconds(dir.resolve("b.txt"), 2000L)
              setModifiedSeconds(dir.resolve("sub"), 3000L)
            }
            result <- runValue("ls", dir).either
          } yield assertTrue(
            result == Right(
              table(
                List("name", "type", "size", "modified"),
                List(
                  List(VStr("a.txt"), VStr("file"), VFilesize(4L), VDate(1000L)),
                  List(VStr("b.txt"), VStr("file"), VFilesize(3L), VDate(2000L)),
                  List(VStr("sub"), VStr("dir"), VFilesize(0L), VDate(3000L))
                )
              )
            )
          )
        }
      },
      test("-a includes dotfiles") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking {
              write(dir, ".hidden", "h")
              write(dir, "visible.txt", "v")
            }
            result <- runValue("ls -a | get name", dir).either
          } yield assertTrue(
            result == Right(VList(List(VStr(".hidden"), VStr("visible.txt"))))
          )
        }
      },
      test("--all long flag also includes dotfiles") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, ".hidden", "h"))
            result <- runValue("ls --all | get name", dir).either
          } yield assertTrue(result == Right(VList(List(VStr(".hidden")))))
        }
      },
      test("-l adds mode and uid columns") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking {
              val file = write(dir, "f.txt", "abc")
              setModifiedSeconds(file, 5L)
              Files.setPosixFilePermissions(
                file,
                PosixFilePermissions.fromString("rw-r--r--")
              )
            }
            result <- runValue("ls -l", dir).either
          } yield result match {
            case Right(VTable(columns, List(row))) =>
              assertTrue(
                columns == List("name", "type", "size", "modified", "mode", "uid"),
                row.take(5) == List(
                  VStr("f.txt"),
                  VStr("file"),
                  VFilesize(3L),
                  VDate(5L),
                  VStr("rw-r--r--")
                ),
                row.lift(5).exists {
                  case VInt(_) => true
                  case _       => false
                }
              )
            case _ => assertTrue(false)
          }
        }
      },
      test("reports file, dir, and symlink types; directory size is 0") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking {
              write(dir, "target.txt", "hello")
              Files.createSymbolicLink(dir.resolve("link.txt"), Paths.get("target.txt"))
              mkdir(dir, "nested")
            }
            result <- runValue("ls", dir).either
          } yield result match {
            case Right(VTable(_, rows)) =>
              assertTrue(
                rows.map(_.take(3)) == List(
                  List(VStr("link.txt"), VStr("symlink"), VFilesize(10L)),
                  List(VStr("nested"), VStr("dir"), VFilesize(0L)),
                  List(VStr("target.txt"), VStr("file"), VFilesize(5L))
                )
              )
            case _ => assertTrue(false)
          }
        }
      },
      test("lists an explicit path argument") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking {
              val sub = mkdir(dir, "sub")
              write(sub, "inner.txt", "x")
            }
            result <- runValue("ls sub | get name", dir).either
          } yield assertTrue(result == Right(VList(List(VStr("inner.txt")))))
        }
      },
      test("fails with IoError for a missing directory") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- runValue("ls nope", dir).either
          } yield result match {
            case Left(IoError("ls", msg)) =>
              assertTrue(msg.endsWith("No such file or directory"))
            case _ => assertTrue(false)
          }
        }
      },
      test("rejects extra path arguments") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- runValue("ls a b", dir).either
          } yield assertTrue(result == Left(BadArg("ls", "too many arguments")))
        }
      }
    ),
    suite("open")(
      test("parses uniform JSON arrays of records into tables") {
        runWithFile("people.json", peopleJson, p => s"open \"$p\"").map(result =>
          assertTrue(result == Right(peopleTable))
        )
      },
      test("parses non-uniform JSON arrays of records into lists") {
        val expected = VList(
          List(record(List("a" -> VInt(1L))), record(List("b" -> VInt(2L))))
        )
        runWithFile("data.json", """[{"a":1},{"b":2}]""", p => s"open \"$p\"").map(result =>
          assertTrue(result == Right(expected))
        )
      },
      test("parses mixed JSON arrays into lists") {
        val expected = VList(List(VInt(1L), VStr("two"), VBool(true), VNull))
        runWithFile("data.json", mixedListJson, p => s"open \"$p\"").map(result =>
          assertTrue(result == Right(expected))
        )
      },
      test("parses an empty JSON array into an empty list") {
        runWithFile("data.json", "[]", p => s"open \"$p\"").map(result =>
          assertTrue(result == Right(VList(Nil)))
        )
      },
      test("parses JSON objects into records") {
        val content =
          """{"name":"Alice","age":30,"scores":[1,2],"meta":{"active":true},"missing":null}"""
        val expected = record(
          List(
            "name" -> VStr("Alice"),
            "age" -> VInt(30L),
            "scores" -> VList(List(VInt(1L), VInt(2L))),
            "meta" -> record(List("active" -> VBool(true))),
            "missing" -> VNull
          )
        )
        runWithFile("data.json", content, p => s"open \"$p\"").map(result =>
          assertTrue(result == Right(expected))
        )
      },
      test("parses JSON numbers into int and float") {
        val expected = record(List("i" -> VInt(5L), "f" -> VFloat(2.5)))
        runWithFile("data.json", """{"i":5,"f":2.5}""", p => s"open \"$p\"").map(result =>
          assertTrue(result == Right(expected))
        )
      },
      test("parses CSV files into tables of strings") {
        val expected = table(
          List("name", "city"),
          List(
            List(VStr("Alice"), VStr("Paris")),
            List(VStr("Bob"), VStr("New York"))
          )
        )
        val content = "name,city\nAlice,Paris\nBob,\"New York\"\n"
        runWithFile("data.csv", content, p => s"open \"$p\"").map(result =>
          assertTrue(result == Right(expected))
        )
      },
      test("parses an empty CSV file into an empty table") {
        runWithFile("data.csv", "", p => s"open \"$p\"").map(result =>
          assertTrue(result == Right(table(Nil, Nil)))
        )
      },
      test("opens plain text files as strings") {
        runWithFile("notes.txt", "hello\nworld\n", p => s"open \"$p\"").map(result =>
          assertTrue(result == Right(VStr("hello\nworld\n")))
        )
      },
      test("rejects invalid JSON with BadArg") {
        runWithFile("bad.json", "{ bad", p => s"open \"$p\"").map(result =>
          assertTrue(
            result == Left(BadArg("open", "invalid JSON: expected object key string at position 2"))
          )
        )
      },
      test("rejects ragged CSV rows with BadArg") {
        runWithFile("ragged.csv", "a,b\n1,2,3\n", p => s"open \"$p\"").map(result =>
          assertTrue(result == Left(BadArg("open", "row 0 has 3 columns, expected 2")))
        )
      },
      test("fails with IoError when the file is missing") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- runValue("open missing.json", dir).either
          } yield result match {
            case Left(IoError("open", msg)) =>
              assertTrue(msg.endsWith("No such file or directory"))
            case _ => assertTrue(false)
          }
        }
      },
      test("requires a path argument") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- runValue("open", dir).either
          } yield assertTrue(result == Left(MissingArg("open", "path")))
        }
      },
      test("expands ~ against the shell home") {
        ZIO.scoped {
          for {
            home <- tempDir
            cwd <- tempDir
            _ <- ZIO.attemptBlocking(write(home, "data.json", "[1]"))
            st = ShellState(
              cwd = cwd.toString,
              prevCwd = None,
              home = home.toString,
              now = 0L,
              color = false
            )
            result <- run("open ~/data.json", st).either
          } yield assertTrue(result == Right((VList(List(VInt(1L))), st)))
        }
      }
    ),
    suite("cat")(
      test("returns the raw file text") {
        runWithFile("f.txt", "hello\nworld", p => s"cat \"$p\"").map(result =>
          assertTrue(result == Right(VStr("hello\nworld")))
        )
      },
      test("fails with IoError for a missing file") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- runValue("cat missing.txt", dir).either
          } yield result match {
            case Left(IoError("cat", msg)) =>
              assertTrue(msg.endsWith("No such file or directory"))
            case _ => assertTrue(false)
          }
        }
      },
      test("requires a path argument") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- runValue("cat", dir).either
          } yield assertTrue(result == Left(MissingArg("cat", "path")))
        }
      },
      test("rejects extra arguments") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- runValue("cat a b", dir).either
          } yield assertTrue(result == Left(BadArg("cat", "too many arguments")))
        }
      },
      test("treats a bare dash as a literal path") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- runValue("cat -", dir).either
          } yield result match {
            case Left(IoError("cat", msg)) =>
              assertTrue(msg.endsWith("No such file or directory"))
            case _ => assertTrue(false)
          }
        }
      }
    ),
    suite("cd")(
      test("changes into a relative directory and records the previous cwd") {
        ZIO.scoped {
          for {
            dir <- tempDir
            sub <- ZIO.attemptBlocking(mkdir(dir, "sub"))
            st = state(dir)
            result <- run("cd sub", st).either
          } yield assertTrue(
            result == Right((VNull, st.copy(cwd = sub.toString, prevCwd = Some(dir.toString))))
          )
        }
      },
      test("changes into an absolute directory") {
        ZIO.scoped {
          for {
            dir <- tempDir
            other <- tempDir
            st = state(dir)
            result <- run(s"cd \"${other.toString}\"", st).either
          } yield assertTrue(
            result == Right(
              (VNull, st.copy(cwd = other.toString, prevCwd = Some(dir.toString)))
            )
          )
        }
      },
      test("goes home without an argument") {
        ZIO.scoped {
          for {
            dir <- tempDir
            home <- tempDir
            st = ShellState(
              cwd = dir.toString,
              prevCwd = None,
              home = home.toString,
              now = 0L,
              color = false
            )
            result <- run("cd", st).either
          } yield assertTrue(
            result == Right(
              (VNull, st.copy(cwd = home.toString, prevCwd = Some(dir.toString)))
            )
          )
        }
      },
      test("expands ~/sub against home") {
        ZIO.scoped {
          for {
            dir <- tempDir
            home <- tempDir
            sub <- ZIO.attemptBlocking(mkdir(home, "sub"))
            st = ShellState(
              cwd = dir.toString,
              prevCwd = None,
              home = home.toString,
              now = 0L,
              color = false
            )
            result <- run("cd ~/sub", st).either
          } yield assertTrue(
            result == Right(
              (VNull, st.copy(cwd = sub.toString, prevCwd = Some(dir.toString)))
            )
          )
        }
      },
      test("cd - returns to the previous directory and swaps prevCwd") {
        ZIO.scoped {
          for {
            dir <- tempDir
            prev <- tempDir
            st = ShellState(
              cwd = dir.toString,
              prevCwd = Some(prev.toString),
              home = dir.toString,
              now = 0L,
              color = false
            )
            result <- run("cd -", st).either
          } yield assertTrue(
            result == Right(
              (VNull, st.copy(cwd = prev.toString, prevCwd = Some(dir.toString)))
            )
          )
        }
      },
      test("cd - fails when there is no previous directory") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- run("cd -", state(dir)).either
          } yield assertTrue(result == Left(BadArg("cd", "no previous directory")))
        }
      },
      test("cd ~ goes home") {
        ZIO.scoped {
          for {
            dir <- tempDir
            home <- tempDir
            st = ShellState(
              cwd = dir.toString,
              prevCwd = None,
              home = home.toString,
              now = 0L,
              color = false
            )
            result <- run("cd ~", st).either
          } yield assertTrue(
            result == Right(
              (VNull, st.copy(cwd = home.toString, prevCwd = Some(dir.toString)))
            )
          )
        }
      },
      test("fails when the target is a file") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "file.txt", "x"))
            result <- run("cd file.txt", state(dir)).either
          } yield assertTrue(result == Left(BadArg("cd", "not a directory: file.txt")))
        }
      },
      test("fails with IoError when the directory is missing") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- run("cd nope", state(dir)).either
          } yield assertTrue(
            result == Left(IoError("cd", "nope: No such file or directory"))
          )
        }
      },
      test("rejects extra arguments") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- run("cd a b", state(dir)).either
          } yield assertTrue(result == Left(BadArg("cd", "too many arguments")))
        }
      },
      test("normalizes . and .. segments") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(mkdir(dir, "sub"))
            other <- ZIO.attemptBlocking(mkdir(dir, "other"))
            st = state(dir)
            result <- run("cd sub/../other", st).either
          } yield assertTrue(
            result == Right(
              (VNull, st.copy(cwd = other.toString, prevCwd = Some(dir.toString)))
            )
          )
        }
      },
      test("later pipeline stages see the updated state") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking {
              val sub = mkdir(dir, "sub")
              write(sub, "inner.txt", "x")
            }
            result <- runValue("cd sub | ls | get name", dir).either
          } yield assertTrue(result == Right(VList(List(VStr("inner.txt")))))
        }
      }
    ),
    suite("pwd")(
      test("returns the current working directory") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- runValue("pwd", dir).either
          } yield assertTrue(result == Right(VStr(dir.toString)))
        }
      }
    ),
    suite("save")(
      test("writes tables as pretty JSON for .json paths") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "in.json", peopleJson))
            result <- runValue("open in.json | save out.json", dir).either
            content <- ZIO.attemptBlocking(readText(dir.resolve("out.json")))
          } yield assertTrue(
            result == Right(VNull),
            content == Json.pretty(peopleTable)
          )
        }
      },
      test("writes tables as CSV for .csv paths") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "in.json", peopleJson))
            result <- runValue("open in.json | save out.csv", dir).either
            content <- ZIO.attemptBlocking(readText(dir.resolve("out.csv")))
          } yield assertTrue(
            result == Right(VNull),
            content == Csv.toCsv(peopleTable)
          )
        }
      },
      test("writes raw string content for other paths") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "in.txt", "no trailing newline"))
            result <- runValue("cat in.txt | save out.txt", dir).either
            content <- ZIO.attemptBlocking(readText(dir.resolve("out.txt")))
          } yield assertTrue(result == Right(VNull), content == "no trailing newline")
        }
      },
      test("falls back to raw strings for .csv paths with non-table input") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "in.txt", "plain"))
            result <- runValue("cat in.txt | save out.csv", dir).either
            content <- ZIO.attemptBlocking(readText(dir.resolve("out.csv")))
          } yield assertTrue(result == Right(VNull), content == "plain")
        }
      },
      test("renders non-string values for plain paths") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "in.json", peopleJson))
            result <- runValue("open in.json | save out.txt", dir).either
            content <- ZIO.attemptBlocking(readText(dir.resolve("out.txt")))
            expected = Render.output(
              peopleTable,
              RenderOpts(color = false, maxColWidth = 40, now = 0L)
            )
          } yield assertTrue(result == Right(VNull), content == expected)
        }
      },
      test("writes scalars as JSON for .json paths") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "in.json", peopleJson))
            result <- runValue("open in.json | length | save n.json", dir).either
            content <- ZIO.attemptBlocking(readText(dir.resolve("n.json")))
          } yield assertTrue(result == Right(VNull), content == "3\n")
        }
      },
      test("fails with IoError when the directory is missing") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "in.txt", "x"))
            result <- runValue("cat in.txt | save missing/out.txt", dir).either
          } yield result match {
            case Left(IoError("save", msg)) =>
              assertTrue(msg.endsWith("No such file or directory"))
            case _ => assertTrue(false)
          }
        }
      },
      test("requires a path argument") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "in.json", peopleJson))
            result <- runValue("open in.json | save", dir).either
          } yield assertTrue(result == Left(MissingArg("save", "path")))
        }
      }
    ),
    suite("to")(
      test("converts tables to JSON") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | to json").map(result =>
          assertTrue(result == Right(VStr(Json.pretty(peopleTable))))
        )
      },
      test("converts records to JSON") {
        val expected = VStr(Json.pretty(record(List("a" -> VInt(1L)))))
        runWithFile("obj.json", """{"a":1}""", p => s"open \"$p\" | to json").map(result =>
          assertTrue(result == Right(expected))
        )
      },
      test("converts tables to CSV") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | to csv").map(result =>
          assertTrue(result == Right(VStr(Csv.toCsv(peopleTable))))
        )
      },
      test("accepts format names case-insensitively") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | to JSON").map(result =>
          assertTrue(result == Right(VStr(Json.pretty(peopleTable))))
        )
      },
      test("rejects unsupported formats") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | to xml").map(result =>
          assertTrue(result == Left(BadArg("to", "unsupported format: xml")))
        )
      },
      test("rejects csv for non-table input") {
        runWithFile("in.txt", "plain", p => s"cat \"$p\" | to csv").map(result =>
          assertTrue(result == Left(TypeMismatch("to", "table", "string")))
        )
      },
      test("requires a format argument") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | to").map(result =>
          assertTrue(result == Left(MissingArg("to", "format")))
        )
      },
      test("rejects extra arguments") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | to json csv").map(result =>
          assertTrue(result == Left(BadArg("to", "too many arguments")))
        )
      }
    ),
    suite("get")(
      test("extracts a table column as a list") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | get name").map(result =>
          assertTrue(
            result == Right(VList(List(VStr("Alice"), VStr("Bob"), VStr("Carol"))))
          )
        )
      },
      test("accepts quoted column names") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | get \"age\"").map(result =>
          assertTrue(result == Right(VList(List(VInt(30L), VInt(25L), VInt(40L)))))
        )
      },
      test("extracts a record field") {
        runWithFile("obj.json", """{"name":"Alice","age":30}""", p => s"open \"$p\" | get name")
          .map(result => assertTrue(result == Right(VStr("Alice"))))
      },
      test("fails on a missing table column") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | get nope").map(result =>
          assertTrue(result == Left(MissingColumn("get", "nope")))
        )
      },
      test("fails on a missing record field") {
        runWithFile("obj.json", """{"name":"Alice"}""", p => s"open \"$p\" | get nope").map(
          result => assertTrue(result == Left(MissingColumn("get", "nope")))
        )
      },
      test("fails on unsupported input types") {
        runWithFile("in.txt", "plain", p => s"cat \"$p\" | get x").map(result =>
          assertTrue(result == Left(TypeMismatch("get", "table or record", "string")))
        )
      },
      test("requires exactly one argument") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | get").map(result =>
          assertTrue(result == Left(MissingArg("get", "column")))
        )
      },
      test("rejects extra arguments") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | get a b").map(result =>
          assertTrue(result == Left(BadArg("get", "too many arguments")))
        )
      }
    ),
    suite("length")(
      test("counts table rows") {
        runWithFile("in.json", peopleJson, p => s"open \"$p\" | length").map(result =>
          assertTrue(result == Right(VInt(3L)))
        )
      },
      test("counts list items") {
        runWithFile("in.json", mixedListJson, p => s"open \"$p\" | length").map(result =>
          assertTrue(result == Right(VInt(4L)))
        )
      },
      test("counts string code points") {
        val content = "ab" + new String(Character.toChars(0x1f600))
        runWithFile("s.txt", content, p => s"cat \"$p\" | length").map(result =>
          assertTrue(result == Right(VInt(3L)))
        )
      },
      test("null input has length 0") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <- runValue("length", dir).either
          } yield assertTrue(result == Right(VInt(0L)))
        }
      },
      test("rejects unsupported input types") {
        runWithFile("obj.json", """{"a":1}""", p => s"open \"$p\" | length").map(result =>
          assertTrue(
            result == Left(
              TypeMismatch("length", "table, list, string, or null", "record")
            )
          )
        )
      }
    ),
    suite("select")(
      test("rejects duplicate requested columns with BadArg") {
        for {
          result <- runWithFile("people.json", peopleJson, p => s"open \"$p\" | select name name")
        } yield assertTrue(
          result == Left(BadArg("select", "duplicate column: name"))
        )
      },
      test("first duplicate in argument order wins") {
        for {
          result <- runWithFile(
            "people.json",
            peopleJson,
            p => s"open \"$p\" | select age name age"
          )
        } yield assertTrue(
          result == Left(BadArg("select", "duplicate column: age"))
        )
      },
      test("selects requested columns in the requested order") {
        for {
          result <- runWithFile("people.json", peopleJson, p => s"open \"$p\" | select age name")
        } yield result match {
          case Right(VTable(columns, rows)) =>
            assertTrue(
              columns == List("age", "name"),
              rows == List(
                List(VInt(30L), VStr("Alice")),
                List(VInt(25L), VStr("Bob")),
                List(VInt(40L), VStr("Carol"))
              )
            )
          case _ => assertTrue(false)
        }
      },
      test("requires at least one column") {
        runWithFile("people.json", peopleJson, p => s"open \"$p\" | select").map(result =>
          assertTrue(result == Left(MissingArg("select", "columns")))
        )
      },
      test("rejects non-string column arguments") {
        runWithFile("people.json", peopleJson, p => s"open \"$p\" | select 5").map(result =>
          assertTrue(result == Left(BadArg("select", "column must be a string")))
        )
      },
      test("fails on a missing column") {
        runWithFile("people.json", peopleJson, p => s"open \"$p\" | select nope").map(result =>
          assertTrue(result == Left(MissingColumn("select", "nope")))
        )
      },
      test("fails on non-table input") {
        runWithFile("in.txt", "plain", p => s"cat \"$p\" | select x").map(result =>
          assertTrue(result == Left(TypeMismatch("select", "table", "string")))
        )
      }
    ),
    suite("where")(
      test("filters with exact integer comparison beyond double precision") {
        for {
          result <-
            runWithFile(
              "big.json",
              bigIntJson,
              p => s"open \"$p\" | where n > 9007199254740992 | get tag"
            )
        } yield assertTrue(result == Right(VList(List(VStr("big")))))
      },
      test("supports every numeric comparison operator") {
        for {
          eq <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | where age == 30 | get name")
          ne <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | where age != 30 | get name")
          lt <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | where age < 30 | get name")
          le <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | where age <= 30 | get name")
          gt <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | where age > 30 | get name")
          ge <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | where age >= 30 | get name")
        } yield assertTrue(
          eq == Right(VList(List(VStr("Alice")))),
          ne == Right(VList(List(VStr("Bob"), VStr("Carol")))),
          lt == Right(VList(List(VStr("Bob")))),
          le == Right(VList(List(VStr("Alice"), VStr("Bob")))),
          gt == Right(VList(List(VStr("Carol")))),
          ge == Right(VList(List(VStr("Alice"), VStr("Carol"))))
        )
      },
      test("compares int cells against float literals") {
        runWithFile("p.json", peopleJson, p => s"open \"$p\" | where age > 25.5 | get name")
          .map(result => assertTrue(result == Right(VList(List(VStr("Alice"), VStr("Carol"))))))
      },
      test("compares filesize columns against filesize and int literals") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking {
              write(dir, "big.txt", "12345")
              write(dir, "small.txt", "x")
            }
            bySize <- runValue("ls | where size > 3b | get name", dir).either
            byInt <- runValue("ls | where size >= 1 | get name", dir).either
          } yield assertTrue(
            bySize == Right(VList(List(VStr("big.txt")))),
            byInt == Right(VList(List(VStr("big.txt"), VStr("small.txt"))))
          )
        }
      },
      test("compares strings lexicographically") {
        for {
          lt <- runWithFile(
            "p.json",
            peopleJson,
            p => s"open \"$p\" | where name < \"Carol\" | get name"
          )
          ne <- runWithFile(
            "p.json",
            peopleJson,
            p => s"open \"$p\" | where name != \"Bob\" | get name"
          )
        } yield assertTrue(
          lt == Right(VList(List(VStr("Alice"), VStr("Bob")))),
          ne == Right(VList(List(VStr("Alice"), VStr("Carol"))))
        )
      },
      test("supports equality on bool columns") {
        for {
          eq <- runWithFile(
            "bool.json",
            boolJson,
            p => s"open \"$p\" | where active == true | get name"
          )
          ne <- runWithFile(
            "bool.json",
            boolJson,
            p => s"open \"$p\" | where active != true | get name"
          )
        } yield assertTrue(
          eq == Right(VList(List(VStr("a")))),
          ne == Right(VList(List(VStr("b"))))
        )
      },
      test("rejects ordering operators on bool columns") {
        for {
          result <- runWithFile("bool.json", boolJson, p => s"open \"$p\" | where active > true")
        } yield assertTrue(
          result == Left(
            BadArg("where", "operator '>' is not supported for bool values")
          )
        )
      },
      test("supports equality on all-null columns") {
        val twoNulls = table(List("x"), List(List(VNull), List(VNull)))
        for {
          eq <- runWithFile("n.json", nullsJson, p => s"open \"$p\" | where x == null")
          ne <- runWithFile("n.json", nullsJson, p => s"open \"$p\" | where x != null")
        } yield assertTrue(
          eq == Right(twoNulls),
          ne == Right(table(List("x"), Nil))
        )
      },
      test("rejects ordering operators on null values") {
        runWithFile("n.json", nullsJson, p => s"open \"$p\" | where x > null").map(result =>
          assertTrue(
            result == Left(BadArg("where", "operator '>' is not supported for null values"))
          )
        )
      },
      test("fails on type mismatches") {
        for {
          strVsInt <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | where name > 3")
          dir <- tempDir
          _ <- ZIO.attemptBlocking(write(dir, "f.txt", "x"))
          dateVsInt <- runValue("ls | where modified == 0", dir).either
        } yield assertTrue(
          strVsInt == Left(TypeMismatch("where", "int", "string")),
          dateVsInt == Left(TypeMismatch("where", "int", "date"))
        )
      },
      test("fails on a missing column") {
        runWithFile("p.json", peopleJson, p => s"open \"$p\" | where nope == 1").map(result =>
          assertTrue(result == Left(MissingColumn("where", "nope")))
        )
      },
      test("requires exactly three arguments") {
        for {
          tooFew <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | where age ==")
          tooMany <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | where age > 1 2")
        } yield assertTrue(
          tooFew == Left(MissingArg("where", "column op literal")),
          tooMany == Left(BadArg("where", "too many arguments"))
        )
      },
      test("requires a bare identifier column") {
        runWithFile("p.json", peopleJson, p => s"open \"$p\" | where \"age\" == 30").map(result =>
          assertTrue(result == Left(BadArg("where", "column must be a bare identifier")))
        )
      },
      test("requires a comparison operator") {
        runWithFile("p.json", peopleJson, p => s"open \"$p\" | where age age 30").map(result =>
          assertTrue(result == Left(BadArg("where", "expected comparison operator")))
        )
      },
      test("requires a literal value") {
        runWithFile("p.json", peopleJson, p => s"open \"$p\" | where age == age").map(result =>
          assertTrue(result == Left(BadArg("where", "expected literal value")))
        )
      },
      test("requires table input") {
        runWithFile("in.txt", "plain", p => s"cat \"$p\" | where x == 1").map(result =>
          assertTrue(result == Left(TypeMismatch("where", "table", "string")))
        )
      }
    ),
    suite("sort-by")(
      test("ascending sort is stable for equal keys") {
        for {
          result <- runWithFile(
            "ranks.json",
            ranksJson,
            p => s"open \"$p\" | sort-by rank | get name"
          )
        } yield assertTrue(
          result == Right(VList(List(VStr("b"), VStr("a"), VStr("c"))))
        )
      },
      test("--reverse reverses the stable ascending order") {
        for {
          result <-
            runWithFile(
              "ranks.json",
              ranksJson,
              p => s"open \"$p\" | sort-by rank --reverse | get name"
            )
        } yield assertTrue(
          result == Right(VList(List(VStr("c"), VStr("a"), VStr("b"))))
        )
      },
      test("compares large integers beyond double precision") {
        for {
          result <- runWithFile("big.json", bigIntJson, p => s"open \"$p\" | sort-by n | get tag")
        } yield assertTrue(
          result == Right(VList(List(VStr("small"), VStr("big"))))
        )
      },
      test("sorts strings case-sensitively") {
        val content = """[{"name":"zeta"},{"name":"alpha"},{"name":"Beta"}]"""
        runWithFile("s.json", content, p => s"open \"$p\" | sort-by name | get name").map(result =>
          assertTrue(result == Right(VList(List(VStr("Beta"), VStr("alpha"), VStr("zeta")))))
        )
      },
      test("sorts bools with false before true and stays stable") {
        val content =
          """[{"b":true,"n":"t1"},{"b":false,"n":"f1"},{"b":true,"n":"t2"},{"b":false,"n":"f2"}]"""
        runWithFile("b.json", content, p => s"open \"$p\" | sort-by b | get n").map(result =>
          assertTrue(
            result == Right(VList(List(VStr("f1"), VStr("f2"), VStr("t1"), VStr("t2"))))
          )
        )
      },
      test("sorts dates chronologically") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking {
              val a = write(dir, "a.txt", "aa")
              val b = write(dir, "b.txt", "bb")
              val c = write(dir, "c.txt", "cc")
              setModifiedSeconds(a, 300L)
              setModifiedSeconds(b, 100L)
              setModifiedSeconds(c, 200L)
            }
            result <- runValue("ls | sort-by modified | get name", dir).either
          } yield assertTrue(
            result == Right(VList(List(VStr("b.txt"), VStr("c.txt"), VStr("a.txt"))))
          )
        }
      },
      test("accepts quoted column names") {
        runWithFile("ranks.json", ranksJson, p => s"open \"$p\" | sort-by \"rank\" | get name")
          .map(result => assertTrue(result == Right(VList(List(VStr("b"), VStr("a"), VStr("c"))))))
      },
      test("sorts an all-null column without reordering") {
        runWithFile("n.json", nullsJson, p => s"open \"$p\" | sort-by x").map(result =>
          assertTrue(result == Right(table(List("x"), List(List(VNull), List(VNull)))))
        )
      },
      test("fails on a missing column") {
        runWithFile("p.json", peopleJson, p => s"open \"$p\" | sort-by nope").map(result =>
          assertTrue(result == Left(MissingColumn("sort-by", "nope")))
        )
      },
      test("fails on mixed scalar types") {
        runWithFile("m.json", """[{"x":1},{"x":"a"}]""", p => s"open \"$p\" | sort-by x").map(
          result => assertTrue(result == Left(TypeMismatch("sort-by", "int", "string")))
        )
      },
      test("fails on non-scalar values") {
        runWithFile("m.json", """[{"x":[1]},{"x":[2]}]""", p => s"open \"$p\" | sort-by x")
          .map(result => assertTrue(result == Left(TypeMismatch("sort-by", "scalar", "list"))))
      },
      test("keeps an empty table empty") {
        runWithFile("e.csv", "a,b\n", p => s"open \"$p\" | sort-by a").map(result =>
          assertTrue(result == Right(table(List("a", "b"), Nil)))
        )
      },
      test("fails on non-table input") {
        runWithFile("in.txt", "plain", p => s"cat \"$p\" | sort-by x").map(result =>
          assertTrue(result == Left(TypeMismatch("sort-by", "table", "string")))
        )
      },
      test("requires exactly one column argument") {
        for {
          none <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | sort-by")
          two <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | sort-by a b")
        } yield assertTrue(
          none == Left(MissingArg("sort-by", "column")),
          two == Left(BadArg("sort-by", "too many arguments"))
        )
      }
    ),
    suite("first / last")(
      test("last without an argument returns the last row as a record") {
        for {
          result <- runWithFile("people.json", peopleJson, p => s"open \"$p\" | last")
        } yield result match {
          case Right(VRecord(fields)) =>
            assertTrue(fields == List("name" -> VStr("Carol"), "age" -> VInt(40L)))
          case other => assertTrue(false)
        }
      },
      test("first without an argument returns the first row as a record") {
        for {
          result <- runWithFile("people.json", peopleJson, p => s"open \"$p\" | first")
        } yield result match {
          case Right(VRecord(fields)) =>
            assertTrue(fields == List("name" -> VStr("Alice"), "age" -> VInt(30L)))
          case other => assertTrue(false)
        }
      },
      test("last with a count returns the trailing rows as a table") {
        for {
          result <- runWithFile("people.json", peopleJson, p => s"open \"$p\" | last 2")
        } yield result match {
          case Right(VTable(columns, rows)) =>
            assertTrue(
              columns == List("name", "age"),
              rows == List(List(VStr("Bob"), VInt(25L)), List(VStr("Carol"), VInt(40L)))
            )
          case other => assertTrue(false)
        }
      },
      test("first and last work on lists") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "mixed.json", mixedListJson))
            first <- runValue("open mixed.json | first", dir).either
            last <- runValue("open mixed.json | last", dir).either
            take2 <- runValue("open mixed.json | first 2", dir).either
            end2 <- runValue("open mixed.json | last 2", dir).either
          } yield assertTrue(
            first == Right(VInt(1L)),
            last == Right(VNull),
            take2 == Right(VList(List(VInt(1L), VStr("two")))),
            end2 == Right(VList(List(VBool(true), VNull)))
          )
        }
      },
      test("count 0 returns empty containers") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "p.json", peopleJson))
            table0 <- runValue("open p.json | first 0", dir).either
            list0 <- runValue("open p.json | get name | last 0", dir).either
          } yield assertTrue(
            table0 == Right(table(List("name", "age"), Nil)),
            list0 == Right(VList(Nil))
          )
        }
      },
      test("counts larger than the input keep everything") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- ZIO.attemptBlocking(write(dir, "p.json", peopleJson))
            all <- runValue("open p.json | first 10", dir).either
            all2 <- runValue("open p.json | last 99", dir).either
          } yield assertTrue(all == Right(peopleTable), all2 == Right(peopleTable))
        }
      },
      test("negative counts are rejected") {
        for {
          neg <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | first -1")
          neg2 <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | last -2")
        } yield assertTrue(
          neg == Left(BadArg("first", "count must be non-negative")),
          neg2 == Left(BadArg("last", "count must be non-negative"))
        )
      },
      test("non-integer arguments are rejected") {
        for {
          str <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | first \"x\"")
          flt <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | last 1.5")
        } yield assertTrue(
          str == Left(BadArg("first", "expected integer argument")),
          flt == Left(BadArg("last", "expected integer argument"))
        )
      },
      test("too many arguments are rejected") {
        for {
          result <- runWithFile("p.json", peopleJson, p => s"open \"$p\" | first 1 2")
        } yield assertTrue(result == Left(BadArg("first", "too many arguments")))
      },
      test("empty inputs fail without a count") {
        for {
          emptyList <- runWithFile("e.json", "[]", p => s"open \"$p\" | last")
          emptyTable <- runWithFile("e.csv", "a,b\n", p => s"open \"$p\" | first")
        } yield assertTrue(
          emptyList == Left(BadArg("last", "input is empty")),
          emptyTable == Left(BadArg("first", "input is empty"))
        )
      },
      test("empty inputs with a count return empty containers") {
        runWithFile("e.csv", "a,b\n", p => s"open \"$p\" | first 2").map(result =>
          assertTrue(result == Right(table(List("a", "b"), Nil)))
        )
      },
      test("reject unsupported input types") {
        for {
          str <- runWithFile("in.txt", "plain", p => s"cat \"$p\" | first")
          rec <- runWithFile("obj.json", """{"a":1}""", p => s"open \"$p\" | last")
          dir <- tempDir
          nul <- runValue("first", dir).either
        } yield assertTrue(
          str == Left(TypeMismatch("first", "table or list", "string")),
          rec == Left(TypeMismatch("last", "table or list", "record")),
          nul == Left(TypeMismatch("first", "table or list", "null"))
        )
      }
    ),
    suite("pipelines")(
      test("compose open, where, sort-by, and first like the spec example") {
        runWithFile(
          "p.json",
          peopleJson,
          p => s"open \"$p\" | where age > 20 | sort-by age --reverse | first 2"
        ).map(result =>
          assertTrue(
            result == Right(
              table(
                List("name", "age"),
                List(
                  List(VStr("Carol"), VInt(40L)),
                  List(VStr("Alice"), VInt(30L))
                )
              )
            )
          )
        )
      }
    ),
    suite("external commands")(
      test("fails with IoError for a nonexistent external command") {
        ZIO.scoped {
          for {
            dir <- tempDir
            result <-
              runValue("/nonexistent-tabbyshell-command hello", dir).either
          } yield result match {
            case Left(IoError(name, _)) =>
              assertTrue(name == "/nonexistent-tabbyshell-command")
            case _ => assertTrue(false)
          }
        }
      }
    ),
    suite("helpers")(
      suite("compareNumeric")(
        test("compares Int, Filesize, and Float values numerically") {
          assertTrue(
            X.compareNumeric(VInt(1L), VInt(2L)) < 0,
            X.compareNumeric(VInt(2L), VInt(2L)) == 0,
            X.compareNumeric(VInt(3L), VInt(2L)) > 0,
            X.compareNumeric(VFilesize(1000L), VFilesize(2000L)) < 0,
            X.compareNumeric(VInt(1000L), VFilesize(1000L)) == 0,
            X.compareNumeric(VFilesize(999L), VInt(1000L)) < 0,
            X.compareNumeric(VFloat(1.5), VInt(1L)) > 0,
            X.compareNumeric(VFloat(2.0), VFilesize(2L)) == 0,
            X.compareNumeric(VInt(2L), VFloat(2.5)) < 0
          )
        },
        test("keeps long precision beyond double range") {
          assertTrue(
            X.compareNumeric(VInt(9007199254740993L), VInt(9007199254740992L)) > 0,
            X.compareNumeric(
              VFilesize(9007199254740993L),
              VInt(9007199254740992L)
            ) > 0,
            X.compareNumeric(VInt(9007199254740993L), VFloat(9007199254740993.0)) > 0
          )
        },
        test("returns 0 for non-numeric values") {
          assertTrue(X.compareNumeric(VStr("a"), VBool(true)) == 0)
        }
      ),
      suite("clampToInt")(
        test("clamps extremes and passes through in-range values") {
          assertTrue(
            X.clampToInt(Long.MaxValue) == Int.MaxValue,
            X.clampToInt(Int.MaxValue.toLong + 1L) == Int.MaxValue,
            X.clampToInt(Long.MinValue) == Int.MinValue,
            X.clampToInt(Int.MinValue.toLong - 1L) == Int.MinValue,
            X.clampToInt(0L) == 0,
            X.clampToInt(42L) == 42
          )
        }
      ),
      suite("compareForWhere")(
        test("compares dates with every operator") {
          assertTrue(
            X.compareForWhere(VDate(100L), VDate(200L), "<") == Right(true),
            X.compareForWhere(VDate(200L), VDate(200L), "<=") == Right(true),
            X.compareForWhere(VDate(200L), VDate(200L), "==") == Right(true),
            X.compareForWhere(VDate(300L), VDate(200L), "!=") == Right(true),
            X.compareForWhere(VDate(300L), VDate(200L), ">") == Right(true),
            X.compareForWhere(VDate(200L), VDate(200L), ">=") == Right(true),
            X.compareForWhere(VDate(300L), VDate(200L), "<") == Right(false)
          )
        },
        test("rejects ordering operators for bool and null") {
          assertTrue(
            X.compareForWhere(VBool(true), VBool(false), "<") ==
              Left(BadArg("where", "operator '<' is not supported for bool values")),
            X.compareForWhere(VNull, VNull, ">=") ==
              Left(BadArg("where", "operator '>=' is not supported for null values"))
          )
        },
        test("reports type mismatches and unknown operators") {
          assertTrue(
            X.compareForWhere(VDate(1L), VInt(1L), "==") ==
              Left(TypeMismatch("where", "int", "date")),
            X.compareForWhere(VStr("a"), VInt(1L), ">") ==
              Left(TypeMismatch("where", "int", "string")),
            X.compareForWhere(VInt(1L), VInt(1L), "~") == Right(false)
          )
        }
      ),
      suite("externalArgString")(
        test("renders every argument shape for external commands") {
          assertTrue(
            X.externalArgString(Arg.Bare("foo")) == "foo",
            X.externalArgString(Arg.Op(">=")) == ">=",
            X.externalArgString(Arg.Dash) == "-",
            X.externalArgString(Arg.Lit(Literal.LStr("hello world"))) == "hello world",
            X.externalArgString(Arg.Lit(Literal.LInt(-5L))) == "-5",
            X.externalArgString(Arg.Lit(Literal.LFloat(1.25))) == "1.25",
            X.externalArgString(Arg.Lit(Literal.LBool(false))) == "false",
            X.externalArgString(Arg.Lit(Literal.LNull)) == "null",
            X.externalArgString(Arg.Lit(Literal.LFilesize(2048L))) == "2048b",
            X.externalArgString(Arg.Flag("a", None)) == "-a",
            X.externalArgString(Arg.Flag("all", None)) == "--all",
            X.externalArgString(Arg.Flag("n", Some(Literal.LInt(3L)))) == "-n=3",
            X.externalArgString(Arg.Flag("name", Some(Literal.LStr("x")))) == "--name=x"
          )
        }
      ),
      suite("literalToRawString")(
        test("renders literals without quoting") {
          assertTrue(
            X.literalToRawString(Literal.LStr("a b")) == "a b",
            X.literalToRawString(Literal.LInt(-3L)) == "-3",
            X.literalToRawString(Literal.LFloat(0.5)) == "0.5",
            X.literalToRawString(Literal.LBool(true)) == "true",
            X.literalToRawString(Literal.LNull) == "null",
            X.literalToRawString(Literal.LFilesize(1000L)) == "1000b"
          )
        }
      )
    )
  )
}
