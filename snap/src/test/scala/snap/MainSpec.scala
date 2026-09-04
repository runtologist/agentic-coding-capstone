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
    )
  )
}
