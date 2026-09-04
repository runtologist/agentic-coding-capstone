package tabbyshell

import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object MainSpec extends ZIOSpecDefault {

  private val esc = ""

  private def tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("tabbyshell-main-spec")))(
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

  private def write(dir: Path, name: String, content: String): Path = {
    val path = dir.resolve(name)
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    path
  }

  private def stateAt(dir: Path): ShellState =
    ShellState(
      cwd = dir.toString,
      prevCwd = None,
      home = dir.toString,
      now = 0L,
      color = false
    )

  private def mkState(cwd: String, home: String): ShellState =
    ShellState(cwd = cwd, prevCwd = None, home = home, now = 0L, color = false)

  private val renderOpts = RenderOpts(color = false, maxColWidth = 40, now = 0L)

  override def spec = suite("Main")(
    suite("parseArgs")(
      test("returns defaults for no args") {
        assertTrue(Main.parseArgs(Nil) == Right(Main.CliOptions()))
      },
      test("parses --version") {
        assertTrue(Main.parseArgs(List("--version")) == Right(Main.CliOptions(showVersion = true)))
      },
      test("parses --no-color") {
        assertTrue(Main.parseArgs(List("--no-color")) == Right(Main.CliOptions(noColor = true)))
      },
      test("parses --interactive") {
        assertTrue(
          Main.parseArgs(List("--interactive")) == Right(Main.CliOptions(interactive = true))
        )
      },
      test("parses --eval with a value") {
        assertTrue(
          Main.parseArgs(List("--eval", "ls")) == Right(Main.CliOptions(eval = Some("ls")))
        )
      },
      test("fails when --eval has no value") {
        assertTrue(Main.parseArgs(List("--eval")) == Left("--eval requires a value"))
      },
      test("parses --eval-file with a value") {
        assertTrue(
          Main.parseArgs(List("--eval-file", "main.tab")) ==
            Right(Main.CliOptions(evalFile = Some("main.tab")))
        )
      },
      test("fails when --eval-file has no value") {
        assertTrue(Main.parseArgs(List("--eval-file")) == Left("--eval-file requires a value"))
      },
      test("fails on unknown argument") {
        assertTrue(Main.parseArgs(List("--bogus")) == Left("unknown argument: --bogus"))
      },
      test("parses multiple flags combined") {
        val args = List("--version", "--no-color", "--interactive", "--eval", "ls")
        val expected = Main.CliOptions(
          showVersion = true,
          noColor = true,
          interactive = true,
          eval = Some("ls"),
          evalFile = None
        )
        assertTrue(Main.parseArgs(args) == Right(expected))
      },
      test("later values override earlier ones") {
        assertTrue(
          Main.parseArgs(List("--eval", "a", "--eval", "b")) ==
            Right(Main.CliOptions(eval = Some("b"))),
          Main.parseArgs(List("--eval-file", "a", "--eval-file", "b")) ==
            Right(Main.CliOptions(evalFile = Some("b")))
        )
      }
    ),
    suite("resolveNow")(
      test("returns fallback when TABBY_NOW is unset") {
        for {
          now <- Main.resolveNow(None, 42L)
        } yield assertTrue(now == 42L)
      },
      test("parses valid unix seconds") {
        for {
          now <- Main.resolveNow(Some("123"), 0L)
        } yield assertTrue(now == 123L)
      },
      test("trims whitespace before parsing") {
        for {
          now <- Main.resolveNow(Some("  456  "), 0L)
        } yield assertTrue(now == 456L)
      },
      test("fails with BadArg for invalid value") {
        for {
          result <- Main.resolveNow(Some("abc"), 0L).either
        } yield assertTrue(
          result == Left(TabbyError.BadArg("TABBY_NOW", "invalid unix seconds: abc"))
        )
      }
    ),
    suite("promptString")(
      test("renders ~ when cwd equals home") {
        assertTrue(Main.promptString(mkState("/home/u", "/home/u")) == "~ ❯ ")
      },
      test("renders ~/subpath when cwd is under home") {
        assertTrue(Main.promptString(mkState("/home/u/docs", "/home/u")) == "~/docs ❯ ")
      },
      test("does not treat similar prefixes as home") {
        assertTrue(
          Main.promptString(mkState("/home/username", "/home/user")) == "/home/username ❯ "
        )
      },
      test("renders absolute path when cwd is outside home") {
        assertTrue(Main.promptString(mkState("/etc", "/home/u")) == "/etc ❯ ")
      },
      test("handles home root case") {
        assertTrue(
          Main.promptString(mkState("/", "/")) == "~ ❯ ",
          Main.promptString(mkState("/etc", "/")) == "/etc ❯ "
        )
      }
    ),
    suite("error formatting")(
      test("formatError adds cross prefix and optional color") {
        assertTrue(
          Main.formatError(color = false, "boom") == "✗ boom",
          Main.formatError(color = true, "boom") == s"$esc[1;31m✗ boom$esc[0m"
        )
      },
      test("formatErrorLine wraps only when color is enabled and not already styled") {
        val alreadyStyled = s"$esc[1;31mboom$esc[0m"
        assertTrue(
          Main.formatErrorLine(color = false, "boom") == "boom",
          Main.formatErrorLine(color = true, "boom") == s"$esc[1;31mboom$esc[0m",
          Main.formatErrorLine(color = true, alreadyStyled) == alreadyStyled
        )
      }
    ),
    suite("readScript")(
      test("reads UTF-8 file content") {
        ZIO.scoped {
          for {
            dir <- tempDir
            path = write(dir, "script.tab", "pwd # café\n")
            script <- Main.readScript(path.toString)
          } yield assertTrue(script == "pwd # café\n")
        }
      },
      test("reads stdin when target is '-'") {
        for {
          _ <- TestConsole.feedLines("line1", "line2")
          script <- Main.readScript("-")
        } yield assertTrue(script == "line1\nline2")
      },
      test("missing file fails with eval-file IoError") {
        ZIO.scoped {
          for {
            dir <- tempDir
            missing = dir.resolve("missing.tab").toString
            result <- Main.readScript(missing).either
          } yield assertTrue(
            result match {
              case Left(TabbyError.IoError("eval-file", osMessage)) =>
                osMessage.contains(missing) && osMessage.endsWith("No such file or directory")
              case _ => false
            }
          )
        }
      }
    ),
    suite("runScript")(
      test("empty, comment-only, and blank-line scripts succeed silently") {
        ZIO.scoped {
          for {
            dir <- tempDir
            st = stateAt(dir)
            c1 <- Main.runScript("", st, renderOpts)
            c2 <- Main.runScript("# only a comment", st, renderOpts)
            c3 <- Main.runScript("\n   \n\n", st, renderOpts)
            out <- TestConsole.output
            err <- TestConsole.outputErr
          } yield assertTrue(
            c1 == ExitCode.success,
            c2 == ExitCode.success,
            c3 == ExitCode.success,
            out.isEmpty,
            err.isEmpty
          )
        }
      },
      test("parse error returns ExitCode 1") {
        ZIO.scoped {
          for {
            dir <- tempDir
            code <- Main.runScript("42", stateAt(dir), renderOpts)
            err <- TestConsole.outputErr
          } yield assertTrue(
            code == ExitCode(1),
            err.mkString.startsWith("✗ parse error")
          )
        }
      },
      test("runtime error returns ExitCode 1") {
        ZIO.scoped {
          for {
            dir <- tempDir
            code <- Main.runScript("cd missing-dir", stateAt(dir), renderOpts)
            err <- TestConsole.outputErr
          } yield assertTrue(
            code == ExitCode(1),
            err.mkString.contains("cd: missing-dir: No such file or directory")
          )
        }
      },
      test("successful pipeline with save writes file and exits successfully") {
        ZIO.scoped {
          for {
            dir <- tempDir
            in = write(dir, "in.txt", "hello")
            outPath = dir.resolve("out.txt")
            script = s"""cat "${in.toString}" | save "${outPath.toString}""""
            code <- Main.runScript(script, stateAt(dir), renderOpts)
            saved <- ZIO.attemptBlocking(
              new String(Files.readAllBytes(outPath), StandardCharsets.UTF_8)
            )
            out <- TestConsole.output
          } yield assertTrue(
            code == ExitCode.success,
            saved == "hello",
            out == Vector("\n")
          )
        }
      },
      test("multiple statements thread ShellState") {
        ZIO.scoped {
          for {
            dir <- tempDir
            sub = dir.resolve("sub")
            _ <- ZIO.attemptBlocking(Files.createDirectories(sub))
            script = s"cd \"${sub.toString}\"\npwd"
            code <- Main.runScript(script, stateAt(dir), renderOpts)
            out <- TestConsole.output
          } yield assertTrue(
            code == ExitCode.success,
            out == Vector("\n", s"${sub.toString}\n")
          )
        }
      },
      test("normalizes CRLF line endings") {
        ZIO.scoped {
          for {
            dir <- tempDir
            code <- Main.runScript("# comment\r\npwd", stateAt(dir), renderOpts)
            out <- TestConsole.output
          } yield assertTrue(
            code == ExitCode.success,
            out == Vector(s"${dir.toString}\n")
          )
        }
      }
    ),
    suite("program")(
      test("--version prints version and exits successfully") {
        for {
          code <- Main.program(List("--version"))
          out <- TestConsole.output
        } yield assertTrue(
          code == ExitCode.success,
          out == Vector(s"${Version.line}\n")
        )
      },
      test("unknown argument exits with 2") {
        for {
          code <- Main.program(List("--bogus"))
          err <- TestConsole.outputErr
        } yield assertTrue(
          code == ExitCode(2),
          err.mkString.contains("unknown argument: --bogus")
        )
      },
      test("--eval executes a script and exits successfully") {
        ZIO.scoped {
          for {
            dir <- tempDir
            in = write(dir, "eval-input.txt", "eval-content")
            _ <- TestSystem.putEnv("HOME", dir.toString)
            _ <- TestSystem.putEnv("TABBY_NOW", "1000")
            _ <- TestSystem.putEnv("NO_COLOR", "1")
            code <- Main.program(List("--eval", s"""cat "${in.toString}""""))
            out <- TestConsole.output
          } yield assertTrue(
            code == ExitCode.success,
            out == Vector("eval-content\n")
          )
        }
      },
      test("--eval-file executes a script file and exits successfully") {
        ZIO.scoped {
          for {
            dir <- tempDir
            in = write(dir, "data.txt", "file-content")
            script = write(dir, "script.tab", s"""cat "${in.toString}"""")
            _ <- TestSystem.putEnv("HOME", dir.toString)
            _ <- TestSystem.putEnv("TABBY_NOW", "1000")
            _ <- TestSystem.putEnv("NO_COLOR", "1")
            code <- Main.program(List("--eval-file", script.toString))
            out <- TestConsole.output
          } yield assertTrue(
            code == ExitCode.success,
            out == Vector("file-content\n")
          )
        }
      },
      test("--eval-file with missing file exits with 1") {
        ZIO.scoped {
          for {
            dir <- tempDir
            missing = dir.resolve("missing.tab").toString
            _ <- TestSystem.putEnv("HOME", dir.toString)
            _ <- TestSystem.putEnv("NO_COLOR", "1")
            code <- Main.program(List("--eval-file", missing))
            err <- TestConsole.outputErr
            message = err.mkString
          } yield assertTrue(
            code == ExitCode(1),
            message.startsWith("✗ eval-file:"),
            message.contains(missing),
            message.contains("No such file or directory")
          )
        }
      },
      test("invalid TABBY_NOW exits with 2") {
        ZIO.scoped {
          for {
            dir <- tempDir
            _ <- TestSystem.putEnv("HOME", dir.toString)
            _ <- TestSystem.putEnv("TABBY_NOW", "not-a-number")
            code <- Main.program(List("--eval", "pwd"))
            err <- TestConsole.outputErr
          } yield assertTrue(
            code == ExitCode(2),
            err.mkString.contains("TABBY_NOW: invalid unix seconds: not-a-number")
          )
        }
      }
    )
  )
}
