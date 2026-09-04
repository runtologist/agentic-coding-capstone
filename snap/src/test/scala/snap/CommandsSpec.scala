package snap

import zio.*
import zio.http.{Body, Request, Response, RoutePattern, Routes, Server, Status, handler}
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{DirectoryNotEmptyException, Files, LinkOption, NoSuchFileException, Path}
import scala.jdk.CollectionConverters.*

import snap.Model.*

/** End-to-end tests for the command layer ([[Commands.run]]) against the harness's byte-exact
  * expectations (tests 01-14, 19, 20, 24-28), executed in-process against temp directories with
  * captured output instead of spawned processes.
  */
object CommandsSpec extends ZIOSpecDefault {

  private val esc = "\u001b"

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def deleteRecursively(dir: Path): Unit =
    if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      val stream = Files.walk(dir)
      try {
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)(Ordering[Int].reverse)
          .foreach { p =>
            try Files.deleteIfExists(p)
            catch { case _: DirectoryNotEmptyException | _: NoSuchFileException => () }
          }
      } finally stream.close()
    }

  private def tempDir(tag: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory(s"l7-$tag"))
    )(dir => ZIO.attemptBlocking(deleteRecursively(dir)).ignoreLogged)

  private def writeText(path: Path, text: String): UIO[Unit] =
    ZIO.attemptBlocking {
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, text.getBytes(UTF_8))
      ()
    }.orDie

  private def writeBytes(path: Path, bytes: Array[Byte]): UIO[Unit] =
    ZIO.attemptBlocking {
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, bytes)
      ()
    }.orDie

  private def readText(path: Path): UIO[String] =
    ZIO.attemptBlocking(new String(Files.readAllBytes(path), UTF_8)).orDie

  private def readBytes(path: Path): UIO[Array[Byte]] =
    ZIO.attemptBlocking(Files.readAllBytes(path)).orDie

  private def exists(path: Path): UIO[Boolean] =
    ZIO.attemptBlocking(Files.exists(path, LinkOption.NOFOLLOW_LINKS)).orDie

  private def copyTree(from: Path, to: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      val stream = Files.walk(from)
      try {
        stream.iterator().asScala.foreach { p =>
          val target = to.resolve(from.relativize(p).toString)
          if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target)
          else {
            Option(target.getParent).foreach(Files.createDirectories(_))
            Files.copy(p, target, LinkOption.NOFOLLOW_LINKS)
          }
        }
      } finally stream.close()
    }.orDie

  private def symlink(path: Path, target: String): UIO[Unit] =
    ZIO.attemptBlocking {
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.createSymbolicLink(path, Path.of(target))
      ()
    }.orDie

  /** Plain-mode environment: NO_COLOR present, no HOME, no TTY — mirrors the harness default. */
  private def envFor(
      cwd: Path,
      home: Option[Path] = None,
      snapColor: Option[String] = None,
      noColorPresent: Boolean = true,
      isTty: Boolean = false,
      snapDebug: Boolean = false
  ): Commands.CmdEnv =
    Commands.CmdEnv(cwd, home, snapColor, noColorPresent, isTty, snapDebug)

  /** Runs the CLI in-process with captured streams; yields (exit code, stdout, stderr). */
  private def runCli(args: String*)(env: Commands.CmdEnv): UIO[(Int, String, String)] =
    for {
      cap <- Commands.Output.captured
      code <- Commands.run(args, env, cap.output)
      out <- cap.stdout
      err <- cap.stderr
    } yield (code, out, err)

  /** `init <name>` plus `config contributor.id`, the prologue of nearly every scenario. */
  private def initRepo(base: Path, name: String, id: String): UIO[Path] = {
    val repo = base.resolve(name)
    for {
      _ <- runCli("init", name)(envFor(base))
      _ <- runCli("config", "contributor.id", id)(envFor(repo))
    } yield repo
  }

  private def repoFile(repo: Path): Path = repo.resolve(".snap").resolve("repository.json")

  private def localConfig(repo: Path): Path = repo.resolve(".snap").resolve("config.json")

  private val port0: Model.Port =
    Model.Port.parse("0").fold(e => throw new IllegalStateException(e.detail), identity)

  /** Poll captured stdout until the serve URL line appears. */
  private def awaitUrl(cap: Commands.Output.Captured, tries: Int = 500): Task[String] =
    cap.stdout.flatMap { s =>
      val idx = s.indexOf('\n')
      if (idx >= 0) ZIO.succeed(s.substring(0, idx))
      else if (tries <= 0) ZIO.fail(new RuntimeException("server never printed its URL"))
      else ZIO.attemptBlocking(Thread.sleep(20)) *> awaitUrl(cap, tries - 1)
    }

  private def httpGet(url: String): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val client = java.net.http.HttpClient.newHttpClient()
      val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build()
      val resp = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(UTF_8))
      (resp.statusCode(), resp.body())
    }

  // ---------------------------------------------------------------------------
  // init
  // ---------------------------------------------------------------------------

  private val initSuite = suite("init (harness 01/02)")(
    test("creates an empty repository and prints () (test 01)") {
      ZIO.scoped {
        for {
          base <- tempDir("init")
          r <- runCli("init", "repo")(envFor(base))
          (code, out, err) = r
          json <- readText(repoFile(base.resolve("repo")))
        } yield assertTrue(
          code == 0,
          out == "()\n",
          err == "",
          json == Json.writeRepository(Repository(Version.empty, Vector.empty))
        )
      }
    },
    test("init without a path initializes the current directory") {
      ZIO.scoped {
        for {
          base <- tempDir("init-cwd")
          r <- runCli("init")(envFor(base))
          (code, out, _) = r
          meta <- exists(repoFile(base))
        } yield assertTrue(code == 0, out == "()\n", meta)
      }
    },
    test("reinitializing an existing repository fails (test 02)") {
      ZIO.scoped {
        for {
          base <- tempDir("reinit")
          repo <- initRepo(base, "repo", "a@x")
          r <- runCli("init", "repo")(envFor(base))
          (code, out, err) = r
          json <- readText(repoFile(repo))
        } yield assertTrue(
          code == 1,
          out == "",
          err == "snap: repository already exists\n",
          json == Json.writeRepository(Repository(Version.empty, Vector.empty))
        )
      }
    },
    test("initializing inside an existing repository fails without creating .snap (test 02)") {
      ZIO.scoped {
        for {
          base <- tempDir("inside")
          repo <- initRepo(base, "repo", "a@x")
          _ <- ZIO.attemptBlocking(Files.createDirectories(repo.resolve("child"))).orDie
          r <- runCli("init")(envFor(repo.resolve("child")))
          (code, out, err) = r
          created <- exists(repo.resolve("child/.snap"))
        } yield assertTrue(
          code == 1,
          out == "",
          err == "snap: cannot initialize inside repository\n",
          !created
        )
      }
    },
    test("creates missing intermediate directories (test 02)") {
      ZIO.scoped {
        for {
          base <- tempDir("nested")
          r <- runCli("init", "new/repository")(envFor(base))
          meta <- exists(repoFile(base.resolve("new/repository")))
        } yield assertTrue(r._1 == 0, r._2 == "()\n", meta)
      }
    },
    test("preserves existing working files (test 02)") {
      ZIO.scoped {
        for {
          base <- tempDir("preserve")
          _ <- writeText(base.resolve("repo/existing.txt"), "keep me\n")
          r <- runCli("init", "repo")(envFor(base))
          kept <- readText(base.resolve("repo/existing.txt"))
        } yield assertTrue(r._1 == 0, kept == "keep me\n")
      }
    }
  )

  // ---------------------------------------------------------------------------
  // config
  // ---------------------------------------------------------------------------

  private val configSuite = suite("config (tests 03/25)")(
    test("writes canonical local config and prints nothing (test 03)") {
      ZIO.scoped {
        for {
          base <- tempDir("cfg-local")
          repo <- initRepo(base, "repo", "a@x")
          _ <- ZIO.attemptBlocking(Files.delete(localConfig(repo))).orDie
          r <- runCli("config", "contributor.id", "local@example.com")(envFor(repo))
          (code, out, err) = r
          json <- readText(localConfig(repo))
        } yield assertTrue(
          code == 0,
          out == "",
          err == "",
          json == Json.writeConfig(Json.ConfigFile(ContributorId("local@example.com")))
        )
      }
    },
    test("writes global config under HOME (test 03)") {
      ZIO.scoped {
        for {
          base <- tempDir("cfg-global")
          home = base.resolve("home")
          r <- runCli("config", "--global", "contributor.id", "global@example.com")(
            envFor(base, home = Some(home))
          )
          (code, out, err) = r
          json <- readText(home.resolve(".snapconfig.json"))
        } yield assertTrue(
          code == 0,
          out == "",
          err == "",
          json == Json.writeConfig(Json.ConfigFile(ContributorId("global@example.com")))
        )
      }
    },
    test("global config without HOME fails before writing") {
      ZIO.scoped {
        for {
          base <- tempDir("cfg-nohome")
          r <- runCli("config", "--global", "contributor.id", "g@x")(envFor(base))
          (code, out, err) = r
        } yield assertTrue(
          code == 1,
          out == "",
          err.startsWith("snap: "),
          err.toLowerCase.contains("home"),
          err.endsWith("\n")
        )
      }
    },
    test("local config outside a repository fails") {
      ZIO.scoped {
        for {
          base <- tempDir("cfg-norepo")
          r <- runCli("config", "contributor.id", "a@x")(envFor(base))
        } yield assertTrue(r == ((1, "", "snap: not a Snap repository\n")))
      }
    },
    test("rejects every invalid id form without writing anything (test 25 matrix)") {
      val badIds = Vector("two@@x", "space @x", "a,b@x", "a(b)@x", "a->b@x", "bad-id")
      ZIO.scoped {
        for {
          base <- tempDir("cfg-bad")
          repo <- initRepo(base, "repo", "ok@x")
          _ <- ZIO.attemptBlocking(Files.delete(localConfig(repo))).orDie
          results <- ZIO.foreach(badIds) { id =>
            for {
              r <- runCli("config", "contributor.id", id)(envFor(repo))
              (c, o, e) = r
              written <- exists(localConfig(repo))
            } yield c == 1 && o == "" && !written &&
              e.startsWith("snap: invalid contributor id: ") && e.endsWith("\n")
          }
        } yield assertTrue(results.forall(identity))
      }
    },
    test("rewriting config drops unknown fields (test 25)") {
      ZIO.scoped {
        for {
          base <- tempDir("cfg-drop")
          repo <- initRepo(base, "repo", "old@x")
          _ <- writeText(
            localConfig(repo),
            "{\n  \"contributor\": {\"id\": \"old@x\"},\n  \"unknown\": true\n}\n"
          )
          r <- runCli("config", "contributor.id", "new@x")(envFor(repo))
          json <- readText(localConfig(repo))
        } yield assertTrue(
          r._1 == 0,
          json == Json.writeConfig(Json.ConfigFile(ContributorId("new@x")))
        )
      }
    },
    test("an invalid local config blocks the global fallback (tests 03/25)") {
      ZIO.scoped {
        for {
          base <- tempDir("cfg-block")
          home = base.resolve("home")
          repo <- initRepo(base, "repo", "x@x")
          _ <- writeText(localConfig(repo), "{\"contributor\":{\"id\":\"not-an-id\"}}\n")
          _ <- writeText(
            home.resolve(".snapconfig.json"),
            Json.writeConfig(Json.ConfigFile(ContributorId("global@x")))
          )
          _ <- writeText(repo.resolve("f.txt"), "x\n")
          r <- runCli("commit", "m")(envFor(repo, home = Some(home)))
        } yield assertTrue(
          r._1 == 1,
          r._2 == "",
          r._3.startsWith("snap: invalid contributor id: ")
        )
      }
    }
  )

  // ---------------------------------------------------------------------------
  // status
  // ---------------------------------------------------------------------------

  private val statusSuite = suite("status (tests 04/08/19/23/25)")(
    test("fresh repository reports the empty version (test 04)") {
      ZIO.scoped {
        for {
          base <- tempDir("status-empty")
          repo <- initRepo(base, "repo", "a@x")
          r <- runCli("status")(envFor(repo))
        } yield assertTrue(r == ((0, "version ()\n", "")))
      }
    },
    test("reports added rows sorted by unsigned UTF-8 path (test 25)") {
      ZIO.scoped {
        for {
          base <- tempDir("status-sort")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("z"), "z\n")
          _ <- writeText(repo.resolve("\u00e9"), "accent\n")
          _ <- writeText(repo.resolve("\ud83d\ude00"), "emoji\n")
          _ <- writeText(repo.resolve("nested/file"), "nested\n")
          r <- runCli("status")(envFor(repo))
          (code, out, err) = r
        } yield assertTrue(
          code == 0,
          err == "",
          out == "version ()\nA nested/file\nA z\nA \u00e9\nA \ud83d\ude00\n"
        )
      }
    },
    test("reports M, A, D rows after a commit (test 04)") {
      ZIO.scoped {
        for {
          base <- tempDir("status-amd")
          repo <- initRepo(base, "repo", "alice@example.com")
          _ <- writeText(repo.resolve("z.txt"), "z\n")
          _ <- writeText(repo.resolve("a.txt"), "a\n")
          _ <- runCli("commit", "first")(envFor(repo))
          _ <- writeText(repo.resolve("a.txt"), "changed\n")
          _ <- ZIO.attemptBlocking(Files.delete(repo.resolve("z.txt"))).orDie
          _ <- writeText(repo.resolve("m.txt"), "middle\n")
          r <- runCli("status")(envFor(repo))
          (code, out, err) = r
        } yield assertTrue(
          code == 0,
          err == "",
          out == "version (alice@example.com->1)\nM a.txt\nA m.txt\nD z.txt\n"
        )
      }
    },
    test("fails on a symlink with the exact error and empty stdout (test 08)") {
      ZIO.scoped {
        for {
          base <- tempDir("status-link")
          repo <- initRepo(base, "repo", "a@x")
          _ <- symlink(repo.resolve("link"), "missing")
          r <- runCli("status")(envFor(repo))
          (code, out, err) = r
        } yield assertTrue(
          code == 1,
          out == "",
          err == "snap: unsupported working tree entry: link\n"
        )
      }
    },
    test("surfaces repository validation errors with empty stdout (test 23)") {
      ZIO.scoped {
        for {
          base <- tempDir("status-invalid")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(
            repoFile(repo),
            "{\n  \"format\": 1,\n  \"frontier\": [],\n  \"patches\": [],\n  \"unknown\": true\n}\n"
          )
          r <- runCli("status")(envFor(repo))
          (code, out, err) = r
        } yield assertTrue(
          code == 1,
          out == "",
          err == "snap: repository has unknown field: unknown\n"
        )
      }
    },
    test("outside any repository fails with the exact error (test 14)") {
      ZIO.scoped {
        for {
          base <- tempDir("status-norepo")
          r <- runCli("status")(envFor(base.resolve("nowhere")))
        } yield assertTrue(r == ((1, "", "snap: not a Snap repository\n")))
      }
    },
    test("discovers the repository from a nested working directory (test 19)") {
      ZIO.scoped {
        for {
          base <- tempDir("status-nested")
          repo <- initRepo(base, "repo", "a@x")
          deep = repo.resolve("sub").resolve("deep")
          _ <- ZIO.attemptBlocking(Files.createDirectories(deep)).orDie
          r <- runCli("status")(envFor(deep))
        } yield assertTrue(r == ((0, "version ()\n", "")))
      }
    }
  )

  // ---------------------------------------------------------------------------
  // log
  // ---------------------------------------------------------------------------

  private val logSuite = suite("log (test 04)")(
    test("empty repository prints nothing") {
      ZIO.scoped {
        for {
          base <- tempDir("log-empty")
          repo <- initRepo(base, "repo", "a@x")
          r <- runCli("log")(envFor(repo))
        } yield assertTrue(r == ((0, "", "")))
      }
    },
    test("prints reverse canonical order with backslash-tab-LF escaping (test 04)") {
      ZIO.scoped {
        for {
          base <- tempDir("log-escape")
          repo <- initRepo(base, "repo", "alice@example.com")
          _ <- writeText(repo.resolve("a.txt"), "a\n")
          _ <- runCli("commit", "first\tline\nsecond\\tail")(envFor(repo))
          _ <- writeText(repo.resolve("a.txt"), "changed\n")
          _ <- runCli("commit", "second")(envFor(repo))
          r <- runCli("log")(envFor(repo))
          (code, out, err) = r
        } yield assertTrue(
          code == 0,
          err == "",
          out ==
            "(alice@example.com->2)\talice@example.com\tsecond\n" +
            "(alice@example.com->1)\talice@example.com\tfirst\\tline\\nsecond\\\\tail\n"
        )
      }
    },
    test("multi-author history prints latest integration first") {
      ZIO.scoped {
        for {
          base <- tempDir("log-multi")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("f"), "one\n")
          _ <- runCli("commit", "one")(envFor(repo))
          _ <- runCli("config", "contributor.id", "b@x")(envFor(repo))
          _ <- writeText(repo.resolve("f"), "two\n")
          _ <- runCli("commit", "two")(envFor(repo))
          r <- runCli("log")(envFor(repo))
        } yield assertTrue(
          r == ((0, "(a@x->1,b@x->1)\tb@x\ttwo\n(a@x->1)\ta@x\tone\n", ""))
        )
      }
    }
  )

  // ---------------------------------------------------------------------------
  // commit
  // ---------------------------------------------------------------------------

  private val commitSuite = suite("commit (tests 03/04/05/06/25/27)")(
    test("prints the new version and persists the canonical repository (tests 03/04)") {
      ZIO.scoped {
        for {
          base <- tempDir("commit-basic")
          repo <- initRepo(base, "repo", "alice@example.com")
          _ <- writeText(repo.resolve("a.txt"), "a\n")
          _ <- writeText(repo.resolve("z.txt"), "z\n")
          r <- runCli("commit", "first")(envFor(repo))
          (code, out, err) = r
          json <- readText(repoFile(repo))
          expected = Json.writeRepository(
            Repository(
              Version(Vector(ContributorId("alice@example.com") -> 1L)),
              Vector(
                Patch(
                  ContributorId("alice@example.com"),
                  1L,
                  Version.empty,
                  "first",
                  Vector(
                    Change.Text("a.txt", Vector(EditOp.Insert(Vector("a\n")))),
                    Change.Text("z.txt", Vector(EditOp.Insert(Vector("z\n"))))
                  )
                )
              )
            )
          )
        } yield assertTrue(
          code == 0,
          err == "",
          out == "(alice@example.com->1)\n",
          json == expected
        )
      }
    },
    test("stores changes sorted by path with the canonical edit script (test 05)") {
      ZIO.scoped {
        for {
          base <- tempDir("commit-sort")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("repeated.txt"), "a\nb\na\n")
          _ <- runCli("commit", "old")(envFor(repo))
          _ <- writeText(repo.resolve("repeated.txt"), "b\na\na")
          _ <- writeText(repo.resolve("added.txt"), "new")
          r <- runCli("commit", "new")(envFor(repo))
          json <- readText(repoFile(repo))
          expected = Json.writeRepository(
            Repository(
              Version(Vector(ContributorId("a@x") -> 2L)),
              Vector(
                Patch(
                  ContributorId("a@x"),
                  1L,
                  Version.empty,
                  "old",
                  Vector(
                    Change.Text("repeated.txt", Vector(EditOp.Insert(Vector("a\n", "b\n", "a\n"))))
                  )
                ),
                Patch(
                  ContributorId("a@x"),
                  2L,
                  Version(Vector(ContributorId("a@x") -> 1L)),
                  "new",
                  Vector(
                    Change.Text("added.txt", Vector(EditOp.Insert(Vector("new")))),
                    Change.Text(
                      "repeated.txt",
                      Vector(EditOp.Delete(1), EditOp.Retain(2), EditOp.Insert(Vector("a")))
                    )
                  )
                )
              )
            )
          )
        } yield assertTrue(r._1 == 0, r._2 == "(a@x->2)\n", r._3 == "", json == expected)
      }
    },
    test("empty file becomes a text change with an empty edit; binary becomes put (test 06)") {
      ZIO.scoped {
        for {
          base <- tempDir("commit-bin")
          repo <- initRepo(base, "repo", "binary@example.com")
          _ <- writeText(repo.resolve("empty"), "")
          _ <- writeBytes(repo.resolve("data.bin"), Array[Byte](0, 0xff.toByte, 1, 1))
          r <- runCli("commit", "bytes")(envFor(repo))
          json <- readText(repoFile(repo))
          expected = Json.writeRepository(
            Repository(
              Version(Vector(ContributorId("binary@example.com") -> 1L)),
              Vector(
                Patch(
                  ContributorId("binary@example.com"),
                  1L,
                  Version.empty,
                  "bytes",
                  Vector(
                    Change.Put("data.bin", Array[Byte](0, 0xff.toByte, 1, 1)),
                    Change.Text("empty", Vector.empty)
                  )
                )
              )
            )
          )
        } yield assertTrue(r._1 == 0, r._2 == "(binary@example.com->1)\n", json == expected)
      }
    },
    test("refuses to commit a clean tree (test 04)") {
      ZIO.scoped {
        for {
          base <- tempDir("commit-clean")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("f"), "x\n")
          _ <- runCli("commit", "one")(envFor(repo))
          r <- runCli("commit", "clean")(envFor(repo))
        } yield assertTrue(r == ((1, "", "snap: working tree is clean\n")))
      }
    },
    test("refuses to commit without a contributor id (tests 03/19)") {
      ZIO.scoped {
        for {
          base <- tempDir("commit-noid")
          _ <- runCli("init", "repo")(envFor(base))
          repo = base.resolve("repo")
          _ <- writeText(repo.resolve("f"), "x\n")
          r <- runCli("commit", "m")(envFor(repo, home = None))
        } yield assertTrue(
          r == ((1, "", "snap: contributor.id is required; configure it locally or globally\n"))
        )
      }
    },
    test("empty message fails before the clean-tree check (test 25)") {
      ZIO.scoped {
        for {
          base <- tempDir("commit-empty-msg")
          repo <- initRepo(base, "repo", "good@x")
          _ <- writeText(repo.resolve("f"), "x\n")
          _ <- runCli("commit", "one")(envFor(repo))
          // Tree is clean now; message validation must still fire first.
          r <- runCli("commit", "")(envFor(repo))
        } yield assertTrue(r == ((1, "", "snap: invalid commit message\n")))
      }
    },
    test("control characters in the message are rejected") {
      ZIO.scoped {
        for {
          base <- tempDir("commit-ctrl")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("f"), "x\n")
          r <- runCli("commit", "bad\u0001msg")(envFor(repo))
        } yield assertTrue(r == ((1, "", "snap: invalid commit message\n")))
      }
    },
    test("tab and LF are allowed in messages (test 04)") {
      ZIO.scoped {
        for {
          base <- tempDir("commit-esc")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("f"), "x\n")
          r <- runCli("commit", "first\tline\nsecond\\tail")(envFor(repo))
        } yield assertTrue(r == ((0, "(a@x->1)\n", "")))
      }
    },
    test("overlong message is rejected") {
      ZIO.scoped {
        for {
          base <- tempDir("commit-long")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("f"), "x\n")
          r <- runCli("commit", "x" * 4097)(envFor(repo))
        } yield assertTrue(r == ((1, "", "snap: invalid commit message\n")))
      }
    },
    test("multi-author history is stored sorted by author then revision (test 27)") {
      ZIO.scoped {
        for {
          base <- tempDir("commit-multi")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("f"), "1\n")
          _ <- runCli("commit", "a1")(envFor(repo))
          _ <- runCli("config", "contributor.id", "b@x")(envFor(repo))
          _ <- writeText(repo.resolve("f"), "2\n")
          _ <- runCli("commit", "b1")(envFor(repo))
          _ <- runCli("config", "contributor.id", "a@x")(envFor(repo))
          _ <- writeText(repo.resolve("f"), "3\n")
          _ <- runCli("commit", "a2")(envFor(repo))
          json <- readText(repoFile(repo))
          parsed = Json.parseRepository(json)
        } yield assertTrue(
          parsed.map(_.frontier.render) == Right("(a@x->2,b@x->1)"),
          parsed.map(_.patches.map(p => (p.author.value, p.revision))) ==
            Right(Vector("a@x" -> 1L, "a@x" -> 2L, "b@x" -> 1L)),
          parsed.map(_.patches(1).base.render) == Right("(a@x->1,b@x->1)")
        )
      }
    },
    test("commit fails with overflow error when frontier revision is at max (E1-S1)") {
      ZIO.scoped {
        for {
          base <- tempDir("commit-overflow")
          repoDir = base.resolve("repo")
          _ <- ZIO.attemptBlocking(Files.createDirectories(repoDir)).orDie
          _ <- writeText(repoDir.resolve("f.txt"), "modified\n")
          contributor = ContributorId("a@x")
          maxRev = Model.MaxSafeInteger
          inMemoryRepo = Repository(
            Version(Vector(contributor -> maxRev)),
            Vector(
              Patch(
                contributor,
                maxRev,
                Version.empty,
                "init",
                Vector(Change.Text("f.txt", Vector(EditOp.Insert(Vector("original\n")))))
              )
            )
          )
          result <- Commands
            .commitWithRepo(
              repoDir,
              inMemoryRepo,
              contributor,
              "next",
              Render.Presentation.Plain
            )
            .either
          repoFileWritten <- exists(repoDir.resolve(".snap").resolve("repository.json"))
        } yield assertTrue(
          result == Left(SnapError.NotPositiveSafeInteger("revision")),
          !repoFileWritten
        )
      }
    }
  )

  // ---------------------------------------------------------------------------
  // diff
  // ---------------------------------------------------------------------------

  private val diffSuite = suite("diff (tests 05/06/13/16/19/21/26)")(
    test("working-tree diff matches the test-05 golden output") {
      ZIO.scoped {
        for {
          base <- tempDir("diff-golden")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("repeated.txt"), "a\nb\na\n")
          _ <- runCli("commit", "old")(envFor(repo))
          _ <- writeText(repo.resolve("repeated.txt"), "b\na\na")
          _ <- writeText(repo.resolve("added.txt"), "new")
          r <- runCli("diff")(envFor(repo))
          (code, out, err) = r
        } yield assertTrue(
          code == 0,
          err == "",
          out ==
            "--- /dev/null\n+++ b/added.txt\n@@ -1,0 +1,1 @@\n+new\n\\ No newline at end of file\n" +
            "--- a/repeated.txt\n+++ b/repeated.txt\n@@ -1,3 +1,3 @@\n-a\n b\n a\n+a\n\\ No newline at end of file\n"
        )
      }
    },
    test("identical version pair prints nothing (test 05)") {
      ZIO.scoped {
        for {
          base <- tempDir("diff-same")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("f"), "x\n")
          _ <- runCli("commit", "one")(envFor(repo))
          r <- runCli("diff", "(a@x->1)", "(a@x->1)")(envFor(repo))
        } yield assertTrue(r == ((0, "", "")))
      }
    },
    test("version-pair diff renders both directions (test 21)") {
      ZIO.scoped {
        for {
          base <- tempDir("diff-pair")
          a <- initRepo(base, "a-side", "a@x")
          _ <- writeText(a.resolve("story.txt"), "base\n")
          _ <- runCli("commit", "a1")(envFor(a))
          _ <- copyTree(a, base.resolve("b-side"))
          b = base.resolve("b-side")
          _ <- runCli("config", "contributor.id", "b@x")(envFor(b))
          _ <- writeText(a.resolve("story.txt"), "base\nA2\n")
          _ <- runCli("commit", "a2")(envFor(a))
          _ <- writeText(b.resolve("story.txt"), "base\nB1\n")
          _ <- runCli("commit", "b1")(envFor(b))
          _ <- writeText(b.resolve("story.txt"), "base\nB1\nB2\n")
          _ <- runCli("commit", "b2")(envFor(b))
          _ <- copyTree(a, base.resolve("from-a"))
          fa = base.resolve("from-a")
          _ <- runCli("merge", "../b-side")(envFor(fa))
          fw <- runCli("diff", "(a@x->1)", "(a@x->2,b@x->2)")(envFor(fa))
          bw <- runCli("diff", "(a@x->2,b@x->2)", "(a@x->1,b@x->2)")(envFor(fa))
        } yield assertTrue(
          fw == ((
            0,
            "--- a/story.txt\n+++ b/story.txt\n@@ -1,1 +1,4 @@\n base\n+B1\n+B2\n+A2\n",
            ""
          )),
          bw == ((
            0,
            "--- a/story.txt\n+++ b/story.txt\n@@ -1,4 +1,3 @@\n base\n B1\n B2\n-A2\n",
            ""
          ))
        )
      }
    },
    test("invalid version operands fail with invalid-version errors (tests 19/25)") {
      ZIO.scoped {
        for {
          base <- tempDir("diff-invalid")
          repo <- initRepo(base, "repo", "a@x")
          r1 <- runCli("diff", "(a@x->01)", "(a@x->1)")(envFor(repo))
          r2 <- runCli("diff", "(a@x->1,a@x->2)", "(a@x->1)")(envFor(repo))
        } yield assertTrue(
          r1._1 == 1,
          r1._2 == "",
          r1._3.startsWith("snap: invalid version: "),
          r2._1 == 1,
          r2._2 == "",
          r2._3.startsWith("snap: invalid version: ")
        )
      }
    },
    test("unknown version echoes the rendered version (test 19)") {
      ZIO.scoped {
        for {
          base <- tempDir("diff-unknown")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("f"), "one\n")
          _ <- runCli("commit", "one")(envFor(repo))
          r <- runCli("diff", "(a@x->2)", "(a@x->1)")(envFor(repo))
        } yield assertTrue(r == ((1, "", "snap: unknown version: (a@x->2)\n")))
      }
    },
    test("empty text file renders a zero-line hunk in working-tree diff (test 06)") {
      ZIO.scoped {
        for {
          base <- tempDir("diff-empty")
          repo <- initRepo(base, "repo", "b@x")
          _ <- writeText(repo.resolve("empty"), "")
          r <- runCli("diff")(envFor(repo))
        } yield assertTrue(r == ((0, "--- /dev/null\n+++ b/empty\n@@ -1,0 +1,0 @@\n", "")))
      }
    },
    test("binary add and delete render single lines (test 06)") {
      ZIO.scoped {
        for {
          base <- tempDir("diff-binary")
          repo <- initRepo(base, "repo", "b@x")
          _ <- writeBytes(repo.resolve("data.bin"), Array[Byte](0, 0xff.toByte, 1))
          r1 <- runCli("diff")(envFor(repo))
          _ <- runCli("commit", "bytes")(envFor(repo))
          _ <- ZIO.attemptBlocking(Files.delete(repo.resolve("data.bin"))).orDie
          r2 <- runCli("diff")(envFor(repo))
        } yield assertTrue(
          r1 == ((0, "Binary files /dev/null and b/data.bin differ\n", "")),
          r2 == ((0, "Binary files a/data.bin and /dev/null differ\n", ""))
        )
      }
    },
    test("cross-repo diff matches the byte-preservation golden and never mutates local (test 26)") {
      ZIO.scoped {
        for {
          base <- tempDir("diff-xrepo")
          remote <- initRepo(base, "remote", "remote@x")
          _ <- writeBytes(remote.resolve("crlf.txt"), "a\r\nb".getBytes(UTF_8))
          _ <- writeBytes(remote.resolve("nul.bin"), Array[Byte]('a'.toByte, 0, 'b'.toByte))
          _ <- writeText(remote.resolve("unicode.txt"), "h\u00e9\n")
          _ <- runCli("commit", "portable-bytes")(envFor(remote))
          local <- initRepo(base, "local", "local@x")
          before <- readText(repoFile(local))
          r <- runCli("diff", "()", "(remote@x->1)", "--repo", "../remote")(envFor(local))
          (code, out, err) = r
          after <- readText(repoFile(local))
          leaked <- exists(local.resolve("crlf.txt"))
        } yield assertTrue(
          code == 0,
          err == "",
          out ==
            "--- /dev/null\n+++ b/crlf.txt\n@@ -1,0 +1,2 @@\n+a\r\n+b\n\\ No newline at end of file\n" +
            "Binary files /dev/null and b/nul.bin differ\n" +
            "--- /dev/null\n+++ b/unicode.txt\n@@ -1,0 +1,1 @@\n+h\u00e9\n",
          before == after,
          !leaked
        )
      }
    },
    test("cross-repo diff fails on dot collisions before any output (test 16)") {
      ZIO.scoped {
        for {
          base <- tempDir("diff-collide")
          local <- initRepo(base, "local", "a@x")
          _ <- writeText(local.resolve("file.txt"), "local\n")
          _ <- runCli("commit", "local")(envFor(local))
          remote <- initRepo(base, "remote", "a@x")
          _ <- writeText(remote.resolve("file.txt"), "remote\n")
          _ <- runCli("commit", "different")(envFor(remote))
          r <- runCli("diff", "()", "(a@x->1)", "--repo", "../remote")(envFor(local))
        } yield assertTrue(r == ((1, "", "snap: patch collision: a@x revision 1\n")))
      }
    },
    test("diff outside a repository fails") {
      ZIO.scoped {
        for {
          base <- tempDir("diff-norepo")
          r <- runCli("diff")(envFor(base))
        } yield assertTrue(r == ((1, "", "snap: not a Snap repository\n")))
      }
    }
  )

  // ---------------------------------------------------------------------------
  // revert
  // ---------------------------------------------------------------------------

  private val revertSuite = suite("revert (tests 07/14/19)")(
    test("restores file-directory transitions and prints the new version (test 07)") {
      ZIO.scoped {
        for {
          base <- tempDir("revert")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("node"), "file\n")
          _ <- runCli("commit", "file")(envFor(repo))
          _ <- ZIO.attemptBlocking(Files.delete(repo.resolve("node"))).orDie
          _ <- writeText(repo.resolve("node/child"), "child\n")
          _ <- runCli("commit", "directory")(envFor(repo))
          r1 <- runCli("revert", "(a@x->1)")(envFor(repo))
          f1 <- readText(repo.resolve("node"))
          goneChild <- exists(repo.resolve("node/child")).map(!_)
          r2 <- runCli("revert", "(a@x->2)")(envFor(repo))
          f2 <- readText(repo.resolve("node/child"))
          logR <- runCli("log")(envFor(repo))
        } yield assertTrue(
          r1 == ((0, "(a@x->3)\n", "")),
          f1 == "file\n",
          goneChild,
          r2 == ((0, "(a@x->4)\n", "")),
          f2 == "child\n",
          logR == ((
            0,
            "(a@x->4)\ta@x\trevert to (a@x->2)\n(a@x->3)\ta@x\trevert to (a@x->1)\n" +
              "(a@x->2)\ta@x\tdirectory\n(a@x->1)\ta@x\tfile\n",
            ""
          ))
        )
      }
    },
    test("revert to () empties the working tree (test 19)") {
      ZIO.scoped {
        for {
          base <- tempDir("revert-empty")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("file.txt"), "one\n")
          _ <- runCli("commit", "one")(envFor(repo))
          r <- runCli("revert", "()")(envFor(repo, home = None))
          gone <- exists(repo.resolve("file.txt")).map(!_)
        } yield assertTrue(r == ((0, "(a@x->2)\n", "")), gone)
      }
    },
    test("reverting to the current tree fails (test 07)") {
      ZIO.scoped {
        for {
          base <- tempDir("revert-noop")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("f"), "x\n")
          _ <- runCli("commit", "one")(envFor(repo))
          r <- runCli("revert", "(a@x->1)")(envFor(repo))
        } yield assertTrue(r == ((1, "", "snap: target tree is already current\n")))
      }
    },
    test("dirty working tree refuses revert (test 07)") {
      ZIO.scoped {
        for {
          base <- tempDir("revert-dirty")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("f"), "x\n")
          _ <- runCli("commit", "one")(envFor(repo))
          _ <- writeText(repo.resolve("dirty"), "dirty")
          r <- runCli("revert", "()")(envFor(repo))
          json <- readText(repoFile(repo))
        } yield assertTrue(
          r == ((1, "", "snap: working tree is dirty\n")),
          json.contains("\"revision\": 1")
        )
      }
    },
    test("unknown version is reported before a missing contributor (test 14)") {
      ZIO.scoped {
        for {
          base <- tempDir("revert-order")
          _ <- runCli("init", "repo")(envFor(base))
          repo = base.resolve("repo")
          r <- runCli("revert", "(unknown@x->1)")(envFor(repo, home = None))
        } yield assertTrue(r == ((1, "", "snap: unknown version: (unknown@x->1)\n")))
      }
    },
    test("invalid version syntax fails") {
      ZIO.scoped {
        for {
          base <- tempDir("revert-bad")
          repo <- initRepo(base, "repo", "a@x")
          r <- runCli("revert", "(a@x->0)")(envFor(repo))
        } yield assertTrue(r._1 == 1, r._2 == "", r._3.startsWith("snap: invalid version: "))
      }
    },
    test("missing contributor fails after version and cleanliness checks (test 19)") {
      ZIO.scoped {
        for {
          base <- tempDir("revert-noid")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("file.txt"), "one\n")
          _ <- runCli("commit", "one")(envFor(repo))
          _ <- ZIO.attemptBlocking(Files.delete(localConfig(repo))).orDie
          r <- runCli("revert", "()")(envFor(repo, home = None))
        } yield assertTrue(
          r == ((1, "", "snap: contributor.id is required; configure it locally or globally\n"))
        )
      }
    }
  )

  // ---------------------------------------------------------------------------
  // merge
  // ---------------------------------------------------------------------------

  private val mergeSuite = suite("merge (tests 09/10/11/13/16/17/20/26)")(
    test("concurrent text edits converge via OT with no warnings (test 09)") {
      ZIO.scoped {
        for {
          base <- tempDir("merge-text")
          left <- initRepo(base, "left", "seed@x")
          _ <- writeText(left.resolve("notes.txt"), "base\n")
          _ <- runCli("commit", "base")(envFor(left))
          _ <- copyTree(left, base.resolve("right"))
          right = base.resolve("right")
          _ <- runCli("config", "contributor.id", "alice@x")(envFor(left))
          _ <- runCli("config", "contributor.id", "bob@x")(envFor(right))
          _ <- writeText(left.resolve("notes.txt"), "base\nleft\n")
          _ <- writeText(right.resolve("notes.txt"), "base\nright\n")
          _ <- runCli("commit", "left")(envFor(left))
          _ <- runCli("commit", "right")(envFor(right))
          _ <- copyTree(left, base.resolve("left-copy"))
          m1 <- runCli("merge", base.resolve("right").toString)(envFor(left))
          m2 <- runCli("merge", "../left")(envFor(right))
          lf <- readText(left.resolve("notes.txt"))
          rf <- readText(right.resolve("notes.txt"))
          noop <- runCli("merge", "../right")(envFor(left))
        } yield assertTrue(
          m1 == ((0, "(alice@x->1,bob@x->1,seed@x->1)\n", "")),
          m2 == ((0, "(alice@x->1,bob@x->1,seed@x->1)\n", "")),
          lf == "base\nright\nleft\n",
          rf == "base\nright\nleft\n",
          noop == ((0, "(alice@x->1,bob@x->1,seed@x->1)\n", ""))
        )
      }
    },
    test("whole-file conflict rules produce sorted warnings and exact bytes (test 10)") {
      ZIO.scoped {
        for {
          base <- tempDir("merge-rules")
          seed <- initRepo(base, "base", "seed@x")
          _ <- writeText(seed.resolve("delete.txt"), "base\n")
          _ <- writeText(seed.resolve("incompatible.txt"), "base\n")
          _ <- writeText(seed.resolve("later-put.txt"), "base\n")
          _ <- writeText(seed.resolve("identical.txt"), "base\n")
          _ <- runCli("commit", "base")(envFor(seed))
          _ <- copyTree(seed, base.resolve("left"))
          _ <- copyTree(seed, base.resolve("right"))
          left = base.resolve("left")
          right = base.resolve("right")
          _ <- runCli("config", "contributor.id", "alice@x")(envFor(left))
          _ <- runCli("config", "contributor.id", "bob@x")(envFor(right))
          _ <- writeText(left.resolve("delete.txt"), "left\n")
          _ <- writeText(left.resolve("incompatible.txt"), "left text\n")
          _ <- writeBytes(left.resolve("later-put.txt"), Array[Byte](0, 1, 1))
          _ <- writeText(left.resolve("identical.txt"), "same\n")
          _ <- ZIO.attemptBlocking(Files.delete(right.resolve("delete.txt"))).orDie
          _ <- writeBytes(right.resolve("incompatible.txt"), Array[Byte](0, 0xff.toByte))
          _ <- writeText(right.resolve("later-put.txt"), "right text\n")
          _ <- writeText(right.resolve("identical.txt"), "same\n")
          _ <- runCli("commit", "left")(envFor(left))
          _ <- runCli("commit", "right")(envFor(right))
          m <- runCli("merge", "../right")(envFor(left))
          (code, stdout, stderr) = m
          incompat <- readBytes(left.resolve("incompatible.txt"))
          laterPut <- readBytes(left.resolve("later-put.txt"))
          identical <- readText(left.resolve("identical.txt"))
          deleted = !Files.exists(left.resolve("delete.txt"))
          re <- runCli("merge", "../right")(envFor(left))
        } yield assertTrue(
          code == 0,
          stdout == "(alice@x->1,bob@x->1,seed@x->1)\n",
          stderr ==
            "warning: auto-resolved delete.txt: delete-wins\n" +
            "warning: auto-resolved incompatible.txt: put-wins\n" +
            "warning: auto-resolved later-put.txt: later-put-wins\n",
          java.util.Arrays.equals(incompat, Array[Byte](0, 0xff.toByte)),
          java.util.Arrays.equals(laterPut, Array[Byte](0, 1, 1)),
          identical == "same\n",
          deleted,
          re == ((0, "(alice@x->1,bob@x->1,seed@x->1)\n", ""))
        )
      }
    },
    test("namespace conflicts keep the canonical winner in both directions (test 11)") {
      ZIO.scoped {
        for {
          base <- tempDir("merge-ns")
          // Direction 1: alice (canonically later) owns file `a`; bob owns `a/b`.
          anc <- initRepo(base, "ancestor", "alice@x")
          _ <- writeText(anc.resolve("a"), "ancestor\n")
          _ <- runCli("commit", "ancestor")(envFor(anc))
          desc <- initRepo(base, "descendant", "bob@x")
          _ <- writeText(desc.resolve("a/b"), "descendant\n")
          _ <- runCli("commit", "descendant")(envFor(desc))
          m1 <- runCli("merge", "../descendant")(envFor(anc))
          kept <- readText(anc.resolve("a"))
          goneChild <- exists(anc.resolve("a/b")).map(!_)
          // Direction 2: bob owns file `x`; alice owns `x/y`; merge alice into bob's repo.
          early <- initRepo(base, "early-ancestor", "bob@x")
          _ <- writeText(early.resolve("x"), "ancestor\n")
          _ <- runCli("commit", "ancestor")(envFor(early))
          late <- initRepo(base, "late-descendant", "alice@x")
          _ <- writeText(late.resolve("x/y"), "descendant\n")
          _ <- runCli("commit", "descendant")(envFor(late))
          m2 <- runCli("merge", "../late-descendant")(envFor(early))
          dirBecame <- ZIO.attemptBlocking(Files.isDirectory(early.resolve("x"))).orDie
          child <- readText(early.resolve("x/y"))
        } yield assertTrue(
          m1 == ((0, "(alice@x->1,bob@x->1)\n", "warning: auto-resolved a/b: namespace-wins\n")),
          kept == "ancestor\n",
          goneChild,
          m2 == ((0, "(alice@x->1,bob@x->1)\n", "warning: auto-resolved x: namespace-wins\n")),
          dirBecame,
          child == "descendant\n"
        )
      }
    },
    test("concurrent creates resolve to the canonically later author both ways (test 17)") {
      ZIO.scoped {
        for {
          base <- tempDir("merge-create")
          alice <- initRepo(base, "alice", "alice@x")
          bob <- initRepo(base, "bob", "bob@x")
          _ <- writeText(alice.resolve("same.txt"), "alice\n")
          _ <- writeText(bob.resolve("same.txt"), "bob\n")
          _ <- runCli("commit", "alice")(envFor(alice))
          _ <- runCli("commit", "bob")(envFor(bob))
          _ <- copyTree(alice, base.resolve("alice-copy"))
          m1 <- runCli("merge", "../bob")(envFor(alice))
          m2 <- runCli("merge", "../alice-copy")(envFor(bob))
          a <- readText(alice.resolve("same.txt"))
          b <- readText(bob.resolve("same.txt"))
        } yield assertTrue(
          m1 == ((
            0,
            "(alice@x->1,bob@x->1)\n",
            "warning: auto-resolved same.txt: later-create-wins\n"
          )),
          m2 == ((
            0,
            "(alice@x->1,bob@x->1)\n",
            "warning: auto-resolved same.txt: later-create-wins\n"
          )),
          a == "alice\n",
          b == "alice\n"
        )
      }
    },
    test("three-way history converges across merge association orders (test 18)") {
      val joined = "(a@x->1,b@x->1,c@x->1,seed@x->1)\n"
      ZIO.scoped {
        for {
          base <- tempDir("merge-3way")
          seed <- initRepo(base, "seed", "seed@x")
          _ <- writeText(seed.resolve("story.txt"), "start\nend\n")
          _ <- runCli("commit", "base")(envFor(seed))
          _ <- copyTree(seed, base.resolve("a"))
          _ <- copyTree(seed, base.resolve("b"))
          _ <- copyTree(seed, base.resolve("c"))
          a = base.resolve("a")
          b = base.resolve("b")
          c = base.resolve("c")
          _ <- runCli("config", "contributor.id", "a@x")(envFor(a))
          _ <- runCli("config", "contributor.id", "b@x")(envFor(b))
          _ <- runCli("config", "contributor.id", "c@x")(envFor(c))
          _ <- writeText(a.resolve("story.txt"), "start\nA\nend\n")
          _ <- writeText(b.resolve("story.txt"), "start\nB\nend\n")
          _ <- writeText(c.resolve("story.txt"), "end\n")
          _ <- runCli("commit", "a")(envFor(a))
          _ <- runCli("commit", "b")(envFor(b))
          _ <- runCli("commit", "c")(envFor(c))
          _ <- copyTree(a, base.resolve("agg-1"))
          _ <- copyTree(c, base.resolve("agg-2"))
          _ <- copyTree(a, base.resolve("agg-3"))
          _ <- copyTree(b, base.resolve("agg-4"))
          _ <- copyTree(b, base.resolve("agg-5"))
          _ <- copyTree(c, base.resolve("agg-6"))
          agg1 = base.resolve("agg-1")
          agg2 = base.resolve("agg-2")
          agg3 = base.resolve("agg-3")
          agg4 = base.resolve("agg-4")
          agg5 = base.resolve("agg-5")
          agg6 = base.resolve("agg-6")
          _ <- runCli("merge", "../b")(envFor(agg1))
          m1 <- runCli("merge", "../c")(envFor(agg1))
          _ <- runCli("merge", "../a")(envFor(agg2))
          m2 <- runCli("merge", "../b")(envFor(agg2))
          _ <- runCli("merge", "../c")(envFor(agg3))
          m3 <- runCli("merge", "../b")(envFor(agg3))
          _ <- runCli("merge", "../a")(envFor(agg4))
          m4 <- runCli("merge", "../c")(envFor(agg4))
          _ <- runCli("merge", "../c")(envFor(agg5))
          m5 <- runCli("merge", "../a")(envFor(agg5))
          _ <- runCli("merge", "../b")(envFor(agg6))
          m6 <- runCli("merge", "../a")(envFor(agg6))
          s1 <- readText(agg1.resolve("story.txt"))
          s2 <- readText(agg2.resolve("story.txt"))
          s3 <- readText(agg3.resolve("story.txt"))
          s4 <- readText(agg4.resolve("story.txt"))
          s5 <- readText(agg5.resolve("story.txt"))
          s6 <- readText(agg6.resolve("story.txt"))
        } yield assertTrue(
          m1 == ((0, joined, "")),
          m2 == ((0, joined, "")),
          m3 == ((0, joined, "")),
          m4 == ((0, joined, "")),
          m5 == ((0, joined, "")),
          m6 == ((0, joined, "")),
          s1 == "B\nA\nend\n",
          s2 == s1,
          s3 == s1,
          s4 == s1,
          s5 == s1,
          s6 == s1
        )
      }
    },
    test("dirty working tree refuses merge without importing history (test 20)") {
      ZIO.scoped {
        for {
          base <- tempDir("merge-dirty")
          remote <- initRepo(base, "remote", "remote@x")
          _ <- writeText(remote.resolve("remote.txt"), "remote\n")
          _ <- runCli("commit", "remote")(envFor(remote))
          local <- initRepo(base, "local", "local@x")
          _ <- writeText(local.resolve("dirty.txt"), "dirty\n")
          r <- runCli("merge", "../remote")(envFor(local))
          dirty <- readText(local.resolve("dirty.txt"))
          leaked <- exists(local.resolve("remote.txt"))
          json <- readText(repoFile(local))
        } yield assertTrue(
          r == ((1, "", "snap: working tree is dirty\n")),
          dirty == "dirty\n",
          !leaked,
          json == Json.writeRepository(Repository(Version.empty, Vector.empty))
        )
      }
    },
    test("unsupported entry refuses merge without importing history (test 20)") {
      ZIO.scoped {
        for {
          base <- tempDir("merge-unsup")
          remote <- initRepo(base, "remote", "remote@x")
          _ <- writeText(remote.resolve("remote.txt"), "remote\n")
          _ <- runCli("commit", "remote")(envFor(remote))
          local <- initRepo(base, "local", "local@x")
          _ <- symlink(local.resolve("link"), "../remote/remote.txt")
          r <- runCli("merge", "../remote")(envFor(local))
          leaked <- exists(local.resolve("remote.txt"))
          json <- readText(repoFile(local))
        } yield assertTrue(
          r == ((1, "", "snap: unsupported working tree entry: link\n")),
          !leaked,
          json == Json.writeRepository(Repository(Version.empty, Vector.empty))
        )
      }
    },
    test("dot collisions refuse merge without touching local state (test 16)") {
      ZIO.scoped {
        for {
          base <- tempDir("merge-collide")
          local <- initRepo(base, "local", "a@x")
          _ <- writeText(local.resolve("file.txt"), "local\n")
          _ <- runCli("commit", "local")(envFor(local))
          remote <- initRepo(base, "remote", "a@x")
          _ <- writeText(remote.resolve("file.txt"), "remote\n")
          _ <- runCli("commit", "different")(envFor(remote))
          before <- readText(repoFile(local))
          r <- runCli("merge", "../remote")(envFor(local))
          after <- readText(repoFile(local))
          kept <- readText(local.resolve("file.txt"))
        } yield assertTrue(
          r == ((1, "", "snap: patch collision: a@x revision 1\n")),
          before == after,
          kept == "local\n"
        )
      }
    },
    test("structurally equal patches with shuffled JSON merge as duplicates (test 26)") {
      ZIO.scoped {
        for {
          base <- tempDir("merge-dup")
          left <- initRepo(base, "duplicate-left", "same@x")
          _ <- writeText(left.resolve("f"), "same\n")
          _ <- runCli("commit", "same")(envFor(left))
          right = base.resolve("duplicate-right")
          _ <- ZIO.attemptBlocking(Files.createDirectories(right.resolve(".snap"))).orDie
          _ <- writeText(
            right.resolve(".snap/repository.json"),
            "{ \"patches\": [\n" +
              "  {\n" +
              "    \"changes\": [{\"edit\": [{\"insert\": [\"same\\n\"]}], \"path\": \"f\", \"type\": \"text\"}],\n" +
              "    \"message\": \"same\", \"base\": [], \"revision\": 1, \"author\": \"same@x\"\n" +
              "  }\n" +
              "], \"frontier\": [[\"same@x\", 1]], \"format\": 1\n}\n"
          )
          r <- runCli("merge", "../duplicate-right")(envFor(left))
          text <- readText(left.resolve("f"))
        } yield assertTrue(r == ((0, "(same@x->1)\n", "")), text == "same\n")
      }
    },
    test("HTTP merge imports the snapshot (test 13)") {
      ZIO.scoped {
        for {
          base <- tempDir("merge-http")
          remote <- initRepo(base, "remote", "remote@x")
          _ <- writeText(remote.resolve("file.txt"), "remote\n")
          _ <- runCli("commit", "remote")(envFor(remote))
          remoteRepo <- RepoIo.loadRepository(remote)
          port <- HttpServe.serveSnapshot(remoteRepo, port0)
          local <- initRepo(base, "local", "local@x")
          r <- runCli("merge", s"http://127.0.0.1:$port/repository.json")(envFor(local))
          content <- readText(local.resolve("file.txt"))
          json <- readText(repoFile(local))
        } yield assertTrue(
          r == ((0, "(remote@x->1)\n", "")),
          content == "remote\n",
          Json.parseRepository(json) == Right(remoteRepo)
        )
      }
    } @@ TestAspect.withLiveClock,
    test("malformed HTTP remote fails without mutating local state (tests 13/26)") {
      ZIO.scoped {
        for {
          base <- tempDir("merge-badhttp")
          local <- initRepo(base, "local", "local@x")
          port <- {
            val routes = Routes(
              RoutePattern.any -> handler { (_: zio.http.Path, _: Request) =>
                Response(
                  status = Status.Ok,
                  body = Body.fromString(
                    "{\"format\":1,\"frontier\":[],\"patches\":[],\"bad\":true}",
                    UTF_8
                  )
                )
              }
            )
            for {
              serverEnv <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
              p <- Server.install(routes).provideEnvironment(serverEnv)
            } yield p
          }
          before <- readText(repoFile(local))
          r <- runCli("merge", s"http://127.0.0.1:$port/repository.json")(envFor(local))
          after <- readText(repoFile(local))
        } yield assertTrue(
          r._1 == 1,
          r._2 == "",
          r._3 == "snap: repository has unknown field: bad\n",
          before == after
        )
      }
    } @@ TestAspect.withLiveClock
  )

  // ---------------------------------------------------------------------------
  // serve
  // ---------------------------------------------------------------------------

  private val serveSuite = suite("serve (tests 12/28)")(
    test("invalid repository fails before printing anything (test 12)") {
      ZIO.scoped {
        for {
          base <- tempDir("serve-invalid")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(
            repoFile(repo),
            "{\n  \"format\": 1,\n  \"frontier\": [],\n  \"patches\": [],\n  \"bad\": true\n}\n"
          )
          r <- runCli("--serve", "0")(envFor(repo))
        } yield assertTrue(
          r._1 == 1,
          r._2 == "",
          r._3 == "snap: repository has unknown field: bad\n"
        )
      }
    },
    test("serve outside a repository fails") {
      ZIO.scoped {
        for {
          base <- tempDir("serve-norepo")
          r <- runCli("--serve")(envFor(base))
        } yield assertTrue(r == ((1, "", "snap: not a Snap repository\n")))
      }
    },
    test("serves the startup snapshot, ignores later commits, exits 0 on SIGTERM (test 12)") {
      ZIO.scoped {
        for {
          base <- tempDir("serve-ok")
          repo <- initRepo(base, "repo", "a@x")
          _ <- writeText(repo.resolve("file.txt"), "one\n")
          _ <- runCli("commit", "one")(envFor(repo))
          snapshot <- readText(repoFile(repo))
          cap <- Commands.Output.captured
          fiber <- Commands.run(Seq("--serve", "0"), envFor(repo), cap.output).fork
          url <- awaitUrl(cap)
          first <- httpGet(url)
          // A later commit must not change the served snapshot (test 12).
          _ <- writeText(repo.resolve("file.txt"), "two\n")
          _ <- runCli("commit", "two")(envFor(repo))
          second <- httpGet(url)
          _ <- ZIO.attemptBlocking(sun.misc.Signal.raise(new sun.misc.Signal("TERM"))).orDie
          exit <- fiber.await
          out <- cap.stdout
          err <- cap.stderr
        } yield assertTrue(
          out == url + "\n",
          url.matches("http://127\\.0\\.0\\.1:[0-9]+/repository\\.json"),
          err == "",
          first == ((200, snapshot)),
          second == ((200, snapshot)),
          exit == Exit.succeed(0)
        )
      }
    },
    test("serve URL stays plain under SNAP_COLOR=always (test 28 ruling I)") {
      ZIO.scoped {
        for {
          base <- tempDir("serve-color")
          repo <- initRepo(base, "repo", "a@x")
          cap <- Commands.Output.captured
          fiber <- Commands
            .run(Seq("--serve", "0"), envFor(repo, snapColor = Some("always")), cap.output)
            .fork
          url <- awaitUrl(cap)
          exit <- fiber.interrupt
          out <- cap.stdout
        } yield assertTrue(
          out == url + "\n",
          url.matches("http://127\\.0\\.0\\.1:[0-9]+/repository\\.json"),
          exit.isInterrupted
        )
      }
    }
  ) @@ TestAspect.sequential

  // ---------------------------------------------------------------------------
  // version + presentation
  // ---------------------------------------------------------------------------

  private val presentationSuite = suite("version and presentation (tests 14/28)")(
    test("--version prints the version line and exits 0 (test 14)") {
      ZIO.scoped {
        for {
          base <- tempDir("version")
          r <- runCli("--version")(envFor(base))
        } yield assertTrue(r == ((0, "snap 1.0.0\n", "")))
      }
    },
    test("--version honors SNAP_COLOR=always (test 28)") {
      ZIO.scoped {
        for {
          base <- tempDir("version-color")
          r <- runCli("--version")(envFor(base, snapColor = Some("always")))
        } yield assertTrue(r == ((0, s"${esc}[1msnap 1.0.0${esc}[0m\n", "")))
      }
    },
    test("invalid SNAP_COLOR fails plain before any command runs (test 28)") {
      ZIO.scoped {
        for {
          base <- tempDir("badcolor")
          r <- runCli("status")(envFor(base, snapColor = Some("sometimes")))
          r2 <- runCli("--version")(envFor(base, snapColor = Some("bogus"), isTty = true))
          r3 <- runCli("nonsense")(envFor(base, snapColor = Some("rainbow")))
        } yield assertTrue(
          r == ((1, "", "snap: SNAP_COLOR must be auto, always, or never\n")),
          r2 == ((1, "", "snap: SNAP_COLOR must be auto, always, or never\n")),
          r3 == ((1, "", "snap: SNAP_COLOR must be auto, always, or never\n"))
        )
      }
    },
    test("auto mode uses the TTY flag; NO_COLOR presence forces plain (test 28)") {
      ZIO.scoped {
        for {
          base <- tempDir("tty")
          tty <- runCli("init", "r")(
            envFor(base, noColorPresent = false, isTty = true)
          )
          nocolor <- runCli("status")(
            envFor(base.resolve("r"), noColorPresent = true, isTty = true)
          )
          never <- runCli("status")(
            envFor(base.resolve("r"), snapColor = Some("never"), isTty = true)
          )
        } yield assertTrue(
          tty == ((
            0,
            s"${esc}[32m\u2713${esc}[0m ${esc}[1mInitialized repository${esc}[0m ${esc}[36m()${esc}[0m\n",
            ""
          )),
          nocolor == ((0, "version ()\n", "")),
          never == ((0, "version ()\n", ""))
        )
      }
    },
    test("terminal errors wrap the whole plain line (test 28)") {
      ZIO.scoped {
        for {
          base <- tempDir("tty-error")
          r <- runCli("unknown")(envFor(base, snapColor = Some("always")))
        } yield assertTrue(
          r == ((1, "", s"${esc}[31m\u2717 snap: invalid command or arguments${esc}[0m\n"))
        )
      }
    },
    test("terminal status renders symbols and dim labels (test 28)") {
      ZIO.scoped {
        for {
          base <- tempDir("tty-status")
          repo <- initRepo(base, "repo", "alice@x")
          always = envFor(repo, snapColor = Some("always"), noColorPresent = false, isTty = true)
          _ <- writeText(repo.resolve("added.txt"), "context\nold\n")
          _ <- writeText(repo.resolve("gone.txt"), "gone\n")
          _ <- writeText(repo.resolve("modified.txt"), "before\n")
          r1 <- runCli("status")(always)
          _ <- runCli("commit", "first")(always)
          _ <- writeText(repo.resolve("added.txt"), "context\nnew\n")
          _ <- writeText(repo.resolve("new.txt"), "new\n")
          _ <- ZIO.attemptBlocking(Files.delete(repo.resolve("gone.txt"))).orDie
          _ <- writeText(repo.resolve("modified.txt"), "after\n")
          r2 <- runCli("status")(always)
        } yield assertTrue(
          r1 == ((
            0,
            s"${esc}[1mSnap status${esc}[0m  ${esc}[36m()${esc}[0m\n\n" +
              s"  ${esc}[32m+${esc}[0m added.txt ${esc}[2m(added)${esc}[0m\n" +
              s"  ${esc}[32m+${esc}[0m gone.txt ${esc}[2m(added)${esc}[0m\n" +
              s"  ${esc}[32m+${esc}[0m modified.txt ${esc}[2m(added)${esc}[0m\n",
            ""
          )),
          r2 == ((
            0,
            s"${esc}[1mSnap status${esc}[0m  ${esc}[36m(alice@x->1)${esc}[0m\n\n" +
              s"  ${esc}[33m~${esc}[0m added.txt ${esc}[2m(modified)${esc}[0m\n" +
              s"  ${esc}[31m\u2212${esc}[0m gone.txt ${esc}[2m(deleted)${esc}[0m\n" +
              s"  ${esc}[33m~${esc}[0m modified.txt ${esc}[2m(modified)${esc}[0m\n" +
              s"  ${esc}[32m+${esc}[0m new.txt ${esc}[2m(added)${esc}[0m\n",
            ""
          ))
        )
      }
    },
    test("terminal merge warnings drop the warning prefix (test 28)") {
      ZIO.scoped {
        for {
          base <- tempDir("tty-merge")
          left <- initRepo(base, "left", "a@x")
          right <- initRepo(base, "right", "b@x")
          _ <- writeText(left.resolve("same"), "left\n")
          _ <- writeText(right.resolve("same"), "right\n")
          _ <- runCli("commit", "left")(envFor(left))
          _ <- runCli("commit", "right")(envFor(right))
          r <- runCli("merge", "../right")(
            envFor(left, snapColor = Some("always"), noColorPresent = false, isTty = true)
          )
        } yield assertTrue(
          r == ((
            0,
            s"${esc}[32m\u2713${esc}[0m ${esc}[1mMerged${esc}[0m ${esc}[36m(a@x->1,b@x->1)${esc}[0m\n",
            s"${esc}[33m\u26a0${esc}[0m ${esc}[33mauto-resolved same: later-create-wins${esc}[0m\n"
          ))
        )
      }
    }
  )

  def spec = suite("Commands (L7)")(
    initSuite,
    configSuite,
    statusSuite,
    logSuite,
    commitSuite,
    diffSuite,
    revertSuite,
    mergeSuite,
    serveSuite,
    presentationSuite
  )
}
