package snap

import zio.*

import java.nio.file.Path

/** Process entry point (SPEC §7, CONTRACT §2/§5/§14).
  *
  * The process boundary is the only place that touches ambient state: the JVM working directory,
  * the process environment, and the real stdout/stderr byte streams. Everything is captured once
  * into a [[Commands.CmdEnv]] snapshot and handed to [[Commands.run]], which never fails and yields
  * the process exit code. Default ZIO loggers are removed so no framework log lines leak onto the
  * byte-exact streams the harness compares against.
  */
object Main extends ZIOAppDefault {

  // Netty probes for native transports via System.loadLibrary, which prints
  // restricted-method warnings on recent JDKs. We only need the NIO event loop,
  // so disable native transport to keep stderr byte-exact (CONTRACT §2, §12).
  // Set before any zio-http / Netty class is initialized.
  java.lang.System.setProperty("io.netty.transport.noNative", "true")

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers

  /** Build a [[Commands.CmdEnv]] from an environment lookup. Pure, so it is unit-testable. */
  def cmdEnv(getenv: String => Option[String], cwd: Path, isTty: Boolean): Commands.CmdEnv =
    Commands.CmdEnv(
      cwd = cwd,
      home = getenv("HOME").map(Path.of(_)),
      snapColor = getenv("SNAP_COLOR"),
      noColorPresent = getenv("NO_COLOR").isDefined,
      isTty = isTty
    )

  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] =
    for {
      args <- ZIOAppArgs.getArgs
      env <- ZIO.attempt(
        cmdEnv(
          getenv = name => Option(java.lang.System.getenv(name)),
          cwd = Path.of("").toAbsolutePath,
          isTty = java.lang.System.console() != null
        )
      )
      code <- Commands.run(args, env, Commands.Output.live)
      _ <- exit(ExitCode(code))
    } yield ()
}
