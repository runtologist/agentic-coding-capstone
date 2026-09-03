package tabbyshell

import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object ExecutorSpec extends ZIOSpecDefault {
  import Value.*

  private val peopleJson =
    """[{"name":"Alice","age":30},{"name":"Bob","age":25},{"name":"Carol","age":40}]"""

  private val ranksJson =
    """[{"name":"a","rank":2},{"name":"b","rank":1},{"name":"c","rank":2}]"""

  private val bigIntJson =
    """[{"n":9007199254740993,"tag":"big"},{"n":9007199254740992,"tag":"small"}]"""

  private val boolJson =
    """[{"active":true,"name":"a"},{"active":false,"name":"b"}]"""

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

  private def state(dir: Path): ShellState =
    ShellState(cwd = dir.toString, prevCwd = None, home = dir.toString, now = 0L, color = false)

  private def run(script: String, dir: Path): IO[TabbyError, Value] =
    for {
      pipeline <- ZIO.fromEither(Parser.parseLine(script) match {
        case Right(Some(p)) => Right(p)
        case Right(None)    => Left(TabbyError.Parse("empty pipeline", 0))
        case Left(err)      => Left(err)
      })
      result <- tabbyshell.Executor.runPipeline(pipeline, state(dir))
    } yield result._1

  private def runWithFile(fileName: String, content: String, script: Path => String) =
    ZIO.scoped {
      for {
        dir <- tempDir
        path <- ZIO.attemptBlocking(write(dir, fileName, content))
        value <- run(script(path), dir).either
      } yield value
    }

  override def spec = suite("Executor")(
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
      test("last on an empty list fails with BadArg") {
        for {
          result <- runWithFile("empty.json", "[]", p => s"open \"$p\" | last")
        } yield assertTrue(result == Left(TabbyError.BadArg("last", "input is empty")))
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
      test("supports equality on bool columns") {
        for {
          result <- runWithFile(
            "bool.json",
            boolJson,
            p => s"open \"$p\" | where active == true | get name"
          )
        } yield assertTrue(result == Right(VList(List(VStr("a")))))
      },
      test("rejects ordering operators on bool columns") {
        for {
          result <- runWithFile("bool.json", boolJson, p => s"open \"$p\" | where active > true")
        } yield assertTrue(
          result == Left(
            TabbyError.BadArg("where", "operator '>' is not supported for bool values")
          )
        )
      }
    ),
    suite("select")(
      test("rejects duplicate requested columns with BadArg") {
        for {
          result <- runWithFile("people.json", peopleJson, p => s"open \"$p\" | select name name")
        } yield assertTrue(
          result == Left(TabbyError.BadArg("select", "duplicate column: name"))
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
          result == Left(TabbyError.BadArg("select", "duplicate column: age"))
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
      }
    )
  )
}
