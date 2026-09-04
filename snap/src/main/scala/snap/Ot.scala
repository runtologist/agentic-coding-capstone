package snap

import snap.Model.EditOp

/** Operational transform for concurrent text edits (SPEC §6.3).
  *
  * `transform(p, q)` rewrites the incoming edit `P` so it applies after the aggregate context edit
  * `Q` (the canonical diff from the patch's base tree to the current canonical tree). Both edits
  * consume the same base token sequence. Q-inserts have priority, so concurrent inserts at one
  * cursor appear in canonical integration order; deletes consume only base tokens, so concurrent
  * inserted text survives.
  */
object Ot {

  def transform(p: Vector[EditOp], q: Vector[EditOp]): Vector[EditOp] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[EditOp]

    def remOf(op: EditOp): Long = op match {
      case EditOp.Retain(n) => n
      case EditOp.Delete(n) => n
      case EditOp.Insert(_) => 0L
    }

    def remAt(ops: Vector[EditOp], i: Int): Long =
      if (i < ops.length) remOf(ops(i)) else 0L

    // Two-cursor state machine as tail recursion: (pi, qi) are the op positions, (pRem, qRem)
    // the unconsumed counts of the current retain/delete ops.
    @scala.annotation.tailrec
    def step(pi: Int, qi: Int, pRem: Long, qRem: Long): Unit = {
      val pOp: Option[EditOp] = if (pi < p.length) Some(p(pi)) else None
      val qOp: Option[EditOp] = if (qi < q.length) Some(q(qi)) else None

      (pOp, qOp) match {
        case (None, None) => ()

        case (_, Some(EditOp.Insert(tokens))) =>
          // Q insert: the incoming edit must skip past the context's inserted tokens.
          out += EditOp.Retain(tokens.length.toLong)
          step(pi, qi + 1, pRem, remAt(q, qi + 1))

        case (Some(EditOp.Insert(tokens)), _) =>
          // P insert: survives unchanged; deletions consume only base tokens.
          out += EditOp.Insert(tokens)
          step(pi + 1, qi, remAt(p, pi + 1), qRem)

        case (Some(pCur), Some(qCur)) =>
          // Both count-based (retain/delete); split counts as needed.
          val take = math.min(pRem, qRem)
          if (take <= 0L)
            throw new IllegalStateException(
              "Ot.transform: non-positive retain/delete count in input edit script"
            )
          (pCur, qCur) match {
            case (_: EditOp.Retain, _: EditOp.Retain) => out += EditOp.Retain(take)
            case (_: EditOp.Delete, _: EditOp.Retain) => out += EditOp.Delete(take)
            case (_: EditOp.Retain, _: EditOp.Delete) => ()
            case (_: EditOp.Delete, _: EditOp.Delete) => ()
            case _                                    => () // inserts handled above
          }
          val pRem2 = pRem - take
          val qRem2 = qRem - take
          step(
            if (pRem2 == 0L) pi + 1 else pi,
            if (qRem2 == 0L) qi + 1 else qi,
            if (pRem2 == 0L) remAt(p, pi + 1) else pRem2,
            if (qRem2 == 0L) remAt(q, qi + 1) else qRem2
          )

        case (None, Some(_)) =>
          // Q may only have trailing inserts after P is exhausted; those are handled above.
          throw new IllegalStateException(
            "Ot.transform: unmatched retain/delete in context edit after incoming edit exhausted"
          )

        case (Some(_), None) =>
          // P may only have trailing inserts after Q is exhausted; those are handled above.
          throw new IllegalStateException(
            "Ot.transform: unmatched retain/delete in incoming edit after context edit exhausted"
          )
      }
    }

    step(0, 0, remAt(p, 0), remAt(q, 0))

    Diff.coalesce(out.toVector)
  }
}
