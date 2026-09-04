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
  * Implementation notes:
  *   - Small inputs use a dense bottom-up decision table (the original strategy).
  *   - Large inputs use a block-based linear-space replay: suffix-cost rows are computed bottom-up,
  *     checkpointed every `BlockSize` rows, and each block is re-materialised (O(m) rolling arrays)
  *     just before the spec walk replays through it. Space is O(m·√n), time stays O(n·m), and
  *     because the walk decisions are evaluated against the exact §5 suffix costs, the emitted
  *     script is byte-identical to the dense-table walk for every input.
  */
object Diff {

  private final val Diag: Byte = 0
  private final val Down: Byte = 1 // delete one old token
  private final val Right: Byte = 2 // insert one new token

  /** Subproblems with at most this many cells use the dense decision table. */
  private val DenseThreshold: Long = 2048L

  /** Row-block height for the linear-space replay; √n balances checkpoint storage vs recompute. */
  private def blockSize(n: Int): Int = math.max(1, math.sqrt(n.toDouble).toInt)

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
      else if (a.length.toLong * b.length.toLong <= DenseThreshold) denseWalk(a, b)
      else blockedWalk(a, b)

    coalesce(if (prefix > 0) EditOp.Retain(prefix.toLong) +: body else body)
  }

  /** Merge adjacent operations of the same kind; drop zero-count or empty operations. */
  private[snap] def coalesce(ops: Vector[EditOp]): Vector[EditOp] = {
    def nonEmpty(op: EditOp): Boolean = op match {
      case EditOp.Retain(n)      => n > 0
      case EditOp.Delete(n)      => n > 0
      case EditOp.Insert(tokens) => tokens.nonEmpty
    }
    ops.filter(nonEmpty).foldLeft(Vector.empty[EditOp]) { (acc, op) =>
      (acc.lastOption, op) match {
        case (Some(EditOp.Retain(x)), EditOp.Retain(y))   => acc.init :+ EditOp.Retain(x + y)
        case (Some(EditOp.Delete(x)), EditOp.Delete(y))   => acc.init :+ EditOp.Delete(x + y)
        case (Some(EditOp.Insert(t1)), EditOp.Insert(t2)) => acc.init :+ EditOp.Insert(t1 ++ t2)
        case _                                            => acc :+ op
      }
    }
  }

  /** One bottom-up suffix-cost row: `below` is D(i+1, ·); result is D(i, ·) over columns 0..m. */
  private def computeSuffixRow(
      a: Vector[String],
      b: Vector[String],
      n: Int,
      m: Int,
      i: Int,
      below: Array[Int]
  ): Array[Int] = {
    val cur = new Array[Int](m + 1)
    cur(m) = n - i // right edge: delete remaining old tokens
    // Imperative inner loop: hot O(m) DP recurrence over primitive arrays.
    var j = m - 1
    while (j >= 0) {
      if (a(i) == b(j)) cur(j) = below(j + 1)
      else {
        val del = below(j) // D(i + 1, j)
        val ins = cur(j + 1) // D(i, j + 1)
        cur(j) = (if (del <= ins) del else ins) + 1
      }
      j -= 1
    }
    cur
  }

  /** Emit the spec walk over a window of suffix-cost rows, advancing (i, j) in place. */
  private def walkRows(
      a: Vector[String],
      b: Vector[String],
      n: Int,
      m: Int,
      rows: Array[Array[Int]], // rows(r) = D(rowLo + r, ·)
      rowLo: Int,
      rowLim: Int, // walk rows [rowLo, rowLim)
      pos: Array[Int], // pos(0) = i, pos(1) = j
      ops: scala.collection.mutable.ArrayBuffer[EditOp]
  ): Unit = {
    var i = pos(0)
    var j = pos(1)
    while (i < rowLim && i < n && j < m) {
      val li = i - rowLo
      if (a(i) == b(j)) {
        ops += EditOp.Retain(1L); i += 1; j += 1
      } else if (rows(li + 1)(j) <= rows(li)(j + 1)) { // deletion wins ties (SPEC §5)
        ops += EditOp.Delete(1L); i += 1
      } else {
        ops += EditOp.Insert(Vector(b(j))); j += 1
      }
    }
    pos(0) = i
    pos(1) = j
  }

  /** Dense bottom-up decision table + walk; used for small inputs (bounded memory). */
  private def denseWalk(a: Vector[String], b: Vector[String]): Vector[EditOp] = {
    val n = a.length
    val m = b.length
    val w = m + 1
    val dec = new Array[Byte](n * w)
    val costNext = new Array[Int](w) // D(i + 1, *)
    val costCur = new Array[Int](w) // D(i, *)

    // Boundary: bottom row deletes nothing / inserts remaining new tokens.
    var j = 0
    while (j <= m) {
      costNext(j) = m - j
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

  /** Linear-space replay of the spec walk for large inputs (no cell cap, O(m·√n) memory). */
  private def blockedWalk(a: Vector[String], b: Vector[String]): Vector[EditOp] = {
    val n = a.length
    val m = b.length
    val s = blockSize(n)

    // Pass 1: bottom-up suffix costs; checkpoint every s-th row (plus row n).
    // Imperative loop: single O(n·m) DP sweep over primitive arrays.
    val checkpoints = scala.collection.mutable.HashMap.empty[Int, Array[Int]]
    var below = Array.tabulate(m + 1)(j => m - j) // D(n, j) = m - j
    checkpoints.update(n, below)
    var i = n - 1
    while (i >= 0) {
      val cur = computeSuffixRow(a, b, n, m, i, below)
      if (i % s == 0) checkpoints.update(i, cur)
      below = cur
      i -= 1
    }

    // Pass 2: replay the walk block by block, re-materialising each block's suffix rows
    // from its lower checkpoint so only O(s·m) rows are live at any time.
    val ops = scala.collection.mutable.ArrayBuffer.empty[EditOp]
    val pos = Array(0, 0)
    var blockLo = 0
    while (pos(0) < n && pos(1) < m) {
      blockLo = (pos(0) / s) * s
      val blockHi = math.min(blockLo + s, n)
      val height = blockHi - blockLo
      val rows = new Array[Array[Int]](height + 1)
      rows(height) = checkpoints(blockHi)
      var r = blockHi - 1
      while (r >= blockLo) {
        rows(r - blockLo) = computeSuffixRow(a, b, n, m, r, rows(r - blockLo + 1))
        r -= 1
      }
      walkRows(a, b, n, m, rows, blockLo, blockHi, pos, ops)
    }

    val (fi, fj) = (pos(0), pos(1))
    if (fi < n) ops += EditOp.Delete((n - fi).toLong)
    if (fj < m) ops += EditOp.Insert(b.drop(fj))
    ops.toVector
  }
}
