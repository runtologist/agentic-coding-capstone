package snap

import zio.test.*

import snap.Model.*
import snap.Model.EditOp.*

/** Unit tests for semantic repository validation (SPEC §4.5), pinned against the YAML suite error
  * contracts (tests 15, 16, 23, 26, 27).
  */
object CodecSpec extends ZIOSpecDefault {

  private def id(s: String): ContributorId =
    ContributorId.parse(s).fold(e => throw new IllegalStateException(e.detail), identity)

  private def v(pairs: (String, Long)*): Version =
    Version(
      pairs
        .map { case (a, r) => (id(a), r) }
        .toVector
        .sortWith((x, y) => Model.utf8Compare(x._1.value, y._1.value) < 0)
    )

  private def textCreate(path: String, content: String): Change.Text = {
    val toks = Model.tokenize(content)
    Change.Text(path, if (toks.isEmpty) Vector.empty else Vector(Insert(toks)))
  }

  private def textEdit(path: String, oldContent: String, newContent: String): Change.Text =
    Change.Text(path, Diff.canonicalDiff(Model.tokenize(oldContent), Model.tokenize(newContent)))

  private def putText(path: String, content: String): Change.Put =
    Change.Put(path, Model.utf8Bytes(content))

  private def putBytes(path: String, bytes: Array[Byte]): Change.Put =
    Change.Put(path, bytes)

  private def del(path: String): Change.Del = Change.Del(path)

  private def patch(
      author: String,
      rev: Long,
      base: Version,
      message: String,
      changes: Change*
  ): Patch = Patch(id(author), rev, base, message, changes.toVector)

  private def repo(frontier: Version, patches: Patch*): Repository =
    Repository(frontier, patches.toVector)

  private def assertError(r: Either[SnapError, Unit])(f: SnapError => Boolean): TestResult =
    r match {
      case Left(e)  => assertTrue(f(e))
      case Right(_) => assertTrue(false)
    }

  private val validSeed = patch("seed@x", 1, v(), "base", textCreate("notes.txt", "base\n"))

  /** Test-09 shaped repository: seed + two concurrent text edits, stored sorted by author. */
  private val validMultiAuthor = repo(
    v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1),
    patch("alice@x", 1, v("seed@x" -> 1), "left", textEdit("notes.txt", "base\n", "base\nleft\n")),
    patch("bob@x", 1, v("seed@x" -> 1), "right", textEdit("notes.txt", "base\n", "base\nright\n")),
    validSeed
  )

  def spec = suite("Codec")(
    suite("validateRepository — valid repositories")(
      test("empty repository is valid") {
        assertTrue(Codec.validateRepository(repo(v())) == Right(()))
      },
      test("single create patch is valid") {
        assertTrue(Codec.validateRepository(repo(v("seed@x" -> 1), validSeed)) == Right(()))
      },
      test("multi-author concurrent history is valid") {
        assertTrue(Codec.validateRepository(validMultiAuthor) == Right(()))
      },
      test("structurally identical duplicate dots collapse and stay valid") {
        assertTrue(
          Codec.validateRepository(repo(v("seed@x" -> 1), validSeed, validSeed)) == Right(())
        )
      },
      test("binary put followed by a different put replacement is valid") {
        val p1 = patch("a@x", 1, v(), "one", putBytes("f", Array[Byte](0, 1)))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "two", putBytes("f", Array[Byte](0, 2)))
        assertTrue(Codec.validateRepository(repo(v("a@x" -> 2), p1, p2)) == Right(()))
      },
      test("empty text edit creating an empty file is valid") {
        val p = patch("a@x", 1, v(), "empty", Change.Text("f", Vector.empty))
        assertTrue(Codec.validateRepository(repo(v("a@x" -> 1), p)) == Right(()))
      }
    ),
    suite("validateRepository — frontier and ordering")(
      test("unsorted frontier is rejected as non-canonical (test 23)") {
        val frontier = Version(Vector((id("b@x"), 1L), (id("a@x"), 1L)))
        val r = Codec.validateRepository(Repository(frontier, Vector.empty))
        assertError(r) {
          case e: SnapError.NonCanonicalFrontier => e.detail.contains("canonical")
          case _                                 => false
        }
      },
      test("duplicate contributor in the frontier is rejected") {
        val frontier = Version(Vector((id("a@x"), 1L), (id("a@x"), 2L)))
        val r = Codec.validateRepository(Repository(frontier, Vector.empty))
        assertError(r)(_.isInstanceOf[SnapError.NonCanonicalFrontier])
      },
      test("unsorted patches are rejected (test 27)") {
        val pb = patch("b@x", 1, v(), "b", textCreate("b", "b\n"))
        val pa = patch("a@x", 1, v(), "a", textCreate("a", "a\n"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1, "b@x" -> 1), pb, pa))
        assertError(r)(_.detail.contains("not sorted"))
      },
      test("unsorted changes within a patch are rejected (test 27)") {
        val p = patch("a@x", 1, v(), "order", putText("z", "z\n"), putText("a", "a\n"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertError(r)(_.detail.contains("not sorted by path"))
      },
      test("duplicate change path within a patch is rejected") {
        val p = patch("a@x", 1, v(), "dup", putText("f", "x"), putText("f", "y"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertTrue(r == Left(SnapError.TreePathsConflict("f")))
      }
    ),
    suite("validateRepository — dots and history")(
      test("revision != base[author] + 1 is rejected (test 27)") {
        val p = patch("a@x", 1, v("a@x" -> 1), "wrong dot", textCreate("f", "f\n"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertTrue(r == Left(SnapError.CyclicOrIncompleteHistory))
      },
      test("different values at one dot are a collision (test 16)") {
        val p1 = patch("a@x", 1, v(), "local", textCreate("file.txt", "local\n"))
        val p2 = patch("a@x", 1, v(), "different", textCreate("file.txt", "remote\n"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p1, p2))
        assertTrue(r == Left(SnapError.PatchCollision("a@x", 1))) &&
        assertTrue(r.swap.toOption.get.detail == "patch collision: a@x revision 1")
      },
      test("revision gap reports the missing patch (test 15)") {
        val p = patch("a@x", 2, v("a@x" -> 1), "gap", textCreate("f", "f\n"))
        val r = Codec.validateRepository(repo(v("a@x" -> 2), p))
        assertTrue(r == Left(SnapError.MissingPatch("a@x", 1))) &&
        assertTrue(r.swap.toOption.get.detail.contains("missing a@x"))
      },
      test("base dot without a patch is reported missing") {
        val p = patch("a@x", 1, v("ghost@x" -> 1), "ghost", textCreate("f", "f\n"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertTrue(r == Left(SnapError.MissingPatch("ghost@x", 1)))
      },
      test("patch with an empty frontier is unreachable (test 23)") {
        val p = patch("a@x", 1, v(), "unreachable", textCreate("f", "f\n"))
        val r = Codec.validateRepository(repo(v(), p))
        assertTrue(r == Left(SnapError.UnreachablePatch("a@x", 1))) &&
        assertTrue(r.swap.toOption.get.detail.startsWith("unreachable patch:"))
      },
      test("patch dot beyond the frontier is unreachable") {
        val p1 = patch("a@x", 1, v(), "one", textCreate("f", "one\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "two", textEdit("f", "one\n", "one\ntwo\n"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p1, p2))
        assertTrue(r == Left(SnapError.UnreachablePatch("a@x", 2)))
      },
      test("dependency cycle is rejected (test 15)") {
        val pa = patch("a@x", 1, v("b@x" -> 1), "cycle a", textCreate("a", "a\n"))
        val pb = patch("b@x", 1, v("a@x" -> 1), "cycle b", textCreate("b", "b\n"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1, "b@x" -> 1), pa, pb))
        assertTrue(r == Left(SnapError.CyclicOrIncompleteHistory))
      }
    ),
    suite("validateRepository — patch structure")(
      test("empty message is rejected (test 23)") {
        val p = patch("a@x", 1, v(), "", textCreate("f", "f\n"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertError(r) {
          case SnapError.EmptyField(_, field) => field == "message"
          case _                              => false
        }
      },
      test("empty changes are rejected (test 23)") {
        val p = Patch(id("a@x"), 1, v(), "none", Vector.empty)
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertError(r) {
          case SnapError.EmptyField(_, field) => field == "changes"
          case _                              => false
        }
      },
      test("message with a forbidden control character is rejected") {
        val p = patch("a@x", 1, v(), "bad\u0001msg", textCreate("f", "f\n"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertError(r)(_.isInstanceOf[SnapError.InvalidMessage])
      },
      test("adjacent same-kind edit ops are rejected (test 15)") {
        val p = patch(
          "a@x",
          1,
          v(),
          "adjacent",
          Change.Text("f", Vector(Insert(Vector("a\n")), Insert(Vector("b\n"))))
        )
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertTrue(r == Left(SnapError.AdjacentSameKindOps("insert"))) &&
        assertTrue(r.swap.toOption.get.detail.contains("adjacent insert"))
      },
      test("zero retain count is rejected (test 23)") {
        val p = patch("a@x", 1, v(), "bad count", Change.Text("f", Vector(Retain(0))))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertError(r) {
          case e: SnapError.NotPositiveSafeInteger => e.detail.endsWith("positive safe integer")
          case _                                   => false
        }
      },
      test("negative delete count is rejected") {
        val p1 = patch("a@x", 1, v(), "one", textCreate("f", "f\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "neg", Change.Text("f", Vector(Delete(-1))))
        val r = Codec.validateRepository(repo(v("a@x" -> 2), p1, p2))
        assertError(r)(_.isInstanceOf[SnapError.NotPositiveSafeInteger])
      },
      test("empty insert array is rejected (test 23)") {
        val p = patch("a@x", 1, v(), "empty insert", Change.Text("f", Vector(Insert(Vector.empty))))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertError(r) {
          case SnapError.EmptyField(_, field) => field == "insert"
          case _                              => false
        }
      },
      test("non-canonical insert token sequence is rejected (test 27)") {
        val p =
          patch("a@x", 1, v(), "bad token", Change.Text("f", Vector(Insert(Vector("a", "b")))))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertError(r)(_.isInstanceOf[SnapError.NonCanonicalTokens])
      },
      test("insert token with an interior line break is rejected") {
        val p =
          patch("a@x", 1, v(), "bad token", Change.Text("f", Vector(Insert(Vector("a\nb\n")))))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertError(r)(_.isInstanceOf[SnapError.NonCanonicalTokens])
      },
      test("insert token containing NUL is rejected at change-vs-base") {
        val p = patch("a@x", 1, v(), "nul", Change.Text("f", Vector(Insert(Vector("a\u0000")))))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertError(r)(_.isInstanceOf[SnapError.NonCanonicalTokens])
      },
      test("path under .snap is rejected (test 15)") {
        val p = patch("a@x", 1, v(), "bad path", putText(".snap/secret", "a"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertError(r) {
          case e: SnapError.InvalidRepoPath => e.detail.startsWith("path is invalid")
          case _                            => false
        }
      },
      test("path with a dot-dot segment is rejected") {
        val p = patch("a@x", 1, v(), "bad path", putText("a/../b", "a"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertError(r)(_.isInstanceOf[SnapError.InvalidRepoPath])
      },
      test("prefix-conflicting change paths are rejected (test 15)") {
        val p = patch("a@x", 1, v(), "prefix", putText("a", "a"), putText("a/b", "b"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1), p))
        assertTrue(r == Left(SnapError.TreePathsConflict("a/b"))) &&
        assertTrue(r.swap.toOption.get.detail.contains("tree paths conflict"))
      }
    ),
    suite("validateRepository — changes against base")(
      test("delete of an absent path is rejected (test 23)") {
        val pa = patch("a@x", 1, v(), "base", putText("f", "a"))
        val pb = patch("b@x", 1, v(), "absent", del("f"))
        val r = Codec.validateRepository(repo(v("a@x" -> 1, "b@x" -> 1), pa, pb))
        assertTrue(r == Left(SnapError.DeleteOfAbsentPath("f"))) &&
        assertTrue(r.swap.toOption.get.detail == "delete of absent path: f")
      },
      test("empty text edit over a present path is rejected as create-of-present (test 27)") {
        val p1 = patch("a@x", 1, v(), "one", putText("f", "a"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "create present", Change.Text("f", Vector.empty))
        val r = Codec.validateRepository(repo(v("a@x" -> 2), p1, p2))
        assertError(r)(_.isInstanceOf[SnapError.CreateOfPresentPath])
      },
      test("text change over a binary base is rejected (test 27)") {
        val p1 = patch("a@x", 1, v(), "binary", putBytes("f", Array[Byte](0)))
        val p2 =
          patch("a@x", 2, v("a@x" -> 1), "text over binary", Change.Text("f", Vector(Delete(1))))
        val r = Codec.validateRepository(repo(v("a@x" -> 2), p1, p2))
        assertError(r)(_.isInstanceOf[SnapError.TextOverBinaryBase])
      },
      test("edit that under-consumes the old tokens is rejected (test 15)") {
        val p1 = patch("a@x", 1, v(), "base", textCreate("f", "one\ntwo\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "underconsume", Change.Text("f", Vector(Retain(1))))
        val r = Codec.validateRepository(repo(v("a@x" -> 2), p1, p2))
        assertError(r) {
          case e: SnapError.EditNotConsuming => e.detail.contains("does not consume old content")
          case _                             => false
        }
      },
      test("edit that consumes beyond the old content is rejected (test 23)") {
        val p1 = patch("a@x", 1, v(), "base", textCreate("f", "one\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "overconsume", Change.Text("f", Vector(Delete(2))))
        val r = Codec.validateRepository(repo(v("a@x" -> 2), p1, p2))
        assertError(r) {
          case e: SnapError.EditOverconsumes => e.detail.contains("consumes beyond old content")
          case _                             => false
        }
      },
      test("put with identical bytes is rejected as no-op (test 15)") {
        val p1 = patch("a@x", 1, v(), "one", putText("f", "a"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "no op", putText("f", "a"))
        val r = Codec.validateRepository(repo(v("a@x" -> 2), p1, p2))
        assertTrue(r == Left(SnapError.NoOpChange("f"))) &&
        assertTrue(r.swap.toOption.get.detail.contains("no-op change"))
      },
      test("text edit producing identical bytes is rejected as no-op") {
        val p1 = patch("a@x", 1, v(), "one", textCreate("f", "x\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "no op", Change.Text("f", Vector(Retain(1))))
        val r = Codec.validateRepository(repo(v("a@x" -> 2), p1, p2))
        assertError(r)(_.isInstanceOf[SnapError.NoOpChange])
      }
    ),
    suite("checkCollision")(
      test("same dot with different values fails with the pinned message (test 16)") {
        val local = Vector(patch("a@x", 1, v(), "local", textCreate("file.txt", "local\n")))
        val remote = Vector(patch("a@x", 1, v(), "different", textCreate("file.txt", "remote\n")))
        val r = Codec.checkCollision(local, remote)
        assertTrue(r == Left(SnapError.PatchCollision("a@x", 1))) &&
        assertTrue(r.swap.toOption.get.detail == "patch collision: a@x revision 1")
      },
      test("structurally equal dots are duplicates, not collisions (test 26)") {
        val local = Vector(patch("a@x", 1, v(), "same", textCreate("f", "same\n")))
        val remote = Vector(patch("a@x", 1, v(), "same", textCreate("f", "same\n")))
        assertTrue(Codec.checkCollision(local, remote) == Right(()))
      },
      test("disjoint dot sets never collide") {
        val local = Vector(patch("a@x", 1, v(), "a", textCreate("a", "a\n")))
        val remote = Vector(patch("b@x", 1, v(), "b", textCreate("b", "b\n")))
        assertTrue(Codec.checkCollision(local, remote) == Right(()))
      }
    ),
    suite("joinedFrontier")(
      test("componentwise join over incoming patch results (test 21)") {
        val a1 = patch("a@x", 1, v(), "a1", textCreate("story.txt", "base\n"))
        val a2 = patch("a@x", 2, v("a@x" -> 1), "a2", textEdit("story.txt", "base\n", "base\nA2\n"))
        val b1 = patch("b@x", 1, v("a@x" -> 1), "b1", textEdit("story.txt", "base\n", "base\nB1\n"))
        val b2 = patch(
          "b@x",
          2,
          v("a@x" -> 1, "b@x" -> 1),
          "b2",
          textEdit("story.txt", "base\nB1\n", "base\nB1\nB2\n")
        )
        val joined = Codec.joinedFrontier(v("a@x" -> 2), Vector(b1, b2))
        assertTrue(joined.render == "(a@x->2,b@x->2)") &&
        assertTrue(
          Codec
            .joinedFrontier(v("a@x" -> 1, "b@x" -> 2), Vector(a1, a2))
            .render == "(a@x->2,b@x->2)"
        )
      }
    ),
    suite("knownVersion")(
      test("version within a closed frontier is known") {
        val p1 = patch("a@x", 1, v(), "one", textCreate("f", "one\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "two", textEdit("f", "one\n", "one\ntwo\n"))
        val r = repo(v("a@x" -> 2), p1, p2)
        assertTrue(Codec.knownVersion(r, v("a@x" -> 1)) == Right(()))
      },
      test("version beyond the frontier is unknown") {
        val p1 = patch("a@x", 1, v(), "one", textCreate("f", "one\n"))
        val r = repo(v("a@x" -> 1), p1)
        assertError(Codec.knownVersion(r, v("a@x" -> 9)))(_.isInstanceOf[SnapError.UnknownVersion])
      },
      test("version whose selection is not base-closed is unknown") {
        val p1 = patch("a@x", 1, v(), "one", textCreate("f", "one\n"))
        val p2 = patch("b@x", 1, v("a@x" -> 1), "two", textEdit("f", "one\n", "one\ntwo\n"))
        val r = repo(v("a@x" -> 1, "b@x" -> 1), p1, p2)
        assertTrue(Codec.knownVersion(r, v("b@x" -> 1)) == Left(SnapError.MissingPatch("a@x", 1)))
      }
    )
  )
}
