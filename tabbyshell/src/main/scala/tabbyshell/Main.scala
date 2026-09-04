package tabbyshell

import zio.{
  Clock,
  Console,
  ExitCode,
  IO,
  Scope,
  System => ZSystem,
  UIO,
  ZIO,
  ZIOAppArgs,
  ZIOAppDefault
}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** TabbyShell entry point.
  *
  * The process exit code is enforced with [[zio.ZIOApp.exit]]. Note that merely *returning* an
  * `ExitCode` value from `run` is not enough: `ZIOAppPlatformSpecific.main` maps every successful
  * run to `ExitCode.success`, so non-zero codes must go through `exit`, which calls `Platform.exit`
  * (i.e. `System.exit`) with the requested code.
  */
object Main extends ZIOAppDefault {

  private[tabbyshell] final case class CliOptions(
      showVersion: Boolean = false,
      noColor: Boolean = false,
      interactive: Boolean = false,
      eval: Option[String] = None,
      evalFile: Option[String] = None
  )

  private[tabbyshell] val esc = "\u001b"

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    for {
      args <- getArgs
      code <- program(args.toList)
      _ <- exit(code) // terminates the JVM with the requested exit code
    } yield code

  private[tabbyshell] def program(args: List[String]): UIO[ExitCode] =
    parseArgs(args) match {
      case Left(message) =>
        printErrorLine(color = false, message).as(ExitCode(2))
      case Right(options) =>
        runOptions(options)
    }

  private[tabbyshell] def parseArgs(args: List[String]): Either[String, CliOptions] = {
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
      setupState(options).either.flatMap {
        case Left(error) =>
          printErrorLine(color = false, error.message).as(ExitCode(2))
        case Right((state, renderOpts, isTty)) =>
          (options.eval, options.evalFile) match {
            case (Some(script), _) =>
              // Spec §8: --eval always renders without color.
              runScript(script, state, renderOpts.copy(color = false))

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
      }
    }
  }

  private def setupState(
      options: CliOptions
  ): IO[TabbyError, (ShellState, RenderOpts, Boolean)] =
    for {
      homeEnv <- ZSystem.env("HOME").orDie
      tabbyNowEnv <- ZSystem.env("TABBY_NOW").orDie
      noColorEnv <- ZSystem.env("NO_COLOR").orDie
      userHomeProp <- ZSystem.property("user.home").orDie
      cwd <- ZIO.attempt(Paths.get("").toAbsolutePath.normalize.toString).orDie
      // ZIO has no built-in service for TTY detection; this raw call is
      // deliberate and remains wrapped in ZIO.attempt.
      isTty <- ZIO.attempt(java.lang.System.console() != null).orDie
      currentTime <- Clock.currentTime(TimeUnit.SECONDS)
      now <- resolveNow(tabbyNowEnv, currentTime)
      home = homeEnv.filter(_.nonEmpty).orElse(userHomeProp).getOrElse("/")
      color = !options.noColor && !noColorEnv.exists(_.nonEmpty) && isTty
      state = ShellState(
        cwd = cwd,
        prevCwd = None,
        home = home,
        now = now,
        color = color
      )
      renderOpts = RenderOpts(color = state.color, maxColWidth = 40, now = state.now)
    } yield (state, renderOpts, isTty)

  private[tabbyshell] def resolveNow(
      tabbyNowEnv: Option[String],
      fallback: Long
  ): IO[TabbyError, Long] =
    tabbyNowEnv match {
      case None => ZIO.succeed(fallback)
      case Some(raw) =>
        raw.trim.toLongOption match {
          case Some(value) => ZIO.succeed(value)
          case None =>
            ZIO.fail(TabbyError.BadArg("TABBY_NOW", s"invalid unix seconds: $raw"))
        }
    }

  private[tabbyshell] def runScript(
      script: String,
      initialState: ShellState,
      opts: RenderOpts
  ): UIO[ExitCode] = {
    val normalized = script.replace("\r\n", "\n").replace('\r', '\n')
    val lines = Parser.joinContinuations(normalized)

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

  private[tabbyshell] def readScript(target: String): IO[TabbyError, String] = {
    if (target == "-") {
      // Idiomatic ZIO: read stdin through the Console service rather than
      // touching scala.io.Source.stdin inside a blocking attempt.
      readAllStdin
        .mapError(e => TabbyError.IoError("eval-file", TabbyError.ioMessage(e)))
    } else {
      ZIO
        .attemptBlocking(
          new String(Files.readAllBytes(Paths.get(target)), StandardCharsets.UTF_8)
        )
        .mapError(e => TabbyError.IoError("eval-file", TabbyError.ioMessage(e)))
    }
  }

  /** Reads all of stdin via the ZIO Console service, line by line. ZIO's `Console.readLine` signals
    * end-of-input by failing with `java.io.EOFException` (rather than returning `null`), so that
    * typed failure is treated as the normal end of the stream. Lines are rejoined with newlines so
    * downstream continuation/comment handling is unchanged.
    */
  private def readAllStdin: IO[java.io.IOException, String] = {
    def loop(acc: List[String]): IO[java.io.IOException, List[String]] =
      Console.readLine
        .map(line => Option(line))
        .catchSome { case _: java.io.EOFException => ZIO.succeed(None) }
        .flatMap {
          case None       => ZIO.succeed(acc.reverse)
          case Some(line) => loop(line :: acc)
        }
    loop(Nil).map(_.mkString("\n"))
  }

  private def printValue(value: Value, opts: RenderOpts): UIO[Unit] =
    printOut(Render.output(value, opts))

  private def printOut(text: String): UIO[Unit] =
    Console.print(text).orDie

  private[tabbyshell] def formatError(color: Boolean, message: String): String = {
    val messageWithPrefix = s"✗ $message"
    if (color) s"$esc[1;31m$messageWithPrefix$esc[0m"
    else messageWithPrefix
  }

  private[tabbyshell] def formatErrorLine(color: Boolean, message: String): String =
    if (color && !message.startsWith(esc)) s"$esc[1;31m$message$esc[0m"
    else message

  private def printError(color: Boolean, message: String): UIO[Unit] =
    printErrorLine(color, formatError(color, message))

  private def printErrorLine(color: Boolean, message: String): UIO[Unit] =
    Console.printLineError(formatErrorLine(color, message)).orDie

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

  private[tabbyshell] def promptString(state: ShellState): String = {
    val short =
      if (state.cwd == state.home) "~"
      else if (state.home != "/" && state.cwd.startsWith(state.home + "/"))
        "~" + state.cwd.substring(state.home.length)
      else state.cwd

    short + " ❯ "
  }

  private def readLogicalLine(initialPrompt: String): UIO[Option[String]] = {
    def loop(prompt: String, buffer: String): UIO[Option[String]] =
      // Idiomatic ZIO: use the Console service's readLine (which prints the
      // prompt) instead of scala.io.StdIn.readLine. ZIO signals end-of-input by
      // failing with java.io.EOFException, which is mapped to None (EOF) here.
      Console
        .readLine(prompt)
        .map(line => Option(line))
        .catchSome { case _: java.io.EOFException => ZIO.succeed(None) }
        .orDie
        .flatMap {
          case None =>
            ZIO.succeed(if (buffer.isEmpty) None else Some(buffer))

          case Some(line) =>
            // Spec §7.3: continuation only when the backslash is the very last
            // character; drop it and keep a newline in the buffer.
            if (line.endsWith("\\")) {
              loop("  ", buffer + line.dropRight(1) + "\n")
            } else {
              ZIO.succeed(Some(buffer + line))
            }
        }

    loop(initialPrompt, "")
  }

  private def appendHistory(state: ShellState, line: String): UIO[Unit] =
    ZIO.attemptBlocking {
      // Logical lines may contain embedded newlines from continuations;
      // flatten them so the history file stays one-entry-per-line.
      val trimmed = line.replace('\n', ' ').trim
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

  private def findBannerPath(state: ShellState): UIO[Option[Path]] =
    ZSystem.env("TABBY_PROJECT_ROOT").orDie.map { envRoot =>
      val fromEnv: Option[Path] =
        envRoot
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
    findBannerPath(state).flatMap {
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
}
