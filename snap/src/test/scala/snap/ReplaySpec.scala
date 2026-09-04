package snap

import zio.test.*

import snap.Model.*
import snap.Model.EditOp.*

/** Unit tests for deterministic replay (SPEC §6), pinned against the merge/replay YAML goldens
  * (tests 09, 10, 11, 17, 18, 21, 22).
  */
object ReplaySpec extends ZIOSpecDefault {

  private def id(s: String): ContributorId =
    ContributorId.parse(s).fold(e => throw new IllegalStateException(e.detail), identity)

  /** Version from (author, revision) pairs; sorts canonically for convenience. */
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

  private def treeText(tree: Model.Tree, path: String): Option[String] =
    tree.get(path).flatMap(Model.decodeUtf8)

  private def materializeOk(
      patches: Vector[Patch],
      target: Version
  ): (Model.Tree, Vector[ReplayWarning]) =
    Replay
      .materialize(patches, target)
      .fold(e => throw new IllegalStateException(e.detail), identity)

  // Test 09 fixture.
  private val seed09 = patch("seed@x", 1, v(), "base", textCreate("notes.txt", "base\n"))
  private val alice09 =
    patch("alice@x", 1, v("seed@x" -> 1), "left", textEdit("notes.txt", "base\n", "base\nleft\n"))
  private val bob09 =
    patch("bob@x", 1, v("seed@x" -> 1), "right", textEdit("notes.txt", "base\n", "base\nright\n"))
  private val target09 = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)

  // Test 10 fixture.
  private val seed10 = patch(
    "seed@x",
    1,
    v(),
    "base",
    textCreate("delete.txt", "base\n"),
    textCreate("identical.txt", "base\n"),
    textCreate("incompatible.txt", "base\n"),
    textCreate("later-put.txt", "base\n")
  )
  private val alice10 = patch(
    "alice@x",
    1,
    v("seed@x" -> 1),
    "left",
    textEdit("delete.txt", "base\n", "left\n"),
    textEdit("identical.txt", "base\n", "same\n"),
    textEdit("incompatible.txt", "base\n", "left text\n"),
    putBytes("later-put.txt", Array[Byte](0, 1))
  )
  private val bob10 = patch(
    "bob@x",
    1,
    v("seed@x" -> 1),
    "right",
    del("delete.txt"),
    textEdit("identical.txt", "base\n", "same\n"),
    putBytes("incompatible.txt", Array[Byte](0, 0xff.toByte)),
    textEdit("later-put.txt", "base\n", "right text\n")
  )
  private val target10 = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)

  // Test 18 fixture.
  private val seed18 = patch("seed@x", 1, v(), "base", textCreate("story.txt", "start\nend\n"))
  private val a18 = patch(
    "a@x",
    1,
    v("seed@x" -> 1),
    "a",
    Change.Text("story.txt", Vector(Retain(1), Insert(Vector("A\n")), Retain(1)))
  )
  private val b18 = patch(
    "b@x",
    1,
    v("seed@x" -> 1),
    "b",
    Change.Text("story.txt", Vector(Retain(1), Insert(Vector("B\n")), Retain(1)))
  )
  private val c18 = patch(
    "c@x",
    1,
    v("seed@x" -> 1),
    "c",
    Change.Text("story.txt", Vector(Delete(1), Retain(1)))
  )
  private val target18 = v("a@x" -> 1, "b@x" -> 1, "c@x" -> 1, "seed@x" -> 1)

  // Test 21 fixture.
  private val a211 = patch("a@x", 1, v(), "a1", textCreate("story.txt", "base\n"))
  private val a212 =
    patch("a@x", 2, v("a@x" -> 1), "a2", textEdit("story.txt", "base\n", "base\nA2\n"))
  private val b211 =
    patch("b@x", 1, v("a@x" -> 1), "b1", textEdit("story.txt", "base\n", "base\nB1\n"))
  private val b212 =
    patch(
      "b@x",
      2,
      v("a@x" -> 1, "b@x" -> 1),
      "b2",
      textEdit("story.txt", "base\nB1\n", "base\nB1\nB2\n")
    )
  private val patches21 = Vector(a211, a212, b211, b212)
  private val target21 = v("a@x" -> 2, "b@x" -> 2)

  private def base5: Patch = patch("seed@x", 1, v(), "base", textCreate("f", "0\n1\n2\n3\n4\n"))
  private def target22 = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)
  private def otScenario(aliceText: String, bobText: String): Vector[Patch] =
    Vector(
      base5,
      patch("alice@x", 1, v("seed@x" -> 1), "alice", textEdit("f", "0\n1\n2\n3\n4\n", aliceText)),
      patch("bob@x", 1, v("seed@x" -> 1), "bob", textEdit("f", "0\n1\n2\n3\n4\n", bobText))
    )

  def spec = suite("Replay")(
    suite("materialize basics")(
      test("empty patch set materializes the empty tree at the empty version") {
        val result = Replay.materialize(Vector.empty, Version.empty)
        assertTrue(result == Right((Model.emptyTree, Vector.empty)))
      },
      test("single text-create patch produces the file") {
        val (tree, warnings) = materializeOk(Vector(seed09), v("seed@x" -> 1))
        assertTrue(treeText(tree, "notes.txt").contains("base\n"), warnings.isEmpty)
      },
      test("sequential single-author chain reaches the final content") {
        val p1 = patch("a@x", 1, v(), "one", textCreate("f", "one\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "two", textEdit("f", "one\n", "one\ntwo\n"))
        val p3 = patch("a@x", 3, v("a@x" -> 2), "three", textEdit("f", "one\ntwo\n", "two\n"))
        val (tree, warnings) = materializeOk(Vector(p1, p2, p3), v("a@x" -> 3))
        assertTrue(treeText(tree, "f").contains("two\n"), warnings.isEmpty)
      },
      test("materialize at a mid-history version returns the partial tree") {
        val p1 = patch("a@x", 1, v(), "one", textCreate("f", "one\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "two", textEdit("f", "one\n", "one\ntwo\n"))
        val (tree, warnings) = materializeOk(Vector(p1, p2), v("a@x" -> 1))
        assertTrue(treeText(tree, "f").contains("one\n"), warnings.isEmpty)
      },
      test("empty text edit creates an empty file") {
        val p = patch("a@x", 1, v(), "empty", Change.Text("f", Vector.empty))
        val (tree, warnings) = materializeOk(Vector(p), v("a@x" -> 1))
        assertTrue(tree.get("f").exists(_.isEmpty), warnings.isEmpty)
      },
      test("target missing a required base dot fails with MissingPatch") {
        val p1 = patch("a@x", 1, v(), "one", textCreate("f", "one\n"))
        val p2 = patch("b@x", 1, v("a@x" -> 1), "two", textEdit("f", "one\n", "one\ntwo\n"))
        assertTrue(
          Replay.materialize(Vector(p1, p2), v("b@x" -> 1)) ==
            Left(SnapError.MissingPatch("a@x", 1))
        )
      },
      test("replay is deterministic across repeated calls") {
        val r1 = Replay.materialize(Vector(seed09, alice09, bob09), target09)
        val r2 = Replay.materialize(Vector(seed09, alice09, bob09), target09)
        (r1, r2) match {
          case (Right((t1, w1)), Right((t2, w2))) =>
            assertTrue(Model.treeEqual(t1, t2), w1 == w2)
          case _ => assertTrue(false)
        }
      }
    ),
    suite("test 09 line OT")(
      test("concurrent single-line inserts merge to base right left with no warnings") {
        val (tree, warnings) = materializeOk(Vector(seed09, alice09, bob09), target09)
        assertTrue(treeText(tree, "notes.txt").contains("base\nright\nleft\n"), warnings.isEmpty)
      },
      test("input patch order does not change the merged result") {
        val (tree, warnings) = materializeOk(Vector(bob09, seed09, alice09), target09)
        assertTrue(treeText(tree, "notes.txt").contains("base\nright\nleft\n"), warnings.isEmpty)
      }
    ),
    suite("test 10 whole-file rules")(
      test("delete-wins, put-wins, later-put-wins, identical collapse, sorted warnings") {
        val (tree, warnings) = materializeOk(Vector(seed10, alice10, bob10), target10)
        assertTrue(
          !tree.contains("delete.txt"),
          Model.bytesEqual(tree("incompatible.txt"), Array[Byte](0, 0xff.toByte)),
          Model.bytesEqual(tree("later-put.txt"), Array[Byte](0, 1)),
          treeText(tree, "identical.txt").contains("same\n"),
          warnings == Vector(
            ReplayWarning.DeleteWins("delete.txt"),
            ReplayWarning.PutWins("incompatible.txt"),
            ReplayWarning.LaterPutWins("later-put.txt")
          )
        )
      },
      test("input patch order does not change bytes or warnings") {
        val (tree, warnings) = materializeOk(Vector(alice10, seed10, bob10), target10)
        assertTrue(
          !tree.contains("delete.txt"),
          Model.bytesEqual(tree("incompatible.txt"), Array[Byte](0, 0xff.toByte)),
          Model.bytesEqual(tree("later-put.txt"), Array[Byte](0, 1)),
          treeText(tree, "identical.txt").contains("same\n"),
          warnings == Vector(
            ReplayWarning.DeleteWins("delete.txt"),
            ReplayWarning.PutWins("incompatible.txt"),
            ReplayWarning.LaterPutWins("later-put.txt")
          )
        )
      },
      test("identical concurrent text edits collapse without warning") {
        val s = patch("seed@x", 1, v(), "base", textCreate("f", "x\n"))
        val a = patch("alice@x", 1, v("seed@x" -> 1), "a", textEdit("f", "x\n", "y\n"))
        val b = patch("bob@x", 1, v("seed@x" -> 1), "b", textEdit("f", "x\n", "y\n"))
        val (tree, warnings) =
          materializeOk(Vector(s, a, b), v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1))
        assertTrue(treeText(tree, "f").contains("y\n"), warnings.isEmpty)
      }
    ),
    suite("test 11 namespace conflicts")(
      test("file a beats file a/b when a integrates later") {
        val alice = patch("alice@x", 1, v(), "ancestor", textCreate("a", "ancestor\n"))
        val bob = patch("bob@x", 1, v(), "descendant", textCreate("a/b", "descendant\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1)
        val (tree, warnings) = materializeOk(Vector(alice, bob), target)
        assertTrue(
          treeText(tree, "a").contains("ancestor\n"),
          !tree.contains("a/b"),
          warnings == Vector(ReplayWarning.NamespaceWins("a/b"))
        )
      },
      test("file x/y beats file x when x/y integrates later") {
        val bob = patch("bob@x", 1, v(), "ancestor", textCreate("x", "ancestor\n"))
        val alice = patch("alice@x", 1, v(), "descendant", textCreate("x/y", "descendant\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1)
        val (tree, warnings) = materializeOk(Vector(bob, alice), target)
        assertTrue(
          !tree.contains("x"),
          treeText(tree, "x/y").contains("descendant\n"),
          warnings == Vector(ReplayWarning.NamespaceWins("x"))
        )
      },
      test("both merge directions converge to the same tree and warnings") {
        val alice = patch("alice@x", 1, v(), "ancestor", textCreate("a", "ancestor\n"))
        val bob = patch("bob@x", 1, v(), "descendant", textCreate("a/b", "descendant\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1)
        val r1 = materializeOk(Vector(alice, bob), target)
        val r2 = materializeOk(Vector(bob, alice), target)
        assertTrue(Model.treeEqual(r1._1, r2._1), r1._2 == r2._2)
      }
    ),
    suite("test 17 concurrent creates")(
      test("canonically later create wins and warns once") {
        val alice = patch("alice@x", 1, v(), "alice", textCreate("same.txt", "alice\n"))
        val bob = patch("bob@x", 1, v(), "bob", textCreate("same.txt", "bob\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1)
        val (tree, warnings) = materializeOk(Vector(alice, bob), target)
        assertTrue(
          treeText(tree, "same.txt").contains("alice\n"),
          warnings == Vector(ReplayWarning.LaterCreateWins("same.txt"))
        )
      },
      test("reverse input order gives the same winner and warning") {
        val alice = patch("alice@x", 1, v(), "alice", textCreate("same.txt", "alice\n"))
        val bob = patch("bob@x", 1, v(), "bob", textCreate("same.txt", "bob\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1)
        val (tree, warnings) = materializeOk(Vector(bob, alice), target)
        assertTrue(
          treeText(tree, "same.txt").contains("alice\n"),
          warnings == Vector(ReplayWarning.LaterCreateWins("same.txt"))
        )
      },
      test("three concurrent creates on one path deduplicate to one warning") {
        val a = patch("a@x", 1, v(), "a", textCreate("f", "a\n"))
        val b = patch("b@x", 1, v(), "b", textCreate("f", "b\n"))
        val c = patch("c@x", 1, v(), "c", textCreate("f", "c\n"))
        val target = v("a@x" -> 1, "b@x" -> 1, "c@x" -> 1)
        val (tree, warnings) = materializeOk(Vector(a, b, c), target)
        assertTrue(
          treeText(tree, "f").contains("a\n"),
          warnings == Vector(ReplayWarning.LaterCreateWins("f"))
        )
      }
    ),
    suite("test 18 three-way convergence")(
      test("canonical order is seed, c, b, a") {
        Replay.integrationOrder(Vector(a18, b18, c18, seed18)) match {
          case Right(order) =>
            assertTrue(
              order.map(p => (p.author.value, p.revision)) ==
                Vector(("seed@x", 1L), ("c@x", 1L), ("b@x", 1L), ("a@x", 1L))
            )
          case Left(_) => assertTrue(false)
        }
      },
      test("final text is B A end with zero warnings") {
        val (tree, warnings) = materializeOk(Vector(seed18, a18, b18, c18), target18)
        assertTrue(treeText(tree, "story.txt").contains("B\nA\nend\n"), warnings.isEmpty)
      },
      test("all six input association orders give the same result") {
        val expected = "B\nA\nend\n"
        val results = Vector(a18, b18, c18).permutations.map { perm =>
          val (tree, warnings) = materializeOk(Vector(seed18) ++ perm, target18)
          (treeText(tree, "story.txt"), warnings)
        }.toVector
        assertTrue(results.forall { case (text, warnings) =>
          text.contains(expected) && warnings.isEmpty
        })
      }
    ),
    suite("test 21 version algebra support")(
      test("joined frontier materializes base B1 B2 A2") {
        val (tree, warnings) = materializeOk(patches21, target21)
        assertTrue(treeText(tree, "story.txt").contains("base\nB1\nB2\nA2\n"), warnings.isEmpty)
      },
      test("materialize at (a@x->1) gives the seed content") {
        val (tree, warnings) = materializeOk(patches21, v("a@x" -> 1))
        assertTrue(treeText(tree, "story.txt").contains("base\n"), warnings.isEmpty)
      },
      test("materialize at (a@x->1,b@x->2) gives base B1 B2") {
        val (tree, warnings) = materializeOk(patches21, v("a@x" -> 1, "b@x" -> 2))
        assertTrue(treeText(tree, "story.txt").contains("base\nB1\nB2\n"), warnings.isEmpty)
      },
      test("materialize at (a@x->2) gives base A2") {
        val (tree, warnings) = materializeOk(patches21, v("a@x" -> 2))
        assertTrue(treeText(tree, "story.txt").contains("base\nA2\n"), warnings.isEmpty)
      }
    ),
    suite("test 22 OT matrix via replay")(
      test("overlapping deletes: same base token deleted only once") {
        val (tree, warnings) =
          materializeOk(otScenario("0\n3\n4\n", "0\n2\n3\n4\n"), target22)
        assertTrue(treeText(tree, "f").contains("0\n3\n4\n"), warnings.isEmpty)
      },
      test("split counts, insert priority, and trailing insert") {
        val (tree, warnings) =
          materializeOk(otScenario("A\n0\n3\n4\nTAIL\n", "0\n1\nB\n3\n4\n"), target22)
        assertTrue(treeText(tree, "f").contains("A\n0\nB\n3\n4\nTAIL\n"), warnings.isEmpty)
      },
      test("retained token deleted by context stays deleted; trailing insert survives") {
        val (tree, warnings) =
          materializeOk(otScenario("0\n1\n2\n3\n4\nA\n", "0\n2\n3\n4\n"), target22)
        assertTrue(treeText(tree, "f").contains("0\n2\n3\n4\nA\n"), warnings.isEmpty)
      },
      test("context insert before an incoming deletion survives") {
        val (tree, warnings) =
          materializeOk(otScenario("0\n2\n3\n4\n", "0\nB\n1\n2\n3\n4\n"), target22)
        assertTrue(treeText(tree, "f").contains("0\nB\n2\n3\n4\n"), warnings.isEmpty)
      }
    ),
    suite("delete versus edit")(
      test("delete over concurrent edit wins with one warning") {
        val s = patch("seed@x", 1, v(), "base", textCreate("f", "base\n"))
        val a = patch("alice@x", 1, v("seed@x" -> 1), "del", del("f"))
        val b = patch("bob@x", 1, v("seed@x" -> 1), "edit", textEdit("f", "base\n", "edited\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)
        val (tree, warnings) = materializeOk(Vector(s, a, b), target)
        assertTrue(!tree.contains("f"), warnings == Vector(ReplayWarning.DeleteWins("f")))
      },
      test("edit over concurrent delete loses with delete-wins regardless of input order") {
        val s = patch("seed@x", 1, v(), "base", textCreate("f", "base\n"))
        val a = patch("alice@x", 1, v("seed@x" -> 1), "del", del("f"))
        val b = patch("bob@x", 1, v("seed@x" -> 1), "edit", textEdit("f", "base\n", "edited\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)
        val (tree, warnings) = materializeOk(Vector(b, s, a), target)
        assertTrue(!tree.contains("f"), warnings == Vector(ReplayWarning.DeleteWins("f")))
      }
    ),
    suite("integrationOrder")(
      test("cycle is rejected") {
        val p1 = patch("a@x", 1, v("b@x" -> 1), "a", textCreate("a", "a\n"))
        val p2 = patch("b@x", 1, v("a@x" -> 1), "b", textCreate("b", "b\n"))
        assertTrue(
          Replay.integrationOrder(Vector(p1, p2)) == Left(SnapError.CyclicOrIncompleteHistory)
        )
      },
      test("missing base dot is rejected") {
        val p = patch("a@x", 1, v("ghost@x" -> 1), "a", textCreate("a", "a\n"))
        assertTrue(Replay.integrationOrder(Vector(p)) == Left(SnapError.MissingPatch("ghost@x", 1)))
      },
      test("structurally equal duplicate dots collapse to one patch") {
        val p = patch("a@x", 1, v(), "a", textCreate("a", "a\n"))
        Replay.integrationOrder(Vector(p, p)) match {
          case Right(order) => assertTrue(order.length == 1)
          case Left(_)      => assertTrue(false)
        }
      },
      test("different values at one dot are a collision") {
        val p1 = patch("a@x", 1, v(), "a", textCreate("a", "a\n"))
        val p2 = patch("a@x", 1, v(), "other", textCreate("a", "a\n"))
        assertTrue(
          Replay.integrationOrder(Vector(p1, p2)) == Left(SnapError.PatchCollision("a@x", 1))
        )
      }
    )
  )
}
