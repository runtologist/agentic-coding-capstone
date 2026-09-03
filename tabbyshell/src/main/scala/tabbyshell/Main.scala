package tabbyshell

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object Main extends ZIOAppDefault:

  private def readStdin: Task[String] =
    ZIO.attempt {
      val source = scala.io.Source.stdin
      try source.mkString
      finally source.close()
    }

  private def readFile(path: String): Task[String] =
    ZIO.attempt {
      new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
    }

  private def currentWorkingDirectory: UIO[String] =
    ZIO.succeed(Paths.get("").toAbsolutePath.toString)

  private def evaluateLine(line: String): UIO[ExitCode] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then ZIO.succeed(ExitCode.success)
    else
      trimmed match
        case "pwd" =>
          (for {
            cwd <- currentWorkingDirectory
            _ <- Console.printLine(cwd).orDie
          } yield ExitCode.success).orDie
        case other =>
          (Console
            .printLineError(s"tabbyshell scaffold: not implemented: $other")
            .orDie as ExitCode(1))

  private def evalFile(target: String): UIO[ExitCode] =
    (for {
      script <- if target == "-" then readStdin else readFile(target)
      lines = script.split("\n", -1).toList
      exitCode <- ZIO.foldLeft(lines)(ExitCode.success) { (current, line) =>
        if current == ExitCode.success then evaluateLine(line)
        else ZIO.succeed(current)
      }
    } yield exitCode).orDie

  override def run: ZIO[Any & ZIOAppArgs & Scope, Any, Any] =
    for {
      args <- getArgs
      argList = args.toList
      exitCode <-
        if argList.contains("--version") then
          Console.printLine(Version.line).orDie as ExitCode.success
        else
          val evalFileIndex = argList.indexOf("--eval-file")
          if evalFileIndex >= 0 && argList.length > evalFileIndex + 1 then
            evalFile(argList(evalFileIndex + 1))
          else
            Console
              .printLineError("tabbyshell scaffold: unsupported invocation")
              .orDie as ExitCode(2)
    } yield exitCode
