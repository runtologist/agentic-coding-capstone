package tabbyshell

import zio.{ExitCode, IO, Scope, UIO, ZIO, ZIOAppArgs, ZIOAppDefault}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** TabbyShell entry point.
  *
  * The process exit code is enforced with [[zio.ZIOApp.exit]]. Note that merely *returning* an
  * `ExitCode` value from `run` is not enough: `ZIOAppPlatformSpecific.main` maps every successful
  * run to `ExitCode.success`, so non-zero codes must go through `exit`, which calls `Platform.exit`
  * (i.e. `System.exit`) with the requested code.
  */
object Main extends ZIOAppDefault {

  private final case class CliOptions(
      showVersion: Boolean = false,
      noColor: Boolean = false,
      interactive: Boolean = false,
      eval: Option[String] = None,
      evalFile: Option[String] = None
  )

  private val esc = "\u001b"

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    for {
      args <- getArgs
      code <- program(args.toList)
      _ <- exit(code) // terminates the JVM with the requested exit code
    } yield code

  private def program(args: List[String]): UIO[ExitCode] =
    parseArgs(args) match {
      case Left(message) =>
        printErrorLine(color = false, message).as(ExitCode(2))
      case Right(options) =>
        runOptions(options)
    }

  private def parseArgs(args: List[String]): Either[String, CliOptions] = {
    def loop(remaining: List[String], options: CliOptions): Either[String, CliOptions] =
      remaining match {
        case Nil =>
          Right(options)
        case "--version" :: tail =>
          loop(tail, options.copy(showVersion = true))
        case "--no-color" :: tail =>
          loop(tail, options.copy(noColor = true))
        case "--interactive" :: tail =>
          loop(tail, options.copy(interactive = true))
        case "--eval" :: value :: tail =>
          loop(tail, options.copy(eval = Some(value)))
        case "--eval" :: Nil =>
          Left("--eval requires a value")
        case "--eval-file" :: value :: tail =>
          loop(tail, options.copy(evalFile = Some(value)))
        case "--eval-file" :: Nil =>
          Left("--eval-file requires a value")
        case other :: _ =>
          Left(s"unknown argument: $other")
      }

    loop(args, CliOptions())
  }

  private def runOptions(options: CliOptions): UIO[ExitCode] = {
    if (options.showVersion) {
      printOut(Version.line + "\n").as(ExitCode.success)
    } else {
      for {
        env <- ZIO.attempt(java.lang.System.getenv()).orDie
        cwd <- ZIO.attempt(Paths.get("").toAbsolutePath.normalize.toString).orDie
        isTty <- ZIO.attempt(java.lang.System.console() != null).orDie
        currentTime <- ZIO.attempt(System.currentTimeMillis() / 1000L).orDie
        state = {
          val home =
            Option(env.get("HOME"))
              .filter(_.nonEmpty)
              .orElse(Option(java.lang.System.getProperty("user.home")))
              .getOrElse("/")

          val now =
            Option(env.get("TABBY_NOW"))
              .flatMap(value => Try(value.toLong).toOption)
              .getOrElse(currentTime)

          val noColorEnv = Option(env.get("NO_COLOR")).exists(_.nonEmpty)
          val color = !options.noColor && !noColorEnv && isTty

          ShellState(
            cwd = cwd,
            prevCwd = None,
            home = home,
            now = now,
            color = color
          )
        }
        renderOpts = RenderOpts(color = state.color, maxColWidth = 40, now = state.now)
        exitCode <- (options.eval, options.evalFile) match {
          case (Some(script), _) =>
            runScript(script, state, renderOpts)

          case (None, Some(target)) =>
            readScript(target).either.flatMap {
              case Left(error) =>
                printError(renderOpts.color, error.message).as(ExitCode(1))
              case Right(script) =>
                runScript(script, state, renderOpts)
            }

          case (None, None) =>
            if (options.interactive || isTty) {
              repl(state, renderOpts)
            } else {
              readScript("-").either.flatMap {
                case Left(error) =>
                  printError(renderOpts.color, error.message).as(ExitCode(1))
                case Right(script) =>
                  runScript(script, state, renderOpts)
              }
            }
        }
      } yield exitCode
    }
  }

  private def runScript(
      script: String,
      initialState: ShellState,
      opts: RenderOpts
  ): UIO[ExitCode] = {
    val normalized = script.replace("\r\n", "\n").replace('\r', '\n')
    val joined = Parser.joinContinuations(normalized)
    val lines = joined.split("\n", -1).toList

    def loop(remaining: List[String], state: ShellState): UIO[ExitCode] =
      remaining match {
        case Nil =>
          ZIO.succeed(ExitCode.success)

        case line :: rest =>
          Parser.parseLine(line) match {
            case Left(error) =>
              printError(opts.color, error.message).as(ExitCode(1))

            case Right(None) =>
              loop(rest, state)

            case Right(Some(pipeline)) =>
              Executor
                .runPipeline(pipeline, state)
                .either
                .flatMap {
                  case Left(error) =>
                    printError(opts.color, error.message).as(ExitCode(1))
                  case Right((value, nextState)) =>
                    printValue(value, opts) *> loop(rest, nextState)
                }
                .catchAllCause { cause =>
                  printError(opts.color, s"internal error: ${cause.prettyPrint}")
                    .as(ExitCode(2))
                }
          }
      }

    loop(lines, initialState)
  }

  private def readScript(target: String): IO[TabbyError, String] = {
    if (target == "-") {
      ZIO
        .attemptBlocking {
          val source = scala.io.Source.stdin
          try source.mkString
          finally source.close()
        }
        .mapError(e => TabbyError.IoError("eval-file", ioMessage(e)))
    } else {
      ZIO
        .attemptBlocking(
          new String(Files.readAllBytes(Paths.get(target)), StandardCharsets.UTF_8)
        )
        .mapError(e => TabbyError.IoError("eval-file", ioMessage(e)))
    }
  }

  private def printValue(value: Value, opts: RenderOpts): UIO[Unit] =
    printOut(Render.output(value, opts))

  private def printOut(text: String): UIO[Unit] =
    ZIO.attemptBlocking(System.out.print(text)).orDie

  private def printError(color: Boolean, message: String): UIO[Unit] = {
    val messageWithPrefix = s"✗ $message"
    val styled =
      if (color) s"$esc[1;31m$messageWithPrefix$esc[0m"
      else messageWithPrefix
    printErrorLine(color, styled)
  }

  private def printErrorLine(color: Boolean, message: String): UIO[Unit] = {
    val styled =
      if (color && !message.startsWith(esc)) s"$esc[1;31m$message$esc[0m"
      else message
    ZIO.attemptBlocking(System.err.println(styled)).orDie
  }

  private def repl(initialState: ShellState, opts: RenderOpts): UIO[ExitCode] = {
    def loop(state: ShellState): UIO[ExitCode] =
      readLogicalLine(promptString(state)).flatMap {
        case None =>
          goodbye(opts.color).as(ExitCode.success)

        case Some(line) =>
          val trimmed = line.trim
          if (trimmed == "exit" || trimmed == "quit") {
            goodbye(opts.color).as(ExitCode.success)
          } else if (trimmed.isEmpty) {
            loop(state)
          } else {
            appendHistory(state, line) *>
              (Parser.parseLine(line) match {
                case Left(error) =>
                  printError(opts.color, error.message) *> loop(state)

                case Right(None) =>
                  loop(state)

                case Right(Some(pipeline)) =>
                  Executor
                    .runPipeline(pipeline, state)
                    .either
                    .flatMap {
                      case Left(error) =>
                        printError(opts.color, error.message) *> loop(state)
                      case Right((value, nextState)) =>
                        printValue(value, opts) *> loop(nextState)
                    }
                    .catchAllCause { cause =>
                      printError(opts.color, s"internal error: ${cause.prettyPrint}") *> loop(
                        state
                      )
                    }
              })
          }
      }

    for {
      _ <- printBanner(initialState)
      _ <- printOut(s"${Version.line} — type 'exit' or Ctrl-D to quit\n")
      code <- loop(initialState)
    } yield code
  }

  private def promptString(state: ShellState): String = {
    val short =
      if (state.cwd == state.home) "~"
      else if (state.home != "/" && state.cwd.startsWith(state.home + "/"))
        "~" + state.cwd.substring(state.home.length)
      else state.cwd

    short + " ❯ "
  }

  private def endsWithContinuation(line: String): Boolean = {
    var j = line.length - 1
    while (j >= 0 && (line.charAt(j) == ' ' || line.charAt(j) == '\t')) j -= 1
    j >= 0 && line.charAt(j) == '\\'
  }

  private def stripTrailingContinuation(line: String): String = {
    var j = line.length - 1
    while (j >= 0 && (line.charAt(j) == ' ' || line.charAt(j) == '\t')) j -= 1
    if (j >= 0 && line.charAt(j) == '\\') line.substring(0, j)
    else line
  }

  private def readLogicalLine(initialPrompt: String): UIO[Option[String]] = {
    def loop(prompt: String, buffer: String): UIO[Option[String]] =
      ZIO
        .attemptBlocking(Option(scala.io.StdIn.readLine(prompt)))
        .orDie
        .flatMap {
          case None =>
            ZIO.succeed(if (buffer.isEmpty) None else Some(buffer))

          case Some(line) =>
            if (endsWithContinuation(line)) {
              loop("  ", buffer + stripTrailingContinuation(line) + " ")
            } else {
              ZIO.succeed(Some(buffer + line))
            }
        }

    loop(initialPrompt, "")
  }

  private def appendHistory(state: ShellState, line: String): UIO[Unit] =
    ZIO.attemptBlocking {
      val trimmed = line.trim
      if (trimmed.nonEmpty) {
        val path = Paths.get(state.home, ".tabbyshell_history")
        val existing =
          if (Files.exists(path))
            Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList
          else Nil

        val updated = (existing ++ List(trimmed)).takeRight(1000)
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.write(path, updated.asJava, StandardCharsets.UTF_8)
        ()
      }
    }.ignore

  private def findBannerPath(state: ShellState): Option[Path] = {
    val fromEnv: Option[Path] =
      Option(java.lang.System.getenv("TABBY_PROJECT_ROOT"))
        .map(root => Paths.get(root).resolve("banner.txt"))
        .filter(Files.exists(_))

    def searchUp(start: Path): Option[Path] = {
      if (start == null) None
      else {
        val candidate = start.resolve("banner.txt")
        if (Files.exists(candidate)) Some(candidate)
        else searchUp(start.getParent)
      }
    }

    fromEnv.orElse(searchUp(Paths.get(state.cwd)))
  }

  private def printBanner(state: ShellState): UIO[Unit] =
    ZIO
      .attemptBlocking(findBannerPath(state))
      .orDie
      .flatMap {
        case Some(path) =>
          ZIO
            .attemptBlocking(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
            .orDie
            .flatMap(content => printOut(content))
        case None =>
          ZIO.unit
      }

  private def goodbye(color: Boolean): UIO[Unit] = {
    val message = "goodbye."
    val styled =
      if (color) s"$esc[2;90m$message$esc[0m"
      else message
    printOut(styled + "\n")
  }

  private def ioMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}
