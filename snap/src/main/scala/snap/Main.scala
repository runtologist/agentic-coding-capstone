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
      isTty = isTty,
      snapDebug = getenv("SNAP_DEBUG").isDefined
    )

  /** Decide whether the JVM must be re-executed so filenames decode as UTF-8 (F-utf8).
    *
    * Under `LANG=C`/`LC_ALL=C` the JVM derives `sun.jnu.encoding` from the locale (ASCII on Linux),
    * mangling UTF-8 filenames. JDK 25 ignores both the `-Dsun.jnu.encoding` command-line flag and
    * runtime `setProperty`, so the only fix is a fresh JVM launched under `C.UTF-8`. Re-exec is
    * needed iff the current jnu encoding is not UTF-8, the `SNAP_JNU_REEXEC` recursion guard is
    * unset, and the app runs from an assembled `.jar` (never under `sbt run`/`sbt test`). Pure, so
    * it is unit-testable.
    */
  private[snap] def jnuNeedsReexec(
      jnuEncoding: Option[String],
      guardEnv: Option[String],
      jarPath: Option[String]
  ): Boolean = {
    val encodingIsUtf8 = jnuEncoding match {
      case None      => true // assume fine when the property is unavailable
      case Some(raw) => raw.toUpperCase.replace("-", "").replace("_", "").contains("UTF8")
    }
    val guardSet = guardEnv.isDefined
    val runningFromJar = jarPath.exists(_.endsWith(".jar"))
    !encodingIsUtf8 && !guardSet && runningFromJar
  }

  /** Build the re-exec command vector for a UTF-8 JVM relaunch (F-utf8). Pure, so unit-testable. */
  private[snap] def reexecCommand(
      javaHome: String,
      jarPath: String,
      args: Seq[String]
  ): List[String] =
    List(
      s"$javaHome/bin/java",
      "-Dsun.misc.unsafe.memory.access=allow",
      "-Dfile.encoding=UTF-8",
      "-jar",
      jarPath
    ) ++ args

  /** Environment overrides for the re-exec child (F-utf8): force a UTF-8 locale so the child JVM
    * derives `sun.jnu.encoding=UTF-8`, and set the recursion guard. Pure, so unit-testable.
    */
  private[snap] def reexecEnv: Map[String, String] =
    Map("LANG" -> "C.UTF-8", "LC_ALL" -> "C.UTF-8", "SNAP_JNU_REEXEC" -> "1")

  /** Guarded JVM re-exec for UTF-8 filename handling (F-utf8).
    *
    * If [[jnuNeedsReexec]] decides a relaunch is required, spawns the child with inherited IO
    * (byte-exact pass-through, the parent prints nothing), overrides `LANG`/`LC_ALL` to `C.UTF-8`,
    * sets the `SNAP_JNU_REEXEC` guard, and waits for the child's exit code. Documented raw-JVM
    * exception mirroring [[Commands.installSignalHandlers]]: the harness signals the whole process
    * group, so the parent installs no-op TERM/INT handlers before spawning; the child receives the
    * signal directly and performs its own graceful shutdown, while the parent survives to relay the
    * child's exit code. Returns [[None]] when re-exec is not needed or spawning fails, in which
    * case the caller degrades to normal execution.
    */
  private def attemptJnuReexec(args: Seq[String]): Option[Int] = {
    val jnuEncoding = Option(java.lang.System.getProperty("sun.jnu.encoding"))
    val guardEnv = Option(java.lang.System.getenv("SNAP_JNU_REEXEC"))
    val jarPath =
      try
        Option(getClass.getProtectionDomain.getCodeSource)
          .flatMap(cs => Option(cs.getLocation))
          .map(l => java.nio.file.Paths.get(l.toURI).toString)
      catch { case _: Throwable => None }

    if (!jnuNeedsReexec(jnuEncoding, guardEnv, jarPath)) return None

    val javaHome = java.lang.System.getProperty("java.home")
    val command = reexecCommand(javaHome, jarPath.get, args)
    try {
      val builder = new ProcessBuilder(command*)
      builder.inheritIO()
      val childEnv = builder.environment()
      reexecEnv.foreach { case (k, v) => childEnv.put(k, v) }
      // No-op TERM/INT handlers: the harness signals the whole process group, so the child
      // handles shutdown itself and the parent must survive to relay its exit code.
      val noop: sun.misc.SignalHandler = (_: sun.misc.Signal) => ()
      sun.misc.Signal.handle(new sun.misc.Signal("TERM"), noop)
      sun.misc.Signal.handle(new sun.misc.Signal("INT"), noop)
      val child = builder.start()
      Some(child.waitFor())
    } catch {
      case _: java.io.IOException => None // degrade gracefully to normal execution
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        None
    }
  }

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
      _ <-
        if (code == Jnu.ReexecCode)
          ZIO.attemptBlocking(attemptJnuReexec(args)).flatMap {
            case Some(childCode) => exit(ExitCode(childCode))
            case None =>
              ZIO.attemptBlocking {
                java.lang.System.err.print("snap: internal error\n")
              } *> exit(ExitCode(2))
          }
        else exit(ExitCode(code))
    } yield ()
}
