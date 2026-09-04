package snap

import snap.Model.EditOp

/** Canonical token diff (SPEC §5).
  *
  * Given old tokens `A` (length n) and new tokens `B` (length m), D(i, j) is the minimum number of
  * inserts/deletes needed to transform `A[i..]` into `B[j..]`:
  *
  * {{{
  * D(n, m) = 0
  * D(i, m) = n - i
  * D(n, j) = m - j
  * if A[i] == B[j]:  D(i, j) = D(i + 1, j + 1)
  * else:             D(i, j) = 1 + min(D(i + 1, j), D(i, j + 1))
  * }}}
  *
  * The walk from (0, 0) retains equal tokens, chooses `delete 1` when `D(i+1, j) <= D(i, j+1)`
  * (deletion on ties), otherwise inserts `B[j]`, and flushes the exhausted side. Adjacent
  * operations of the same kind are coalesced. The recurrence and the deletion-on-tie rule define
  * the exact output, including for repeated equal lines.
  *
  * Implementation: costs are computed bottom-up with two rolling rows while a one-byte-per-cell
  * decision table records the walk choice, giving the exact spec walk in O(n·m) time and ~1 byte
  * per cell instead of a full int table.
  */
object Diff {

  /** Decision-table size cap (cells). 64M cells ≈ 64 MB decision table + O(m) cost rows. */
  private val MaxDiffCells: Long = 64000000L

  private final val Diag: Byte = 0
  private final val Down: Byte = 1 // delete one old token
  private final val Right: Byte = 2 // insert one new token

  /** SPEC §5 canonical diff, coalesced. */
  def canonicalDiff(oldTokens: Vector[String], newTokens: Vector[String]): Vector[EditOp] = {
    // Equal leading tokens are always retained first by the spec walk, so they can be trimmed.
    var prefix = 0
    val lim = math.min(oldTokens.length, newTokens.length)
    while (prefix < lim && oldTokens(prefix) == newTokens(prefix)) prefix += 1

    val a = oldTokens.drop(prefix)
    val b = newTokens.drop(prefix)

    val body: Vector[EditOp] =
      if (a.isEmpty && b.isEmpty) Vector.empty
      else if (a.isEmpty) Vector(EditOp.Insert(b))
      else if (b.isEmpty) Vector(EditOp.Delete(a.length.toLong))
      else dpDiff(a, b)

    coalesce(if (prefix > 0) EditOp.Retain(prefix.toLong) +: body else body)
  }

  /** Merge adjacent operations of the same kind; drop zero-count or empty operations. */
  private[snap] def coalesce(ops: Vector[EditOp]): Vector[EditOp] = {
    val buf = scala.collection.mutable.ArrayBuffer.empty[EditOp]
    ops.foreach { op =>
      val nonEmpty = op match {
        case EditOp.Retain(n)      => n > 0
        case EditOp.Delete(n)      => n > 0
        case EditOp.Insert(tokens) => tokens.nonEmpty
      }
      if (nonEmpty) {
        if (buf.isEmpty) buf += op
        else
          (buf.last, op) match {
            case (EditOp.Retain(x), EditOp.Retain(y)) => buf(buf.length - 1) = EditOp.Retain(x + y)
            case (EditOp.Delete(x), EditOp.Delete(y)) => buf(buf.length - 1) = EditOp.Delete(x + y)
            case (EditOp.Insert(t1), EditOp.Insert(t2)) =>
              buf(buf.length - 1) = EditOp.Insert(t1 ++ t2)
            case _ => buf += op
          }
      }
    }
    buf.toVector
  }

  private def dpDiff(a: Vector[String], b: Vector[String]): Vector[EditOp] = {
    val n = a.length
    val m = b.length
    val w = m + 1
    val cells = (n.toLong + 1L) * w.toLong
    if (cells > MaxDiffCells)
      throw new IllegalStateException(
        s"Diff.dpDiff: token diff of $n x $m exceeds the $MaxDiffCells-cell budget"
      )

    val dec = new Array[Byte]((n + 1) * w)
    val costNext = new Array[Int](w) // D(i + 1, *)
    val costCur = new Array[Int](w) // D(i, *)

    // Boundary: bottom row deletes nothing / inserts remaining new tokens.
    var j = 0
    while (j <= m) {
      costNext(j) = m - j
      dec(n * w + j) = Right
      j += 1
    }

    var i = n - 1
    while (i >= 0) {
      costCur(m) = n - i // right edge: delete remaining old tokens
      dec(i * w + m) = Down
      j = m - 1
      while (j >= 0) {
        if (a(i) == b(j)) {
          costCur(j) = costNext(j + 1)
          dec(i * w + j) = Diag
        } else {
          val del = costNext(j) // D(i + 1, j)
          val ins = costCur(j + 1) // D(i, j + 1)
          if (del <= ins) { // deletion wins ties (SPEC §5)
            costCur(j) = del + 1
            dec(i * w + j) = Down
          } else {
            costCur(j) = ins + 1
            dec(i * w + j) = Right
          }
        }
        j -= 1
      }
      System.arraycopy(costCur, 0, costNext, 0, w)
      i -= 1
    }

    val ops = Vector.newBuilder[EditOp]
    i = 0
    j = 0
    while (i < n && j < m) {
      dec(i * w + j) match {
        case Diag => ops += EditOp.Retain(1L); i += 1; j += 1
        case Down => ops += EditOp.Delete(1L); i += 1
        case _    => ops += EditOp.Insert(Vector(b(j))); j += 1
      }
    }
    if (i < n) ops += EditOp.Delete((n - i).toLong)
    if (j < m) ops += EditOp.Insert(b.drop(j))
    ops.result()
  }
}
