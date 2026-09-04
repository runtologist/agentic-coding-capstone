package snap

import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{DirectoryNotEmptyException, Files, LinkOption, NoSuchFileException, Path}
import scala.jdk.CollectionConverters.*

/** Unit tests for the working-tree filesystem layer (SPEC §2, CONTRACT §9). */
object WorkingTreeSpec extends ZIOSpecDefault {

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
      ZIO.attemptBlocking(Files.createTempDirectory(s"wt-$tag"))
    )(dir => ZIO.attemptBlocking(deleteRecursively(dir)).ignoreLogged)

  private def writeFile(path: Path, bytes: Array[Byte]): Unit = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.write(path, bytes)
    ()
  }

  private def writeText(path: Path, s: String): Unit = writeFile(path, s.getBytes(UTF_8))

  private def text(tree: Model.Tree, p: String): Option[String] =
    tree.get(p).map(b => new String(b, UTF_8))

  def spec = suite("WorkingTree")(
    suite("scan")(
      test("empty directory yields an empty tree") {
        ZIO.scoped {
          for {
            dir <- tempDir("empty")
            tree <- WorkingTree.scan(dir)
          } yield assertTrue(tree.isEmpty)
        }
      },
      test("returns paths sorted by unsigned UTF-8 bytes and reads exact contents") {
        ZIO.scoped {
          for {
            dir <- tempDir("sorted")
            _ <- ZIO.attemptBlocking {
              writeText(dir.resolve("z"), "z\n")
              writeText(dir.resolve("\u00e9"), "accent\n")
              writeText(dir.resolve("\uD83D\uDE00"), "emoji\n")
              writeText(dir.resolve("nested").resolve("file"), "nested\n")
            }
            tree <- WorkingTree.scan(dir)
          } yield assertTrue(
            Model.sortedPaths(tree) == Vector("nested/file", "z", "\u00e9", "\uD83D\uDE00"),
            text(tree, "z").contains("z\n"),
            text(tree, "\u00e9").contains("accent\n"),
            text(tree, "nested/file").contains("nested\n")
          )
        }
      },
      test("skips the top-level .snap directory entirely") {
        ZIO.scoped {
          for {
            dir <- tempDir("skipsnap")
            _ <- ZIO.attemptBlocking {
              writeText(dir.resolve("file"), "x")
              writeText(dir.resolve(".snap").resolve("repository.json"), "{}")
              writeText(dir.resolve(".snap").resolve("config.json"), "{}")
            }
            tree <- WorkingTree.scan(dir)
          } yield assertTrue(
            Model.sortedPaths(tree) == Vector("file"),
            !tree.contains(".snap/repository.json")
          )
        }
      },
      test("empty directories are ignored") {
        ZIO.scoped {
          for {
            dir <- tempDir("emptydirs")
            _ <- ZIO.attemptBlocking {
              Files.createDirectories(dir.resolve("empty"))
              Files.createDirectories(dir.resolve("deep").resolve("empty"))
              writeText(dir.resolve("file"), "x")
            }
            tree <- WorkingTree.scan(dir)
          } yield assertTrue(Model.sortedPaths(tree) == Vector("file"))
        }
      },
      test("preserves arbitrary binary bytes including NUL") {
        val binary = java.util.Base64.getDecoder.decode("AP+AQUI=")
        ZIO.scoped {
          for {
            dir <- tempDir("binary")
            _ <- ZIO.attemptBlocking(writeFile(dir.resolve("nul.bin"), binary))
            tree <- WorkingTree.scan(dir)
          } yield assertTrue(tree.get("nul.bin").exists(b => Model.bytesEqual(b, binary)))
        }
      },
      test("a symlink fails the scan with UnsupportedEntry naming the relative path") {
        ZIO.scoped {
          for {
            dir <- tempDir("symlink")
            _ <- ZIO.attemptBlocking(
              Files.createSymbolicLink(dir.resolve("link"), dir.resolve("missing"))
            )
            res <- WorkingTree.scan(dir).either
          } yield assertTrue(res == Left(SnapError.UnsupportedEntry("link")))
        }
      },
      test("a nested symlink is reported with a slash-separated relative path") {
        ZIO.scoped {
          for {
            dir <- tempDir("sym-sub")
            _ <- ZIO.attemptBlocking {
              Files.createDirectories(dir.resolve("sub"))
              Files
                .createSymbolicLink(dir.resolve("sub").resolve("badlink"), dir.resolve("nowhere"))
            }
            res <- WorkingTree.scan(dir).either
          } yield assertTrue(res == Left(SnapError.UnsupportedEntry("sub/badlink")))
        }
      },
      test("a fifo is reported as an unsupported entry") {
        ZIO.scoped {
          for {
            dir <- tempDir("fifo")
            mk <- ZIO.attemptBlocking {
              val pb = new ProcessBuilder("mkfifo", dir.resolve("pipe").toString)
              pb.redirectErrorStream(true)
              pb.start().waitFor()
            }
            res <-
              if (mk == 0) WorkingTree.scan(dir).either
              else ZIO.succeed(Right(Map.empty[String, Array[Byte]]))
          } yield assertTrue(mk != 0 || res == Left(SnapError.UnsupportedEntry("pipe")))
        }
      },
      test("the first unsupported entry in sorted order is reported deterministically") {
        ZIO.scoped {
          for {
            dir <- tempDir("two-links")
            _ <- ZIO.attemptBlocking {
              Files.createSymbolicLink(dir.resolve("zlink"), dir.resolve("m1"))
              Files.createSymbolicLink(dir.resolve("alink"), dir.resolve("m2"))
            }
            res <- WorkingTree.scan(dir).either
          } yield assertTrue(res == Left(SnapError.UnsupportedEntry("alink")))
        }
      },
      test("a file with a backslash in its name fails scan with InvalidRepoPath (E5-F3)") {
        ZIO.scoped {
          for {
            dir <- tempDir("backslash")
            _ <- ZIO.attemptBlocking(writeText(dir.resolve("bad\\name.txt"), "x"))
            res <- WorkingTree.scan(dir).either
          } yield assertTrue(
            res == Left(SnapError.InvalidRepoPath("bad\\name.txt", "contains a backslash"))
          )
        }
      },
      test("a file with a control character in its name fails scan with InvalidRepoPath (E5-F3)") {
        ZIO.scoped {
          for {
            dir <- tempDir("controlchar")
            _ <- ZIO.attemptBlocking(writeText(dir.resolve("bad\u0001name.txt"), "x"))
            res <- WorkingTree.scan(dir).either
          } yield assertTrue(
            res == Left(
              SnapError.InvalidRepoPath("bad\u0001name.txt", "contains a control character")
            )
          )
        }
      },
      test(".snap is still skipped when path validation is active (E5-F3)") {
        ZIO.scoped {
          for {
            dir <- tempDir("snap-skip-valid")
            _ <- ZIO.attemptBlocking {
              writeText(dir.resolve("good.txt"), "ok")
              writeText(dir.resolve(".snap").resolve("repository.json"), "{}")
              writeText(dir.resolve(".snap").resolve("weird\\path"), "{}")
            }
            tree <- WorkingTree.scan(dir)
          } yield assertTrue(
            Model.sortedPaths(tree) == Vector("good.txt"),
            !tree.contains(".snap/repository.json")
          )
        }
      },
      test("valid unicode filenames still pass scan with path validation (E5-F3)") {
        ZIO.scoped {
          for {
            dir <- tempDir("unicode-valid")
            _ <- ZIO.attemptBlocking {
              writeText(dir.resolve("caf\u00e9.txt"), "coffee")
              writeText(dir.resolve("\u65e5\u672c\u8a9e").resolve("file.txt"), "nihongo")
            }
            tree <- WorkingTree.scan(dir)
          } yield assertTrue(
            tree.contains("caf\u00e9.txt"),
            tree.contains("\u65e5\u672c\u8a9e/file.txt"),
            tree.size == 2
          )
        }
      },
      test("first invalid path in sorted order wins over later ones (E5-F3)") {
        ZIO.scoped {
          for {
            dir <- tempDir("first-invalid")
            _ <- ZIO.attemptBlocking {
              writeText(dir.resolve("zz\\bad"), "x")
              writeText(dir.resolve("aa\\bad"), "y")
            }
            res <- WorkingTree.scan(dir).either
          } yield assertTrue(
            res == Left(SnapError.InvalidRepoPath("aa\\bad", "contains a backslash"))
          )
        }
      }
    ),
    suite("compare")(
      test("classifies added, modified, deleted rows sorted by path") {
        val current: Model.Tree = Map(
          "a.txt" -> "old".getBytes(UTF_8),
          "gone.txt" -> "bye".getBytes(UTF_8),
          "same.txt" -> "same".getBytes(UTF_8)
        )
        val working: Model.Tree = Map(
          "a.txt" -> "new".getBytes(UTF_8),
          "same.txt" -> "same".getBytes(UTF_8),
          "added.txt" -> "hi".getBytes(UTF_8)
        )
        assertTrue(
          WorkingTree.compare(current, working) ==
            Vector(
              ("a.txt", Render.StatusKind.Modified),
              ("added.txt", Render.StatusKind.Added),
              ("gone.txt", Render.StatusKind.Deleted)
            )
        )
      },
      test("identical trees produce no rows and report clean") {
        val t: Model.Tree = Map("a" -> "x".getBytes(UTF_8))
        assertTrue(WorkingTree.compare(t, t).isEmpty, WorkingTree.isClean(t, t))
      },
      test("byte differences make the tree dirty") {
        val a: Model.Tree = Map("a" -> "x".getBytes(UTF_8))
        val b: Model.Tree = Map("a" -> "y".getBytes(UTF_8))
        assertTrue(!WorkingTree.isClean(a, b))
      },
      test("an extra working file makes the tree dirty") {
        val a: Model.Tree = Map("a" -> "x".getBytes(UTF_8))
        val b: Model.Tree = a + ("b" -> "y".getBytes(UTF_8))
        assertTrue(!WorkingTree.isClean(a, b))
      }
    ),
    suite("materialize")(
      test("round-trips add, modify and delete to match the target exactly") {
        ZIO.scoped {
          for {
            dir <- tempDir("roundtrip")
            _ <- ZIO.attemptBlocking {
              writeText(dir.resolve("keep.txt"), "keep\n")
              writeText(dir.resolve("change.txt"), "before\n")
              writeText(dir.resolve("drop.txt"), "gone\n")
            }
            target: Model.Tree = Map(
              "keep.txt" -> "keep\n".getBytes(UTF_8),
              "change.txt" -> "after\n".getBytes(UTF_8),
              "new.txt" -> "new\n".getBytes(UTF_8)
            )
            _ <- WorkingTree.materialize(dir, target)
            tree <- WorkingTree.scan(dir)
            dropGone <- ZIO.attemptBlocking(!Files.exists(dir.resolve("drop.txt")))
          } yield assertTrue(
            Model.treeEqual(tree, target),
            dropGone,
            text(tree, "change.txt").contains("after\n")
          )
        }
      },
      test("replaces a file with a directory tree (file to dir, test 07)") {
        ZIO.scoped {
          for {
            dir <- tempDir("file2dir")
            _ <- ZIO.attemptBlocking(writeText(dir.resolve("node"), "file\n"))
            target: Model.Tree = Map("node/child" -> "child\n".getBytes(UTF_8))
            _ <- WorkingTree.materialize(dir, target)
            tree <- WorkingTree.scan(dir)
            nodeIsDir <- ZIO.attemptBlocking(Files.isDirectory(dir.resolve("node")))
            childIsFile <- ZIO.attemptBlocking(
              Files.isRegularFile(dir.resolve("node").resolve("child"))
            )
          } yield assertTrue(
            Model.treeEqual(tree, target),
            nodeIsDir,
            childIsFile
          )
        }
      },
      test("replaces a directory tree with a file (dir to file, test 07)") {
        ZIO.scoped {
          for {
            dir <- tempDir("dir2file")
            _ <- ZIO.attemptBlocking(writeText(dir.resolve("node").resolve("child"), "child\n"))
            target: Model.Tree = Map("node" -> "file\n".getBytes(UTF_8))
            _ <- WorkingTree.materialize(dir, target)
            tree <- WorkingTree.scan(dir)
            nodeIsFile <- ZIO.attemptBlocking(Files.isRegularFile(dir.resolve("node")))
            childGone <- ZIO.attemptBlocking(!Files.exists(dir.resolve("node").resolve("child")))
          } yield assertTrue(
            Model.treeEqual(tree, target),
            nodeIsFile,
            childGone
          )
        }
      },
      test("prunes newly emptied directories but preserves unrelated empty ones") {
        ZIO.scoped {
          for {
            dir <- tempDir("prune")
            _ <- ZIO.attemptBlocking {
              writeText(dir.resolve("a").resolve("b").resolve("c.txt"), "x")
              Files.createDirectories(dir.resolve("keepempty"))
            }
            _ <- WorkingTree.materialize(dir, Map.empty[String, Array[Byte]])
            aGone <- ZIO.attemptBlocking(!Files.exists(dir.resolve("a")))
            keepStill <- ZIO.attemptBlocking(Files.isDirectory(dir.resolve("keepempty")))
          } yield assertTrue(
            aGone,
            keepStill
          )
        }
      },
      test("writes binary content byte-exactly (test 06)") {
        val binary = java.util.Base64.getDecoder.decode("AP+AQUI=")
        ZIO.scoped {
          for {
            dir <- tempDir("bin")
            target: Model.Tree = Map("data.bin" -> binary)
            _ <- WorkingTree.materialize(dir, target)
            read <- ZIO.attemptBlocking(Files.readAllBytes(dir.resolve("data.bin")))
          } yield assertTrue(Model.bytesEqual(read, binary))
        }
      },
      test("never touches the .snap metadata directory") {
        ZIO.scoped {
          for {
            dir <- tempDir("snapdir")
            _ <- ZIO.attemptBlocking(
              writeText(dir.resolve(".snap").resolve("repository.json"), "{}\n")
            )
            _ <- WorkingTree.materialize(dir, Map("f" -> "x".getBytes(UTF_8)))
            snapIntact <- ZIO.attemptBlocking(
              Files.isRegularFile(dir.resolve(".snap").resolve("repository.json"))
            )
            fWritten <- ZIO.attemptBlocking(Files.isRegularFile(dir.resolve("f")))
          } yield assertTrue(
            snapIntact,
            fWritten
          )
        }
      }
    )
  )
}
