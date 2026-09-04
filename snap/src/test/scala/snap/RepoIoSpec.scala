package snap

import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{DirectoryNotEmptyException, Files, LinkOption, NoSuchFileException, Path}
import scala.jdk.CollectionConverters.*

import snap.Model.*

/** Unit tests for repository discovery, loading, atomic persistence and init (SPEC §2, §7.1, §10).
  */
object RepoIoSpec extends ZIOSpecDefault {

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
      ZIO.attemptBlocking(Files.createTempDirectory(s"rio-$tag"))
    )(dir => ZIO.attemptBlocking(deleteRecursively(dir)).ignoreLogged)

  private def writeText(path: Path, s: String): Unit = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.write(path, s.getBytes(UTF_8))
    ()
  }

  private def readText(path: Path): String = new String(Files.readAllBytes(path), UTF_8)

  private def patch(author: String, revision: Long, path: String, content: String): Patch =
    Patch(
      ContributorId(author),
      revision,
      Version.empty,
      s"msg-$revision",
      Vector(Change.Text(path, Vector(EditOp.Insert(Vector(content)))))
    )

  private def singlePatchRepo: Repository =
    Repository(
      Version(Vector(ContributorId("a@x") -> 1L)),
      Vector(patch("a@x", 1L, "f.txt", "x\n"))
    )

  def spec = suite("RepoIo")(
    suite("discoverRepo")(
      test("finds the repository from a nested working directory (test 19)") {
        ZIO.scoped {
          for {
            base <- tempDir("discover")
            repo = base.resolve("repo")
            deep = repo.resolve("sub").resolve("deep")
            _ <- ZIO.attemptBlocking {
              Files.createDirectories(deep)
              Files.createDirectories(repo.resolve(".snap"))
            }
            found <- RepoIo.discoverRepo(deep)
          } yield assertTrue(found.contains(repo.toAbsolutePath.normalize))
        }
      },
      test("finds the repository when started at the repo root") {
        ZIO.scoped {
          for {
            base <- tempDir("discover-root")
            repo = base.resolve("repo")
            _ <- ZIO.attemptBlocking(Files.createDirectories(repo.resolve(".snap")))
            found <- RepoIo.discoverRepo(repo)
          } yield assertTrue(found.contains(repo.toAbsolutePath.normalize))
        }
      },
      test("returns None outside any repository") {
        ZIO.scoped {
          for {
            base <- tempDir("norepo")
            sub = base.resolve("plain").resolve("dir")
            _ <- ZIO.attemptBlocking(Files.createDirectories(sub))
            found <- RepoIo.discoverRepo(sub)
          } yield assertTrue(found.isEmpty)
        }
      },
      test("a .snap that is a regular file does not count as a repository") {
        ZIO.scoped {
          for {
            base <- tempDir("snapfile")
            sub = base.resolve("dir")
            _ <- ZIO.attemptBlocking {
              Files.createDirectories(sub)
              Files.write(base.resolve(".snap"), "not a dir".getBytes(UTF_8))
            }
            found <- RepoIo.discoverRepo(sub)
          } yield assertTrue(found.isEmpty)
        }
      }
    ),
    suite("init")(
      test("creates .snap/repository.json with exact canonical empty bytes (test 01)") {
        ZIO.scoped {
          for {
            base <- tempDir("init")
            target = base.resolve("repo")
            _ <- ZIO.attemptBlocking(Files.createDirectories(target))
            _ <- RepoIo.init(target)
            bytes <- ZIO.attemptBlocking(Files.readAllBytes(RepoIo.repoFile(target)))
            snapIsDir <- ZIO.attemptBlocking(Files.isDirectory(RepoIo.snapDir(target)))
            expected = Json.writeRepository(Repository(Version.empty, Vector.empty))
          } yield assertTrue(
            new String(bytes, UTF_8) == expected,
            snapIsDir
          )
        }
      },
      test("creates intermediate directories for a nested target (test 02)") {
        ZIO.scoped {
          for {
            base <- tempDir("init-nested")
            target = base.resolve("new").resolve("repository")
            _ <- RepoIo.init(target)
            exists <- ZIO.attemptBlocking(Files.isRegularFile(RepoIo.repoFile(target)))
          } yield assertTrue(exists)
        }
      },
      test("preserves existing working files (test 02)") {
        ZIO.scoped {
          for {
            base <- tempDir("init-keep")
            target = base.resolve("repo")
            _ <- ZIO.attemptBlocking(writeText(target.resolve("existing.txt"), "keep me\n"))
            _ <- RepoIo.init(target)
            kept <- ZIO.attemptBlocking(readText(target.resolve("existing.txt")))
          } yield assertTrue(kept == "keep me\n")
        }
      },
      test("reinitializing an existing repository fails (test 02)") {
        ZIO.scoped {
          for {
            base <- tempDir("reinit")
            target = base.resolve("repo")
            _ <- RepoIo.init(target)
            second <- RepoIo.init(target).either
          } yield assertTrue(second == Left(SnapError.RepositoryAlreadyExists))
        }
      },
      test("initializing inside an existing repository fails and creates nothing (test 02)") {
        ZIO.scoped {
          for {
            base <- tempDir("inside")
            repo = base.resolve("repo")
            child = repo.resolve("child")
            _ <- RepoIo.init(repo)
            _ <- ZIO.attemptBlocking(Files.createDirectories(child))
            res <- RepoIo.init(child).either
            childSnapCreated <- ZIO.attemptBlocking(Files.exists(child.resolve(".snap")))
          } yield assertTrue(
            res == Left(SnapError.CannotInitializeInsideRepository),
            !childSnapCreated
          )
        }
      }
    ),
    suite("loadRepository")(
      test("loads and validates a well-formed repository") {
        ZIO.scoped {
          for {
            base <- tempDir("load")
            repo = base.resolve("repo")
            _ <- RepoIo.init(repo)
            value = singlePatchRepo
            _ <- RepoIo.writeRepositoryAtomic(repo, value)
            loaded <- RepoIo.loadRepository(repo)
          } yield assertTrue(loaded == value)
        }
      },
      test("malformed JSON surfaces as invalid JSON without mutation") {
        ZIO.scoped {
          for {
            base <- tempDir("load-bad")
            repo = base.resolve("repo")
            _ <- RepoIo.init(repo)
            _ <- ZIO.attemptBlocking(writeText(RepoIo.repoFile(repo), "not json"))
            before = readText(RepoIo.repoFile(repo))
            res <- RepoIo.loadRepository(repo).either
            after = readText(RepoIo.repoFile(repo))
          } yield assertTrue(
            res.left.exists(_.detail.contains("invalid JSON")),
            after == before
          )
        }
      },
      test("missing repository.json is reported as not a Snap repository") {
        ZIO.scoped {
          for {
            base <- tempDir("load-missing")
            repo = base.resolve("repo")
            _ <- ZIO.attemptBlocking(Files.createDirectories(RepoIo.snapDir(repo)))
            res <- RepoIo.loadRepository(repo).either
          } yield assertTrue(res == Left(SnapError.NotASnapRepository))
        }
      },
      test("semantically invalid history (dependency cycle) is rejected without mutation") {
        val a = ContributorId("a@x")
        val b = ContributorId("b@x")
        val cyclic = Repository(
          Version(Vector(a -> 1L, b -> 1L)),
          Vector(
            Patch(
              a,
              1L,
              Version(Vector(b -> 1L)),
              "a",
              Vector(Change.Text("a.txt", Vector(EditOp.Insert(Vector("a\n")))))
            ),
            Patch(
              b,
              1L,
              Version(Vector(a -> 1L)),
              "b",
              Vector(Change.Text("b.txt", Vector(EditOp.Insert(Vector("b\n")))))
            )
          )
        )
        ZIO.scoped {
          for {
            base <- tempDir("load-cycle")
            repo = base.resolve("repo")
            _ <- RepoIo.init(repo)
            _ <- RepoIo.writeRepositoryAtomic(repo, cyclic)
            before = readText(RepoIo.repoFile(repo))
            res <- RepoIo.loadRepository(repo).either
            after = readText(RepoIo.repoFile(repo))
          } yield assertTrue(
            res == Left(SnapError.CyclicOrIncompleteHistory),
            after == before
          )
        }
      },
      test("invalid UTF-8 bytes in repository.json fail with InvalidJson (E4-P3)") {
        ZIO.scoped {
          for {
            base <- tempDir("load-badutf8")
            repo = base.resolve("repo")
            _ <- RepoIo.init(repo)
            _ <- ZIO.attemptBlocking(
              Files.write(RepoIo.repoFile(repo), Array[Byte](0xff.toByte, 0xfe.toByte))
            )
            res <- RepoIo.loadRepository(repo).either
          } yield assertTrue(
            res == Left(SnapError.InvalidJson("repository is not valid UTF-8"))
          )
        }
      }
    ),
    suite("writeRepositoryAtomic")(
      test("writes canonical bytes and leaves no temp files behind") {
        ZIO.scoped {
          for {
            base <- tempDir("write")
            repo = base.resolve("repo")
            _ <- RepoIo.init(repo)
            value = singlePatchRepo
            _ <- RepoIo.writeRepositoryAtomic(repo, value)
            bytes <- ZIO.attemptBlocking(Files.readAllBytes(RepoIo.repoFile(repo)))
            entries <- ZIO.attemptBlocking(
              Files
                .list(RepoIo.snapDir(repo))
                .iterator()
                .asScala
                .map(_.getFileName.toString)
                .toVector
                .sorted
            )
          } yield assertTrue(
            new String(bytes, UTF_8) == Json.writeRepository(value),
            entries == Vector("repository.json")
          )
        }
      },
      test("round-trips through loadRepository") {
        ZIO.scoped {
          for {
            base <- tempDir("roundtrip")
            repo = base.resolve("repo")
            _ <- RepoIo.init(repo)
            value = singlePatchRepo
            _ <- RepoIo.writeRepositoryAtomic(repo, value)
            loaded <- RepoIo.loadRepository(repo)
          } yield assertTrue(loaded == value)
        }
      }
    )
  )
}
