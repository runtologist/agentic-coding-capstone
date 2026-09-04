package snap

import zio.test.*

import snap.Model
import snap.Model.*
import snap.Model.EditOp.*

/** Unit tests for semantic repository validation (SPEC §4.5) pinned against CONTRACT §7 and the
  * YAML suite (tests 15, 16, 23, 27).
  */
object CodecSpec extends ZIOSpecDefault {

  private def id(s: String): ContributorId = ContributorId.parse(s).toOption.get

  private def v(pairs: (String, Long)*): Version =
    Version(pairs.map { case (a, r) => (id(a), r) }.toVector)

  private def createText(path: String, content: String): Change =
    if (content.isEmpty) Change.Text(path, Vector.empty)
    else Change.Text(path, Vector(Insert(Model.tokenize(content))))

  private def editText(path: String, oldContent: String, newContent: String): Change =
    Change.Text(path, Diff.canonicalDiff(Model.tokenize(oldContent), Model.tokenize(newContent)))

  private def patch(
      author: String,
      rev: Long,
      base: Version,
      msg: String,
      changes: Change*
  ): Patch =
    Patch(id(author), rev, base, msg, changes.toVector)

  private def repo(frontier: Version, patches: Patch*): Repository =
    Repository(frontier, patches.toVector)

  private def leftDetail(e: Either[SnapError, Unit]): String =
    e.left.toOption.map(_.detail).getOrElse("")

  // A minimal valid repository: one author, one text create.
  private val validSingle =
    repo(v("a@x" -> 1), patch("a@x", 1, Version.empty, "one", createText("f", "one\n")))

  // Test-15 golden: two-revision chain creating then editing one file.
  private val validChain = repo(
    v("a@x" -> 2),
    patch("a@x", 1, Version.empty, "base", createText("f", "one\ntwo\n")),
    patch("a@x", 2, v("a@x" -> 1), "edit", editText("f", "one\ntwo\n", "two\nthree\n"))
  )

  // Test-05 golden: repeated.txt old `a\nb\na\n` -> new `b\na\na`, stored edit
  // [{delete 1},{retain 2},{insert ["a"]}] (deletion-on-tie rule).
  private val validRepeatedLines = repo(
    v("a@x" -> 2),
    patch("a@x", 1, Version.empty, "base", createText("repeated.txt", "a\nb\na\n")),
    patch(
      "a@x",
      2,
      v("a@x" -> 1),
      "repeated",
      Change.Text("repeated.txt", Vector(Delete(1), Retain(2), Insert(Vector("a"))))
    )
  )

  def spec = suite("Codec")(
    suite("valid repositories")(
      test("empty repository passes") {
        assertTrue(Codec.validateRepository(repo(Version.empty)) == Right(()))
      },
      test("single text-create patch passes") {
        assertTrue(Codec.validateRepository(validSingle) == Right(()))
      },
      test("multi-revision single-author chain passes") {
        assertTrue(Codec.validateRepository(validChain) == Right(()))
      },
      test("test 05 golden stored edit script passes") {
        assertTrue(Codec.validateRepository(validRepeatedLines) == Right(()))
      },
      test("concurrent two-author history passes") {
        val r = repo(
          v("a@x" -> 1, "b@x" -> 1),
          patch("a@x", 1, Version.empty, "a", createText("a.txt", "a\n")),
          patch("b@x", 1, Version.empty, "b", createText("b.txt", "b\n"))
        )
        assertTrue(Codec.validateRepository(r) == Right(()))
      },
      test("empty text edit creates an empty file and passes") {
        val r = repo(v("a@x" -> 1), patch("a@x", 1, Version.empty, "empty", createText("f", "")))
        assertTrue(Codec.validateRepository(r) == Right(()))
      }
    ),
    suite("frontier canonical form")(
      test("unsorted frontier fails with a canonical-frontier message (test 23)") {
        val r = Repository(v("b@x" -> 1, "a@x" -> 1), Vector.empty)
        val detail = leftDetail(Codec.validateRepository(r))
        assertTrue(
          detail.matches(".*canonical.*"),
          detail.contains("frontier is not canonical")
        )
      },
      test("frontier component without a patch fails as missing") {
        val r = Repository(v("a@x" -> 1), Vector.empty)
        val detail = leftDetail(Codec.validateRepository(r))
        assertTrue(detail.contains("missing a@x revision 1"))
      },
      test("frontier not matching the joined results fails as non-canonical") {
        // All patches are reachable from the declared (b@x->1) frontier, but the joined patch
        // results also include a@x->1, so the declared frontier is incomplete.
        val r = Repository(
          v("b@x" -> 1),
          Vector(
            patch("a@x", 1, Version.empty, "a", createText("a.txt", "a\n")),
            patch("b@x", 1, v("a@x" -> 1), "b", createText("b.txt", "b\n"))
          )
        )
        val detail = leftDetail(Codec.validateRepository(r))
        assertTrue(detail.matches(".*canonical.*"))
      }
    ),
    suite("patch and change ordering")(
      test("unsorted patches fail (test 27)") {
        val r = repo(
          v("a@x" -> 1, "b@x" -> 1),
          patch("b@x", 1, Version.empty, "b", createText("b.txt", "b\n")),
          patch("a@x", 1, Version.empty, "a", createText("a.txt", "a\n"))
        )
        assertTrue(Codec.validateRepository(r).isLeft)
      },
      test("revisions out of order for one author fail (test 27)") {
        val r = repo(
          v("a@x" -> 2),
          patch("a@x", 2, v("a@x" -> 1), "second", createText("g", "g\n")),
          patch("a@x", 1, Version.empty, "first", createText("f", "f\n"))
        )
        assertTrue(Codec.validateRepository(r).isLeft)
      },
      test("unsorted changes fail (test 27)") {
        val p = patch(
          "a@x",
          1,
          Version.empty,
          "order",
          createText("z", "z\n"),
          createText("a", "a\n")
        )
        assertTrue(Codec.validateRepository(repo(v("a@x" -> 1), p)).isLeft)
      },
      test("two changes for one path fail as a tree paths conflict") {
        val p = patch(
          "a@x",
          1,
          Version.empty,
          "dup",
          Change.Put("f", Array[Byte](1)),
          Change.Put("f", Array[Byte](2))
        )
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1), p)))
        assertTrue(detail.contains("tree paths conflict: f"))
      }
    ),
    suite("dot consistency")(
      test("revision != base[author] + 1 fails (test 27)") {
        val p = patch("a@x", 1, v("a@x" -> 1), "wrong dot", createText("f", ""))
        assertTrue(Codec.validateRepository(repo(v("a@x" -> 1), p)).isLeft)
      },
      test("revision jumping over the next base revision fails (test 27)") {
        val p = patch("a@x", 3, v("a@x" -> 1), "jump", createText("f", ""))
        assertTrue(Codec.validateRepository(repo(v("a@x" -> 3), p)).isLeft)
      }
    ),
    suite("one value per dot")(
      test("same dot with different values fails as patch collision (test 16)") {
        val p1 = patch("a@x", 1, Version.empty, "local", createText("f", "local\n"))
        val p2 = patch("a@x", 1, Version.empty, "different", createText("f", "remote\n"))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1), p1, p2)))
        assertTrue(detail.contains("patch collision: a@x revision 1"))
      },
      test("structurally equal duplicate dots collapse and pass (test 26)") {
        val p1 = patch("a@x", 1, Version.empty, "one", createText("f", "one\n"))
        val p2 = patch("a@x", 1, Version.empty, "one", createText("f", "one\n"))
        assertTrue(Codec.validateRepository(repo(v("a@x" -> 1), p1, p2)) == Right(()))
      }
    ),
    suite("contiguity and closure")(
      test("revision gap fails as missing patch (test 15)") {
        val p = patch("a@x", 2, v("a@x" -> 1), "gap", createText("f", ""))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 2), p)))
        assertTrue(detail.contains("missing a@x revision 1"))
      },
      test("base referencing an absent dot fails as missing patch") {
        val p = patch("a@x", 1, v("z@x" -> 1), "dangling", createText("f", ""))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1), p)))
        assertTrue(detail.contains("missing z@x revision 1"))
      },
      test("patch outside the frontier closure fails as unreachable (test 23)") {
        val p = patch("a@x", 1, Version.empty, "unreachable", createText("f", ""))
        val detail = leftDetail(Codec.validateRepository(repo(Version.empty, p)))
        assertTrue(detail.matches("unreachable patch: .+"))
      }
    ),
    suite("acyclic causality")(
      test("two-patch dependency cycle fails (test 15)") {
        val pa = patch("a@x", 1, v("b@x" -> 1), "cycle a", createText("a", ""))
        val pb = patch("b@x", 1, v("a@x" -> 1), "cycle b", createText("b", ""))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1, "b@x" -> 1), pa, pb)))
        assertTrue(detail.contains("cyclic or incomplete patch history"))
      }
    ),
    suite("message and changes")(
      test("empty message fails (test 23)") {
        val p = patch("a@x", 1, Version.empty, "", createText("f", ""))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1), p)))
        assertTrue(detail.endsWith("message is empty"))
      },
      test("empty changes fail (test 23)") {
        val p = Patch(id("a@x"), 1, Version.empty, "none", Vector.empty)
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1), p)))
        assertTrue(detail.endsWith("changes is empty"))
      }
    ),
    suite("edit script shape")(
      test("adjacent same-kind ops fail (test 15)") {
        val p = patch(
          "a@x",
          1,
          Version.empty,
          "adjacent",
          Change.Text("f", Vector(Insert(Vector("a\n")), Insert(Vector("b\n"))))
        )
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1), p)))
        assertTrue(detail.contains("adjacent insert"))
      },
      test("non-canonical insert tokens fail (test 27)") {
        val p = patch(
          "a@x",
          1,
          Version.empty,
          "bad token",
          Change.Text("f", Vector(Insert(Vector("a", "b"))))
        )
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1), p)))
        assertTrue(detail.contains("canonical token sequence"))
      },
      test("insert token with interior LF fails") {
        val p = patch(
          "a@x",
          1,
          Version.empty,
          "interior lf",
          Change.Text("f", Vector(Insert(Vector("a\nb\n"))))
        )
        assertTrue(Codec.validateRepository(repo(v("a@x" -> 1), p)).isLeft)
      }
    ),
    suite("path validity and prefix-free trees")(
      test("path under .snap fails (test 15)") {
        val p = patch("a@x", 1, Version.empty, "bad path", Change.Put(".snap/secret", Array(97)))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1), p)))
        assertTrue(detail.contains("path is invalid"))
      },
      test("path with backslash fails") {
        val p = patch("a@x", 1, Version.empty, "backslash", createText("a\\b", ""))
        assertTrue(Codec.validateRepository(repo(v("a@x" -> 1), p)).isLeft)
      },
      test("prefix conflict within one patch fails (test 15)") {
        val p = patch(
          "a@x",
          1,
          Version.empty,
          "prefix",
          Change.Put("a", Array(97)),
          Change.Put("a/b", Array(98))
        )
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1), p)))
        assertTrue(detail.contains("tree paths conflict"))
      }
    ),
    suite("change versus materialized base")(
      test("delete of absent path fails with the exact message (test 23)") {
        val pa = patch("a@x", 1, Version.empty, "base", createText("f", "one\n"))
        val pb = patch("b@x", 1, Version.empty, "absent", Change.Del("f"))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1, "b@x" -> 1), pa, pb)))
        assertTrue(detail.contains("delete of absent path: f"))
      },
      test("no-op put with identical bytes fails (test 15)") {
        val bytes = Array[Byte](97)
        val p1 = patch("a@x", 1, Version.empty, "base", Change.Put("f", bytes))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "no op", Change.Put("f", bytes.clone()))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 2), p1, p2)))
        assertTrue(detail.contains("no-op change"))
      },
      test("no-op text edit producing identical bytes fails") {
        val p1 = patch("a@x", 1, Version.empty, "base", createText("f", "one\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "noop", Change.Text("f", Vector(Retain(1))))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 2), p1, p2)))
        assertTrue(detail.contains("no-op change"))
      },
      test("text create of a present path fails (test 27)") {
        val p1 = patch("a@x", 1, Version.empty, "base", Change.Put("f", Array(97)))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "create present", Change.Text("f", Vector.empty))
        assertTrue(Codec.validateRepository(repo(v("a@x" -> 2), p1, p2)).isLeft)
      },
      test("text change over a binary base fails (test 27)") {
        val p1 = patch("a@x", 1, Version.empty, "binary", Change.Put("f", Array(0)))
        val p2 =
          patch("a@x", 2, v("a@x" -> 1), "text over binary", Change.Text("f", Vector(Delete(1))))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 2), p1, p2)))
        assertTrue(detail.contains("text change over binary base"))
      },
      test("edit under-consuming old tokens fails (test 15)") {
        val p1 = patch("a@x", 1, Version.empty, "base", createText("f", "one\ntwo\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "underconsume", Change.Text("f", Vector(Retain(1))))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 2), p1, p2)))
        assertTrue(detail.contains("does not consume old content"))
      },
      test("edit consuming beyond old content fails (test 23)") {
        val pa = patch("a@x", 1, Version.empty, "base", createText("f", "one\n"))
        val pb = patch("b@x", 1, Version.empty, "overconsume", Change.Text("f", Vector(Delete(2))))
        val detail = leftDetail(Codec.validateRepository(repo(v("a@x" -> 1, "b@x" -> 1), pa, pb)))
        assertTrue(detail.contains("consumes beyond old content"))
      }
    ),
    suite("cross-repository collision (test 16)")(
      test("different values at one dot collide with the exact message") {
        val local = Vector(patch("a@x", 1, Version.empty, "local", createText("f", "local\n")))
        val remote = Vector(patch("a@x", 1, Version.empty, "different", createText("f", "r\n")))
        val detail = leftDetail(Codec.checkCollision(local, remote))
        assertTrue(detail.contains("patch collision: a@x revision 1"))
      },
      test("structurally equal dots do not collide (test 26)") {
        val local = Vector(patch("a@x", 1, Version.empty, "one", createText("f", "one\n")))
        val remote = Vector(patch("a@x", 1, Version.empty, "one", createText("f", "one\n")))
        assertTrue(Codec.checkCollision(local, remote) == Right(()))
      },
      test("disjoint dots do not collide") {
        val local = Vector(patch("a@x", 1, Version.empty, "one", createText("f", "one\n")))
        val remote = Vector(patch("b@x", 1, Version.empty, "two", createText("g", "two\n")))
        assertTrue(Codec.checkCollision(local, remote) == Right(()))
      }
    ),
    suite("joinedFrontier")(
      test("joins the local frontier with every incoming result (test 21)") {
        val b1 = patch("b@x", 1, v("a@x" -> 1), "b1", createText("g", "b1\n"))
        val b2 = patch("b@x", 2, v("a@x" -> 1, "b@x" -> 1), "b2", createText("g", "b2\n"))
        val joined = Codec.joinedFrontier(v("a@x" -> 2), Vector(b1, b2))
        assertTrue(joined.render == "(a@x->2,b@x->2)")
      },
      test("empty incoming leaves the local frontier unchanged") {
        assertTrue(Codec.joinedFrontier(v("a@x" -> 1), Vector.empty).render == "(a@x->1)")
      }
    )
  )
}
