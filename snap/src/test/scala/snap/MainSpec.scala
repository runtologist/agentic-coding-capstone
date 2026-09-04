package snap

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Thin tests for the process entry point wiring: environment snapshot mapping in [[Main.cmdEnv]]
  * and the arg-vector → (exit code, stdout, stderr) table that [[Commands.run]] drives from
  * `Main.run` (CONTRACT §2, §5, §14).
  */
object MainSpec extends ZIOSpecDefault {

  private val esc = "\u001b"

  private def tempDir(tag: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory(s"l7-main-$tag"))
    )(dir =>
      ZIO.attemptBlocking {
        if (Files.exists(dir)) {
          val stream = Files.walk(dir)
          try {
            stream
              .iterator()
              .asScala
              .toVector
              .sortBy(_.getNameCount)(Ordering[Int].reverse)
              .foreach(p =>
                try Files.deleteIfExists(p)
                catch { case _: Throwable => () }
              )
          } finally stream.close()
        }
      }.ignoreLogged
    )

  private def envFor(
      cwd: Path,
      home: Option[Path] = None,
      snapColor: Option[String] = None,
      noColorPresent: Boolean = true,
      isTty: Boolean = false
  ): Commands.CmdEnv =
    Commands.CmdEnv(cwd, home, snapColor, noColorPresent, isTty)

  private def runCli(args: String*)(env: Commands.CmdEnv): UIO[(Int, String, String)] =
    for {
      cap <- Commands.Output.captured
      code <- Commands.run(args, env, cap.output)
      out <- cap.stdout
      err <- cap.stderr
    } yield (code, out, err)

  def spec = suite("Main wiring")(
    suite("cmdEnv snapshot (CONTRACT §14)")(
      test("maps HOME, SNAP_COLOR and NO_COLOR from the environment lookup") {
        val vars = Map(
          "HOME" -> "/home/user",
          "SNAP_COLOR" -> "always",
          "NO_COLOR" -> "1"
        )
        val env = Main.cmdEnv(vars.get, Path.of("/tmp/wd"), isTty = true)
        assertTrue(
          env.cwd == Path.of("/tmp/wd"),
          env.home.contains(Path.of("/home/user")),
          env.snapColor.contains("always"),
          env.noColorPresent,
          env.isTty
        )
      },
      test("treats missing HOME as None and absent NO_COLOR as false") {
        val env = Main.cmdEnv(_ => None, Path.of("/tmp/wd"), isTty = false)
        assertTrue(
          env.home.isEmpty,
          env.snapColor.isEmpty,
          !env.noColorPresent,
          !env.isTty
        )
      },
      test("NO_COLOR present with an empty string still counts as present (test 28)") {
        val env =
          Main.cmdEnv(k => if (k == "NO_COLOR") Some("") else None, Path.of("/tmp"), isTty = true)
        assertTrue(env.noColorPresent)
      }
    ),
    suite("argument vector → exit/stream table")(
      test("--version exits 0 with the plain version line (test 14)") {
        ZIO.scoped {
          for {
            base <- tempDir("main-version")
            r <- runCli("--version")(envFor(base))
          } yield assertTrue(r == ((0, "snap 1.0.0\n", "")))
        }
      },
      test("invalid SNAP_COLOR is rejected plain before command execution (test 28)") {
        ZIO.scoped {
          for {
            base <- tempDir("main-color")
            r <- runCli("status")(envFor(base, snapColor = Some("sometimes"), isTty = true))
          } yield assertTrue(
            r == ((1, "", "snap: SNAP_COLOR must be auto, always, or never\n"))
          )
        }
      },
      test("unknown command exits 1 with the generic grammar error (test 14)") {
        ZIO.scoped {
          for {
            base <- tempDir("main-unknown")
            r <- runCli("unknown")(envFor(base))
          } yield assertTrue(r == ((1, "", "snap: invalid command or arguments\n")))
        }
      },
      test("status outside a repository exits 1 with not-a-repo error (test 14)") {
        ZIO.scoped {
          for {
            base <- tempDir("main-norepo")
            r <- runCli("status")(envFor(base))
          } yield assertTrue(r == ((1, "", "snap: not a Snap repository\n")))
        }
      },
      test("tty flag drives terminal presentation under auto mode") {
        ZIO.scoped {
          for {
            base <- tempDir("main-tty")
            r <- runCli("--version")(envFor(base, noColorPresent = false, isTty = true))
          } yield assertTrue(r == ((0, s"${esc}[1msnap 1.0.0${esc}[0m\n", "")))
        }
      },
      test("non-tty under auto mode stays plain") {
        ZIO.scoped {
          for {
            base <- tempDir("main-notty")
            r <- runCli("--version")(envFor(base, noColorPresent = false, isTty = false))
          } yield assertTrue(r == ((0, "snap 1.0.0\n", "")))
        }
      }
    ),
    suite("jnuNeedsReexec decision (F-utf8)")(
      test("ASCII encoding + no guard + jar path → re-exec needed") {
        assertTrue(
          Main.jnuNeedsReexec(Some("ANSI_X3.4-1968"), None, Some("/app/snap-assembly-0.1.0.jar"))
        )
      },
      test("UTF-8 encoding → no re-exec") {
        assertTrue(
          !Main.jnuNeedsReexec(Some("UTF-8"), None, Some("/app/snap.jar"))
        )
      },
      test("lowercase utf8 encoding → no re-exec") {
        assertTrue(
          !Main.jnuNeedsReexec(Some("utf8"), None, Some("/app/snap.jar"))
        )
      },
      test("guard env set → no re-exec even with ASCII encoding") {
        assertTrue(
          !Main.jnuNeedsReexec(Some("ANSI_X3.4-1968"), Some("1"), Some("/app/snap.jar"))
        )
      },
      test("classes directory (not jar) → no re-exec") {
        assertTrue(
          !Main.jnuNeedsReexec(Some("ANSI_X3.4-1968"), None, Some("/app/target/classes/"))
        )
      },
      test("None encoding → no re-exec (assume fine)") {
        assertTrue(
          !Main.jnuNeedsReexec(None, None, Some("/app/snap.jar"))
        )
      },
      test("None jar path → no re-exec (sbt run/test)") {
        assertTrue(
          !Main.jnuNeedsReexec(Some("ANSI_X3.4-1968"), None, None)
        )
      }
    ),
    suite("reexecCommand builder (F-utf8)")(
      test("builds exact command list with flags and arg passthrough") {
        val cmd = Main.reexecCommand("/opt/java", "/app/snap.jar", Seq("status", "--verbose"))
        assertTrue(
          cmd == List(
            "/opt/java/bin/java",
            "-Dsun.misc.unsafe.memory.access=allow",
            "-Dfile.encoding=UTF-8",
            "-jar",
            "/app/snap.jar",
            "status",
            "--verbose"
          )
        )
      },
      test("handles empty args") {
        val cmd = Main.reexecCommand("/opt/java", "/app/snap.jar", Seq.empty)
        assertTrue(
          cmd == List(
            "/opt/java/bin/java",
            "-Dsun.misc.unsafe.memory.access=allow",
            "-Dfile.encoding=UTF-8",
            "-jar",
            "/app/snap.jar"
          )
        )
      }
    ),
    suite("reexecEnv overrides (F-utf8)")(
      test("forces UTF-8 locale and sets the recursion guard") {
        val env = Main.reexecEnv
        assertTrue(
          env("LANG") == "C.UTF-8",
          env("LC_ALL") == "C.UTF-8",
          env("SNAP_JNU_REEXEC") == "1"
        )
      },
      test("contains exactly the three required override keys") {
        assertTrue(
          Main.reexecEnv.keySet == Set("LANG", "LC_ALL", "SNAP_JNU_REEXEC")
        )
      }
    )
  )
}
