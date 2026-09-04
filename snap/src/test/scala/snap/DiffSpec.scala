package snap

import zio.test.*

import snap.Model.*
import snap.Model.EditOp.*

/** Unit tests for the canonical token diff (SPEC §5). */
object DiffSpec extends ZIOSpecDefault {

  private def toks(s: String): Vector[String] = Model.tokenize(s)

  /** Canonical token sequences over a small alphabet, optionally without a final LF. */
  private val canonicalTokens: Gen[Any, Vector[String]] =
    for {
      n <- Gen.int(0, 6)
      core <- Gen.vectorOfN(n)(Gen.elements("a\n", "b\n", "c\n", "d\n"))
      tail <- Gen.elements("", "x", "y\n")
    } yield {
      if (tail.isEmpty) core
      else if (core.isEmpty) Vector(tail)
      else if (tail.endsWith("\n")) core :+ tail
      else core :+ tail
    }

  def spec = suite("Diff.canonicalDiff")(
    test("empty to empty yields an empty script") {
      assertTrue(Diff.canonicalDiff(Vector.empty, Vector.empty).isEmpty)
    },
    test("empty old yields one insert of all new tokens") {
      assertTrue(
        Diff.canonicalDiff(Vector.empty, toks("a\nb\n")) ==
          Vector(Insert(Vector("a\n", "b\n")))
      )
    },
    test("empty new yields one delete") {
      assertTrue(Diff.canonicalDiff(toks("a\nb\n"), Vector.empty) == Vector(Delete(2)))
    },
    test("identical non-empty sequences coalesce to one retain") {
      assertTrue(Diff.canonicalDiff(toks("a\nb\n"), toks("a\nb\n")) == Vector(Retain(2)))
    },
    test("test 05 golden: repeated lines use deletion on tie") {
      val old = Vector("a\n", "b\n", "a\n")
      val nw = Vector("b\n", "a\n", "a")
      assertTrue(
        Diff.canonicalDiff(old, nw) ==
          Vector(Delete(1), Retain(2), Insert(Vector("a")))
      )
    },
    test("test 05 golden: applying the script reproduces the new tokens") {
      val old = Vector("a\n", "b\n", "a\n")
      val nw = Vector("b\n", "a\n", "a")
      assertTrue(Model.applyEdit(old, Diff.canonicalDiff(old, nw), "repeated.txt") == Right(nw))
    },
    test("test 21 forward diff appends after retained base") {
      assertTrue(
        Diff.canonicalDiff(toks("base\n"), toks("base\nB1\nB2\nA2\n")) ==
          Vector(Retain(1), Insert(Vector("B1\n", "B2\n", "A2\n")))
      )
    },
    test("test 21 reverse diff deletes the final line") {
      assertTrue(
        Diff.canonicalDiff(toks("base\nB1\nB2\nA2\n"), toks("base\nB1\nB2\n")) ==
          Vector(Retain(3), Delete(1))
      )
    },
    test("tie at first token chooses delete before insert") {
      val script = Diff.canonicalDiff(toks("x\ny\n"), toks("y\nx\n"))
      assertTrue(script == Vector(Delete(1), Retain(1), Insert(Vector("x\n"))))
    },
    test("final token without trailing LF is preserved exactly") {
      val old = toks("a\nb\n")
      val nw = Vector("a\n", "b")
      val script = Diff.canonicalDiff(old, nw)
      assertTrue(
        script == Vector(Retain(1), Delete(1), Insert(Vector("b"))),
        Model.applyEdit(old, script, "f") == Right(nw)
      )
    },
    test("matches naive memoized reference implementation (property)") {
      check(canonicalTokens, canonicalTokens) { (old, nw) =>
        assertTrue(Diff.canonicalDiff(old, nw) == naiveDiff(old, nw))
      }
    },
    test("apply(diff) round-trips arbitrary canonical token sequences (property)") {
      check(canonicalTokens, canonicalTokens) { (old, nw) =>
        val script = Diff.canonicalDiff(old, nw)
        assertTrue(Model.applyEdit(old, script, "f") == Right(nw))
      }
    },
    test("output is always canonical: coalesced, positive counts, valid insert tokens (property)") {
      check(canonicalTokens, canonicalTokens) { (old, nw) =>
        val script = Diff.canonicalDiff(old, nw)
        val countsPositive = script.forall {
          case Retain(n)      => n > 0
          case Delete(n)      => n > 0
          case Insert(tokens) => tokens.nonEmpty && tokens.forall(Model.isValidInsertToken)
        }
        assertTrue(
          Model.hasAdjacentSameKind(script).isEmpty,
          countsPositive
        )
      }
    }
  ) @@ TestAspect.samples(64)

  /** Naive memoized reference implementation of SPEC §5, used to cross-check the DP walk. */
  private def naiveDiff(a: Vector[String], b: Vector[String]): Vector[EditOp] = {
    val memo = scala.collection.mutable.HashMap.empty[(Int, Int), Int]
    def d(i: Int, j: Int): Int =
      memo.getOrElseUpdate(
        (i, j), {
          if (i == a.length) b.length - j
          else if (j == b.length) a.length - i
          else if (a(i) == b(j)) d(i + 1, j + 1)
          else 1 + math.min(d(i + 1, j), d(i, j + 1))
        }
      )
    val ops = Vector.newBuilder[EditOp]
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
      if (a(i) == b(j)) { ops += Retain(1L); i += 1; j += 1 }
      else if (d(i + 1, j) <= d(i, j + 1)) { ops += Delete(1L); i += 1 }
      else { ops += Insert(Vector(b(j))); j += 1 }
    }
    if (i < a.length) ops += Delete((a.length - i).toLong)
    if (j < b.length) ops += Insert(b.drop(j))
    Diff.coalesce(ops.result())
  }
}
