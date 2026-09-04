package snap

import zio.test.*

import snap.Model
import snap.Model.*
import snap.Model.EditOp.*

/** Unit tests for deterministic replay (SPEC §6) pinned against CONTRACT §11 and the YAML goldens
  * (tests 09, 10, 11, 17, 18, 21, 22).
  */
object ReplaySpec extends ZIOSpecDefault {

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

  private def ok[A](e: Either[SnapError, A]): A = e.toOption.get

  private def treeText(tree: Model.Tree, path: String): String =
    Model.decodeUtf8(tree(path)).get

  private def materializeOk(
      patches: Vector[Patch],
      target: Version
  ): (Model.Tree, Vector[ReplayWarning]) =
    ok(Replay.materialize(patches, target))

  def spec = suite("Replay")(
    suite("materialize basics")(
      test("empty patch set yields empty tree and no warnings") {
        val (tree, ws) = materializeOk(Vector.empty, Version.empty)
        assertTrue(tree.isEmpty, ws.isEmpty)
      },
      test("single text create installs the file bytes") {
        val p = patch("a@x", 1, Version.empty, "one", createText("f", "one\n"))
        val (tree, ws) = materializeOk(Vector(p), v("a@x" -> 1))
        assertTrue(treeText(tree, "f") == "one\n", ws.isEmpty)
      },
      test("single put create installs binary bytes") {
        val bytes = Array[Byte](0, 1, 2)
        val p = patch("a@x", 1, Version.empty, "bin", Change.Put("data.bin", bytes))
        val (tree, _) = materializeOk(Vector(p), v("a@x" -> 1))
        assertTrue(Model.bytesEqual(tree("data.bin"), bytes))
      },
      test("empty text edit creates an empty file (test 06 semantics)") {
        val p = patch("a@x", 1, Version.empty, "empty", createText("empty", ""))
        val (tree, _) = materializeOk(Vector(p), v("a@x" -> 1))
        assertTrue(tree.contains("empty"), tree("empty").length == 0)
      },
      test("sequential single-author chain applies edits in order") {
        val p1 = patch("a@x", 1, Version.empty, "one", createText("f", "one\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "two", editText("f", "one\n", "one\ntwo\n"))
        val p3 = patch("a@x", 3, v("a@x" -> 2), "three", editText("f", "one\ntwo\n", "two\n"))
        val (tree, ws) = materializeOk(Vector(p1, p2, p3), v("a@x" -> 3))
        assertTrue(treeText(tree, "f") == "two\n", ws.isEmpty)
      },
      test("delete removes the path from the tree") {
        val p1 = patch("a@x", 1, Version.empty, "one", createText("f", "one\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "del", Change.Del("f"))
        val (tree, ws) = materializeOk(Vector(p1, p2), v("a@x" -> 2))
        assertTrue(!tree.contains("f"), ws.isEmpty)
      },
      test("materialize at a mid-history version stops at that version (test 21 shape)") {
        val a1 = patch("a@x", 1, Version.empty, "a1", createText("story.txt", "base\n"))
        val a2 = patch("a@x", 2, v("a@x" -> 1), "a2", editText("story.txt", "base\n", "base\nA2\n"))
        val b1 = patch("b@x", 1, v("a@x" -> 1), "b1", editText("story.txt", "base\n", "base\nB1\n"))
        val b2 =
          patch(
            "b@x",
            2,
            v("a@x" -> 1, "b@x" -> 1),
            "b2",
            editText("story.txt", "base\nB1\n", "base\nB1\nB2\n")
          )
        val (tree, ws) = materializeOk(Vector(a1, a2, b1, b2), v("a@x" -> 1, "b@x" -> 2))
        assertTrue(treeText(tree, "story.txt") == "base\nB1\nB2\n", ws.isEmpty)
      },
      test("target missing a base dependency fails as cyclic or incomplete") {
        // a2's base references a@x->1 but only a2 is supplied.
        val a2 = patch("a@x", 2, v("a@x" -> 1), "a2", createText("f", "x\n"))
        assertTrue(
          Replay
            .materialize(Vector(a2), v("a@x" -> 2))
            .left
            .toOption
            .contains(SnapError.CyclicOrIncompleteHistory)
        )
      },
      test("replay is deterministic across repeated calls") {
        val p1 = patch("a@x", 1, Version.empty, "one", createText("f", "one\n"))
        val p2 = patch("b@x", 1, Version.empty, "two", createText("f", "two\n"))
        val r1 = materializeOk(Vector(p1, p2), v("a@x" -> 1, "b@x" -> 1))
        val r2 = materializeOk(Vector(p1, p2), v("a@x" -> 1, "b@x" -> 1))
        assertTrue(Model.treeEqual(r1._1, r2._1), r1._2 == r2._2)
      }
    ),
    suite("integrationOrder (SPEC §6.1)")(
      test("orders a causal chain base-first") {
        val p1 = patch("a@x", 1, Version.empty, "one", createText("f", "one\n"))
        val p2 = patch("a@x", 2, v("a@x" -> 1), "two", createText("g", "two\n"))
        val order = ok(Replay.integrationOrder(Vector(p2, p1)))
        assertTrue(order.map(_.revision) == Vector(1L, 2L))
      },
      test("test 09: concurrent seed/alice/bob order is seed, bob, alice") {
        val seed = patch("seed@x", 1, Version.empty, "base", createText("notes.txt", "base\n"))
        val alice =
          patch(
            "alice@x",
            1,
            v("seed@x" -> 1),
            "left",
            editText("notes.txt", "base\n", "base\nleft\n")
          )
        val bob =
          patch(
            "bob@x",
            1,
            v("seed@x" -> 1),
            "right",
            editText("notes.txt", "base\n", "base\nright\n")
          )
        val order = ok(Replay.integrationOrder(Vector(alice, seed, bob)))
        assertTrue(order.map(_.author.value) == Vector("seed@x", "bob@x", "alice@x"))
      },
      test("test 18: three-way order after seed is c, b, a") {
        val seed =
          patch("seed@x", 1, Version.empty, "base", createText("story.txt", "start\nend\n"))
        val a = patch(
          "a@x",
          1,
          v("seed@x" -> 1),
          "a",
          editText("story.txt", "start\nend\n", "start\nA\nend\n")
        )
        val b = patch(
          "b@x",
          1,
          v("seed@x" -> 1),
          "b",
          editText("story.txt", "start\nend\n", "start\nB\nend\n")
        )
        val c =
          patch("c@x", 1, v("seed@x" -> 1), "c", editText("story.txt", "start\nend\n", "end\n"))
        val order = ok(Replay.integrationOrder(Vector(a, b, c, seed)))
        assertTrue(order.map(_.author.value) == Vector("seed@x", "c@x", "b@x", "a@x"))
      },
      test("dependency cycle is rejected") {
        val pa = patch("a@x", 1, v("b@x" -> 1), "cycle a", createText("a", ""))
        val pb = patch("b@x", 1, v("a@x" -> 1), "cycle b", createText("b", ""))
        assertTrue(
          Replay
            .integrationOrder(Vector(pa, pb))
            .left
            .toOption
            .contains(SnapError.CyclicOrIncompleteHistory)
        )
      },
      test("missing base dependency is rejected") {
        val p = patch("a@x", 1, v("zz@x" -> 9), "dangling", createText("f", ""))
        assertTrue(
          Replay
            .integrationOrder(Vector(p))
            .left
            .toOption
            .contains(SnapError.CyclicOrIncompleteHistory)
        )
      },
      test("structurally equal duplicate dots collapse to one entry") {
        val p1 = patch("a@x", 1, Version.empty, "one", createText("f", "one\n"))
        val p2 = patch("a@x", 1, Version.empty, "one", createText("f", "one\n"))
        val order = ok(Replay.integrationOrder(Vector(p1, p2)))
        assertTrue(order.length == 1)
      },
      test("different values at one dot are a patch collision (test 16)") {
        val p1 = patch("a@x", 1, Version.empty, "local", createText("f", "local\n"))
        val p2 = patch("a@x", 1, Version.empty, "different", createText("f", "remote\n"))
        assertTrue(
          Replay
            .integrationOrder(Vector(p1, p2))
            .left
            .toOption
            .exists(_.detail.contains("patch collision: a@x revision 1"))
        )
      }
    ),
    suite("test 09 line OT merge")(
      test("concurrent text edits merge to base-right-left with no warnings") {
        val seed = patch("seed@x", 1, Version.empty, "base", createText("notes.txt", "base\n"))
        val alice =
          patch(
            "alice@x",
            1,
            v("seed@x" -> 1),
            "left",
            editText("notes.txt", "base\n", "base\nleft\n")
          )
        val bob =
          patch(
            "bob@x",
            1,
            v("seed@x" -> 1),
            "right",
            editText("notes.txt", "base\n", "base\nright\n")
          )
        val target = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)
        val (tree, ws) = materializeOk(Vector(seed, alice, bob), target)
        assertTrue(treeText(tree, "notes.txt") == "base\nright\nleft\n", ws.isEmpty)
      },
      test("convergence holds regardless of input patch order") {
        val seed = patch("seed@x", 1, Version.empty, "base", createText("notes.txt", "base\n"))
        val alice =
          patch(
            "alice@x",
            1,
            v("seed@x" -> 1),
            "left",
            editText("notes.txt", "base\n", "base\nleft\n")
          )
        val bob =
          patch(
            "bob@x",
            1,
            v("seed@x" -> 1),
            "right",
            editText("notes.txt", "base\n", "base\nright\n")
          )
        val target = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)
        val results = Vector(seed, alice, bob).permutations.map { perm =>
          val (tree, ws) = materializeOk(perm, target)
          (treeText(tree, "notes.txt"), ws)
        }.toSet
        assertTrue(
          results.size == 1,
          results.head == (("base\nright\nleft\n"), Vector.empty[ReplayWarning])
        )
      }
    ),
    suite("test 10 whole-file rules")(
      test(
        "delete-wins, put-wins, later-put-wins with sorted warnings; identical collapses silently"
      ) {
        val base = "base\n"
        val seed =
          patch(
            "seed@x",
            1,
            Version.empty,
            "base",
            createText("delete.txt", base),
            createText("identical.txt", base),
            createText("incompatible.txt", base),
            createText("later-put.txt", base)
          )
        val alice = patch(
          "alice@x",
          1,
          v("seed@x" -> 1),
          "left",
          editText("delete.txt", base, "left\n"),
          editText("identical.txt", base, "same\n"),
          editText("incompatible.txt", base, "left text\n"),
          Change.Put("later-put.txt", Array[Byte](0, 1))
        )
        val bob = patch(
          "bob@x",
          1,
          v("seed@x" -> 1),
          "right",
          Change.Del("delete.txt"),
          editText("identical.txt", base, "same\n"),
          Change.Put("incompatible.txt", Array[Byte](0, -1)),
          editText("later-put.txt", base, "right text\n")
        )
        val target = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)
        val (tree, ws) = materializeOk(Vector(seed, alice, bob), target)
        val expectedWarnings = Vector(
          ReplayWarning.DeleteWins("delete.txt"),
          ReplayWarning.PutWins("incompatible.txt"),
          ReplayWarning.LaterPutWins("later-put.txt")
        )
        assertTrue(
          !tree.contains("delete.txt"),
          Model.bytesEqual(tree("incompatible.txt"), Array[Byte](0, -1)),
          Model.bytesEqual(tree("later-put.txt"), Array[Byte](0, 1)),
          treeText(tree, "identical.txt") == "same\n",
          ws == expectedWarnings
        )
      }
    ),
    suite("test 11 namespace conflicts")(
      test("file vs file/subpath: incoming ancestor wins, descendant removed with namespace-wins") {
        val alice = patch("alice@x", 1, Version.empty, "ancestor", createText("a", "ancestor\n"))
        val bob = patch("bob@x", 1, Version.empty, "descendant", createText("a/b", "descendant\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1)
        val (tree, ws) = materializeOk(Vector(alice, bob), target)
        assertTrue(
          treeText(tree, "a") == "ancestor\n",
          !tree.contains("a/b"),
          ws == Vector(ReplayWarning.NamespaceWins("a/b"))
        )
      },
      test("reverse ownership also converges to the canonically later author's namespace") {
        val bob = patch("bob@x", 1, Version.empty, "ancestor", createText("x", "ancestor\n"))
        val alice =
          patch("alice@x", 1, Version.empty, "descendant", createText("x/y", "descendant\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1)
        val (tree, ws) = materializeOk(Vector(alice, bob), target)
        assertTrue(
          treeText(tree, "x/y") == "descendant\n",
          !tree.contains("x"),
          ws == Vector(ReplayWarning.NamespaceWins("x"))
        )
      },
      test("duplicate namespace removals collapse to one warning per removed path") {
        val seed = patch("seed@x", 1, Version.empty, "blocker", createText("b", "blocker\n"))
        val p = patch(
          "p@x",
          1,
          Version.empty,
          "two children",
          createText("b/x", "x\n"),
          createText("b/y", "y\n")
        )
        val target = v("p@x" -> 1, "seed@x" -> 1)
        val (tree, ws) = materializeOk(Vector(seed, p), target)
        assertTrue(
          treeText(tree, "b/x") == "x\n",
          treeText(tree, "b/y") == "y\n",
          !tree.contains("b"),
          ws == Vector(ReplayWarning.NamespaceWins("b"))
        )
      }
    ),
    suite("test 17 concurrent creates")(
      test("canonically later create wins in both merge directions") {
        val alice = patch("alice@x", 1, Version.empty, "alice", createText("same.txt", "alice\n"))
        val bob = patch("bob@x", 1, Version.empty, "bob", createText("same.txt", "bob\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1)
        val forward = materializeOk(Vector(alice, bob), target)
        val backward = materializeOk(Vector(bob, alice), target)
        assertTrue(
          treeText(forward._1, "same.txt") == "alice\n",
          forward._2 == Vector(ReplayWarning.LaterCreateWins("same.txt")),
          Model.treeEqual(forward._1, backward._1),
          forward._2 == backward._2
        )
      }
    ),
    suite("test 18 three-way convergence")(
      test("delete plus two concurrent inserts converges with no warnings") {
        val base = "start\nend\n"
        val seed = patch("seed@x", 1, Version.empty, "base", createText("story.txt", base))
        val a =
          patch("a@x", 1, v("seed@x" -> 1), "a", editText("story.txt", base, "start\nA\nend\n"))
        val b =
          patch("b@x", 1, v("seed@x" -> 1), "b", editText("story.txt", base, "start\nB\nend\n"))
        val c = patch("c@x", 1, v("seed@x" -> 1), "c", editText("story.txt", base, "end\n"))
        val target = v("a@x" -> 1, "b@x" -> 1, "c@x" -> 1, "seed@x" -> 1)
        val (tree, ws) = materializeOk(Vector(seed, a, b, c), target)
        assertTrue(treeText(tree, "story.txt") == "B\nA\nend\n", ws.isEmpty)
      },
      test("all input permutations of the three-way history converge identically") {
        val base = "start\nend\n"
        val seed = patch("seed@x", 1, Version.empty, "base", createText("story.txt", base))
        val a =
          patch("a@x", 1, v("seed@x" -> 1), "a", editText("story.txt", base, "start\nA\nend\n"))
        val b =
          patch("b@x", 1, v("seed@x" -> 1), "b", editText("story.txt", base, "start\nB\nend\n"))
        val c = patch("c@x", 1, v("seed@x" -> 1), "c", editText("story.txt", base, "end\n"))
        val target = v("a@x" -> 1, "b@x" -> 1, "c@x" -> 1, "seed@x" -> 1)
        val results = Vector(seed, a, b, c).permutations.map { perm =>
          val (tree, ws) = materializeOk(perm, target)
          (treeText(tree, "story.txt"), ws)
        }.toSet
        assertTrue(
          results.size == 1,
          results.head == (("B\nA\nend\n", Vector.empty[ReplayWarning]))
        )
      }
    ),
    suite("test 21 version algebra merge")(
      test("serial contributor chain plus concurrent branch joins to (a@x->2,b@x->2)") {
        val a1 = patch("a@x", 1, Version.empty, "a1", createText("story.txt", "base\n"))
        val a2 = patch("a@x", 2, v("a@x" -> 1), "a2", editText("story.txt", "base\n", "base\nA2\n"))
        val b1 = patch("b@x", 1, v("a@x" -> 1), "b1", editText("story.txt", "base\n", "base\nB1\n"))
        val b2 = patch(
          "b@x",
          2,
          v("a@x" -> 1, "b@x" -> 1),
          "b2",
          editText("story.txt", "base\nB1\n", "base\nB1\nB2\n")
        )
        val target = v("a@x" -> 2, "b@x" -> 2)
        val (tree, ws) = materializeOk(Vector(a1, a2, b1, b2), target)
        assertTrue(treeText(tree, "story.txt") == "base\nB1\nB2\nA2\n", ws.isEmpty)
      }
    ),
    suite("test 22 OT matrix")(
      test("overlapping deletes remove the shared token once") {
        val base = "0\n1\n2\n3\n4\n"
        val seed = patch("seed@x", 1, Version.empty, "base", createText("f", base))
        val alice =
          patch("alice@x", 1, v("seed@x" -> 1), "delete-two", editText("f", base, "0\n3\n4\n"))
        val bob =
          patch("bob@x", 1, v("seed@x" -> 1), "delete-one", editText("f", base, "0\n2\n3\n4\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)
        val (tree, ws) = materializeOk(Vector(seed, alice, bob), target)
        assertTrue(treeText(tree, "f") == "0\n3\n4\n", ws.isEmpty)
      },
      test("insert priority, count splitting, overlapping deletes, trailing insert") {
        val base = "0\n1\n2\n3\n4\n"
        val seed = patch("seed@x", 1, Version.empty, "base", createText("f", base))
        val alice =
          patch(
            "alice@x",
            1,
            v("seed@x" -> 1),
            "incoming-complex",
            editText("f", base, "A\n0\n3\n4\nTAIL\n")
          )
        val bob =
          patch(
            "bob@x",
            1,
            v("seed@x" -> 1),
            "context-complex",
            editText("f", base, "0\n1\nB\n3\n4\n")
          )
        val target = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)
        val (tree, ws) = materializeOk(Vector(seed, alice, bob), target)
        assertTrue(treeText(tree, "f") == "A\n0\nB\n3\n4\nTAIL\n", ws.isEmpty)
      },
      test("retained token deleted by context stays deleted, trailing insert survives") {
        val base = "0\n1\n2\n3\n4\n"
        val seed = patch("seed@x", 1, Version.empty, "base", createText("f", base))
        val alice =
          patch(
            "alice@x",
            1,
            v("seed@x" -> 1),
            "retain-and-append",
            editText("f", base, "0\n1\n2\n3\n4\nA\n")
          )
        val bob =
          patch("bob@x", 1, v("seed@x" -> 1), "delete-middle", editText("f", base, "0\n2\n3\n4\n"))
        val target = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)
        val (tree, ws) = materializeOk(Vector(seed, alice, bob), target)
        assertTrue(treeText(tree, "f") == "0\n2\n3\n4\nA\n", ws.isEmpty)
      },
      test("context insert before a deleted token survives") {
        val base = "0\n1\n2\n3\n4\n"
        val seed = patch("seed@x", 1, Version.empty, "base", createText("f", base))
        val alice =
          patch(
            "alice@x",
            1,
            v("seed@x" -> 1),
            "delete-base-token",
            editText("f", base, "0\n2\n3\n4\n")
          )
        val bob = patch(
          "bob@x",
          1,
          v("seed@x" -> 1),
          "insert-before-token",
          editText("f", base, "0\nB\n1\n2\n3\n4\n")
        )
        val target = v("alice@x" -> 1, "bob@x" -> 1, "seed@x" -> 1)
        val (tree, ws) = materializeOk(Vector(seed, alice, bob), target)
        assertTrue(treeText(tree, "f") == "0\nB\n2\n3\n4\n", ws.isEmpty)
      }
    )
  )
}
