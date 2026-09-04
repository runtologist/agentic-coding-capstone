package snap

import zio.{Config as _, *}
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{DirectoryNotEmptyException, Files, LinkOption, NoSuchFileException, Path}
import scala.jdk.CollectionConverters.*

import snap.Model.ContributorId

/** Unit tests for contributor configuration read/write (SPEC §8, CONTRACT §8, §15 ruling A). */
object ConfigSpec extends ZIOSpecDefault {

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
      ZIO.attemptBlocking(Files.createTempDirectory(s"cfg-$tag"))
    )(dir => ZIO.attemptBlocking(deleteRecursively(dir)).ignoreLogged)

  private def writeRaw(path: Path, s: String): Unit = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.write(path, s.getBytes(UTF_8))
    ()
  }

  private def readText(path: Path): String = new String(Files.readAllBytes(path), UTF_8)

  private val validId = ContributorId("user@example.com")

  def spec = suite("Config")(
    suite("writeLocal / writeGlobal")(
      test("writeLocal produces canonical pretty bytes (test 03 shape)") {
        ZIO.scoped {
          for {
            base <- tempDir("write-local")
            repo = base.resolve("repo")
            cfgPath = Config.localConfigPath(repo)
            _ <- Config.writeLocal(repo, validId)
            bytes <- ZIO.attemptBlocking(Files.readAllBytes(cfgPath))
          } yield assertTrue(new String(bytes, UTF_8) == Json.writeConfig(Json.ConfigFile(validId)))
        }
      },
      test("writeLocal overwrites and drops unknown fields (test 25)") {
        ZIO.scoped {
          for {
            base <- tempDir("write-drop")
            repo = base.resolve("repo")
            cfgPath = Config.localConfigPath(repo)
            _ <- ZIO.attemptBlocking(
              writeRaw(cfgPath, """{"contributor":{"id":"old@x"},"unknown":true}""")
            )
            _ <- Config.writeLocal(repo, ContributorId("new@x"))
            content <- ZIO.attemptBlocking(readText(cfgPath))
          } yield assertTrue(
            content == Json.writeConfig(Json.ConfigFile(ContributorId("new@x")))
          )
        }
      },
      test("writeGlobal writes $HOME/.snapconfig.json") {
        ZIO.scoped {
          for {
            home <- tempDir("write-global")
            cfgPath = Config.globalConfigPath(home)
            _ <- Config.writeGlobal(home, validId)
            exists <- ZIO.attemptBlocking(Files.isRegularFile(cfgPath))
            content <- ZIO.attemptBlocking(readText(cfgPath))
          } yield assertTrue(
            exists,
            content == Json.writeConfig(Json.ConfigFile(validId))
          )
        }
      }
    ),
    suite("resolveContributor")(
      test("local wins and global is never read when global is malformed (test 03)") {
        ZIO.scoped {
          for {
            base <- tempDir("local-wins")
            repo = base.resolve("repo")
            home = base.resolve("home")
            _ <- ZIO.attemptBlocking {
              writeRaw(
                Config.localConfigPath(repo),
                """{"contributor":{"id":"local@example.com"}}"""
              )
              writeRaw(Config.globalConfigPath(home), "not json")
            }
            res <- Config.resolveContributor(Some(repo), Some(home))
          } yield assertTrue(res == Some(ContributorId("local@example.com")))
        }
      },
      test("missing local falls through to global (test 03)") {
        ZIO.scoped {
          for {
            base <- tempDir("fallthrough")
            repo = base.resolve("repo")
            home = base.resolve("home")
            _ <- ZIO.attemptBlocking(
              writeRaw(
                Config.globalConfigPath(home),
                """{"contributor":{"id":"global@example.com"}}"""
              )
            )
            res <- Config.resolveContributor(Some(repo), Some(home))
          } yield assertTrue(res == Some(ContributorId("global@example.com")))
        }
      },
      test("invalid local id is fatal with no global fallback (test 25)") {
        ZIO.scoped {
          for {
            base <- tempDir("invalid-local")
            repo = base.resolve("repo")
            home = base.resolve("home")
            _ <- ZIO.attemptBlocking {
              writeRaw(Config.localConfigPath(repo), """{"contributor":{"id":"not-an-id"}}""")
              writeRaw(Config.globalConfigPath(home), """{"contributor":{"id":"global@x"}}""")
            }
            res <- Config.resolveContributor(Some(repo), Some(home)).either
          } yield assertTrue(
            res.left.exists(_.isInstanceOf[SnapError.InvalidContributorId])
          )
        }
      },
      test("duplicate key in local config is fatal (test 25)") {
        ZIO.scoped {
          for {
            base <- tempDir("dup-local")
            repo = base.resolve("repo")
            _ <- ZIO.attemptBlocking(
              writeRaw(Config.localConfigPath(repo), """{"contributor":{"id":"a@x","id":"b@x"}}""")
            )
            res <- Config.resolveContributor(Some(repo), None).either
          } yield assertTrue(res.left.exists(_.isInstanceOf[SnapError.DuplicateJsonKey]))
        }
      },
      test("unknown field in local config is fatal") {
        ZIO.scoped {
          for {
            base <- tempDir("unknown-local")
            repo = base.resolve("repo")
            _ <- ZIO.attemptBlocking(
              writeRaw(Config.localConfigPath(repo), """{"contributor":{"id":"a@x"},"extra":1}""")
            )
            res <- Config.resolveContributor(Some(repo), None).either
          } yield assertTrue(res.left.exists(_.isInstanceOf[SnapError.InvalidJson]))
        }
      },
      test("malformed global JSON surfaces invalid JSON (test 03)") {
        ZIO.scoped {
          for {
            base <- tempDir("bad-global")
            home = base.resolve("home")
            _ <- ZIO.attemptBlocking(writeRaw(Config.globalConfigPath(home), "not json"))
            res <- Config.resolveContributor(None, Some(home)).either
          } yield assertTrue(
            res.left.exists(_.detail.contains("invalid JSON"))
          )
        }
      },
      test("trailing bytes after the first global JSON value are tolerated (test 03, ruling A)") {
        ZIO.scoped {
          for {
            base <- tempDir("trailing-global")
            home = base.resolve("home")
            _ <- ZIO.attemptBlocking(
              writeRaw(
                Config.globalConfigPath(home),
                """{"contributor":{"id":"global@example.com"}}}}"""
              )
            )
            res <- Config.resolveContributor(None, Some(home))
          } yield assertTrue(res == Some(ContributorId("global@example.com")))
        }
      },
      test("absent HOME makes global config unavailable, not an error (test 19)") {
        ZIO.scoped {
          for {
            base <- tempDir("no-home")
            repo = base.resolve("repo")
            res <- Config.resolveContributor(Some(repo), None)
          } yield assertTrue(res.isEmpty)
        }
      },
      test("no local and no global file yields None") {
        ZIO.scoped {
          for {
            base <- tempDir("none")
            home = base.resolve("home")
            _ <- ZIO.attemptBlocking(Files.createDirectories(home))
            res <- Config.resolveContributor(None, Some(home))
          } yield assertTrue(res.isEmpty)
        }
      },
      test("no repo root and no home yields None") {
        Config.resolveContributor(None, None).map(res => assertTrue(res.isEmpty))
      }
    )
  )
}
