package snap

import zio.test.*

import snap.Model.*
import snap.Model.EditOp.*

/** Unit tests for the OT transform (SPEC §6.3) pinned against the YAML suite goldens. */
object OtSpec extends ZIOSpecDefault {

  private def toks(s: String): Vector[String] = Model.tokenize(s)

  /** Apply context edit q to base, then transformed incoming edit p; decoded text. */
  private def mergedText(base: Vector[String], p: Vector[EditOp], q: Vector[EditOp]): String = {
    val c = Model.applyEdit(base, q, "f").toOption.get
    val pPrime = Ot.transform(p, q)
    val result = Model.applyEdit(c, pPrime, "f").toOption.get
    Model.detokenize(result)
  }

  private val base22 = toks("0\n1\n2\n3\n4\n")

  // Test-22 derived scripts (canonicalDiff of each side's file against the seed file).
  private val ddP = Vector(Retain(1), Delete(2), Retain(2)) // "0\n3\n4\n"
  private val ddQ = Vector(Retain(1), Delete(1), Retain(3)) // "0\n2\n3\n4\n"
  private val splitP = Vector(
    Insert(Vector("A\n")),
    Retain(1),
    Delete(2),
    Retain(2),
    Insert(Vector("TAIL\n"))
  ) // "A\n0\n3\n4\nTAIL\n"
  private val splitQ =
    Vector(Retain(2), Delete(1), Insert(Vector("B\n")), Retain(2)) // "0\n1\nB\n3\n4\n"
  private val rdP = Vector(Retain(5), Insert(Vector("A\n"))) // "0\n1\n2\n3\n4\nA\n"
  private val rdQ = Vector(Retain(1), Delete(1), Retain(3)) // "0\n2\n3\n4\n"
  private val svP = Vector(Retain(1), Delete(1), Retain(3)) // "0\n2\n3\n4\n"
  private val svQ = Vector(Retain(1), Insert(Vector("B\n")), Retain(4)) // "0\nB\n1\n2\n3\n4\n"

  def spec = suite("Ot.transform")(
    suite("test 22 OT matrix")(
      test("overlapping deletes: same base token deleted only once") {
        assertTrue(
          Ot.transform(ddP, ddQ) == Vector(Retain(1), Delete(1), Retain(2)),
          mergedText(base22, ddP, ddQ) == "0\n3\n4\n",
          mergedText(base22, ddQ, ddP) == "0\n3\n4\n"
        )
      },
      test("split counts, insert priority, and trailing insert") {
        assertTrue(
          Ot.transform(splitP, splitQ) ==
            Vector(
              Insert(Vector("A\n")),
              Retain(1),
              Delete(1),
              Retain(3),
              Insert(Vector("TAIL\n"))
            ),
          Ot.transform(splitQ, splitP) ==
            Vector(Retain(2), Insert(Vector("B\n")), Retain(3)),
          mergedText(base22, splitP, splitQ) == "A\n0\nB\n3\n4\nTAIL\n",
          mergedText(base22, splitQ, splitP) == "A\n0\nB\n3\n4\nTAIL\n"
        )
      },
      test("retained token deleted by context stays deleted; trailing insert survives") {
        assertTrue(
          Ot.transform(rdP, rdQ) == Vector(Retain(4), Insert(Vector("A\n"))),
          Ot.transform(rdQ, rdP) == Vector(Retain(1), Delete(1), Retain(4)),
          mergedText(base22, rdP, rdQ) == "0\n2\n3\n4\nA\n",
          mergedText(base22, rdQ, rdP) == "0\n2\n3\n4\nA\n"
        )
      },
      test("context insert before an incoming deletion survives") {
        assertTrue(
          Ot.transform(svP, svQ) == Vector(Retain(2), Delete(1), Retain(3)),
          Ot.transform(svQ, svP) == Vector(Retain(1), Insert(Vector("B\n")), Retain(3)),
          mergedText(base22, svP, svQ) == "0\nB\n2\n3\n4\n",
          mergedText(base22, svQ, svP) == "0\nB\n2\n3\n4\n"
        )
      }
    ),
    suite("test 18 three-way convergence support")(
      test("pairwise transforms for inserts and a delete") {
        val base = toks("start\nend\n")
        val a = Vector(Retain(1), Insert(Vector("A\n")), Retain(1))
        val b = Vector(Retain(1), Insert(Vector("B\n")), Retain(1))
        val c = Vector(Delete(1), Retain(1))
        assertTrue(
          Ot.transform(a, b) == Vector(Retain(2), Insert(Vector("A\n")), Retain(1)),
          Ot.transform(b, a) == Vector(Retain(2), Insert(Vector("B\n")), Retain(1)),
          Ot.transform(a, c) == Vector(Insert(Vector("A\n")), Retain(1)),
          Ot.transform(c, a) == Vector(Delete(1), Retain(2)),
          Ot.transform(b, c) == Vector(Insert(Vector("B\n")), Retain(1)),
          Ot.transform(c, b) == Vector(Delete(1), Retain(2)),
          mergedText(base, c, b) == "B\nend\n",
          mergedText(base, b, c) == "B\nend\n"
        )
      },
      test("canonical integration order c then b then a yields B A end") {
        val base = toks("start\nend\n")
        val a = Vector(Retain(1), Insert(Vector("A\n")), Retain(1))
        val b = Vector(Retain(1), Insert(Vector("B\n")), Retain(1))
        val c = Vector(Delete(1), Retain(1))
        // integrate c
        val t1 = Model.applyEdit(base, c, "f").toOption.get
        // integrate b against aggregate context edit diff(base, t1)
        val q2 = Diff.canonicalDiff(base, t1)
        val t2 = Model.applyEdit(t1, Ot.transform(b, q2), "f").toOption.get
        // integrate a against aggregate context edit diff(base, t2)
        val q3 = Diff.canonicalDiff(base, t2)
        val t3 = Model.applyEdit(t2, Ot.transform(a, q3), "f").toOption.get
        assertTrue(
          q2 == c,
          Model.detokenize(t3) == "B\nA\nend\n"
        )
      }
    ),
    suite("mechanics")(
      test("q-insert priority emits retain before p's insert at the same cursor") {
        val p = Vector(Insert(Vector("P\n")), Retain(1))
        val q = Vector(Insert(Vector("Q\n")), Retain(1))
        assertTrue(
          Ot.transform(p, q) == Vector(Retain(1), Insert(Vector("P\n")), Retain(1))
        )
      },
      test("retain counts split across uneven context retains") {
        val p = Vector(Retain(5))
        val q = Vector(Retain(2), Insert(Vector("x\n")), Retain(3))
        assertTrue(Ot.transform(p, q) == Vector(Retain(6)))
      },
      test("delete spanning context retain/delete boundaries splits correctly") {
        val p = Vector(Delete(3), Retain(1))
        val q = Vector(Retain(1), Delete(1), Retain(2))
        // base tokens: [t0,t1,t2,t3]; q keeps [t0,t2,t3]
        // p deletes t0,t1,t2 and retains t3 -> over q's result: delete t0, skip t1, delete t2, retain t3
        assertTrue(Ot.transform(p, q) == Vector(Delete(2), Retain(1)))
      },
      test("trailing inserts on both streams coalesce the retains") {
        val p = Vector(Retain(1), Insert(Vector("P\n")))
        val q = Vector(Retain(1), Insert(Vector("Q\n")))
        assertTrue(
          Ot.transform(p, q) == Vector(Retain(2), Insert(Vector("P\n")))
        )
      },
      test("leading q insert shifts p retains into one coalesced retain") {
        val p = Vector(Retain(2))
        val q = Vector(Insert(Vector("x\n")), Retain(2))
        assertTrue(Ot.transform(p, q) == Vector(Retain(3)))
      }
    ),
    suite("transform applicability property")(
      test("transformed edits always apply cleanly to the context result (property)") {
        // Snap replay integrates patches in canonical order, so the guarantee is determinism of
        // one fixed order, not symmetric TP1 confluence: applying P-after-Q and Q-after-P can
        // legitimately yield different merged text. The invariant under test is that transform()
        // always yields an edit that applies cleanly to the context result and stays canonical.
        // LF-terminated tokens only: a bare final token could be legitimately stranded mid-sequence
        // by a concurrent insert, which is a property of the token model, not a transform bug.
        val canonicalTokens: Gen[Any, Vector[String]] =
          for {
            n <- Gen.int(0, 4)
            core <- Gen.vectorOfN(n)(Gen.elements("a\n", "b\n", "c\n"))
          } yield core

        val scenario = for {
          base <- Gen.vectorOfN(4)(Gen.elements("a\n", "b\n", "c\n", "d\n"))
          nwP <- canonicalTokens
          nwQ <- canonicalTokens
        } yield (base, nwP, nwQ)

        check(scenario) { case (base, newP, newQ) =>
          val p = Diff.canonicalDiff(base, newP)
          val q = Diff.canonicalDiff(base, newQ)
          val viaQ =
            Model.applyEdit(base, q, "f").flatMap(c => Model.applyEdit(c, Ot.transform(p, q), "f"))
          val viaP =
            Model.applyEdit(base, p, "f").flatMap(c => Model.applyEdit(c, Ot.transform(q, p), "f"))
          assertTrue(
            viaQ.isRight,
            viaP.isRight,
            viaQ.toOption.forall(Model.isCanonicalTokenSeq),
            viaP.toOption.forall(Model.isCanonicalTokenSeq)
          )
        }
      } @@ TestAspect.samples(128)
    )
  )
}
