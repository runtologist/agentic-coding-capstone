package snap

import snap.Model.*

import scala.collection.mutable

/** Semantic repository validation (SPEC §4.5) over values already decoded by [[Json]], plus the
  * cross-repository helpers used by `merge` and `diff --repo`.
  *
  * Structural JSON checks (duplicate keys, unknown fields, integer-literal strictness, base64
  * canonicality, edit-op arity, empty insert/changes, path and message validity) belong to the JSON
  * layer. Codec validates the decoded typed value: history shape, ordering, closure, and every
  * change against its materialized exact base. Validation is pure and never mutates anything, so a
  * failed validation cannot touch working files (SPEC §10).
  */
object Codec {

  /** SPEC §4.5 full validation in pinned order; the first failure wins. */
  def validateRepository(repo: Repository): Either[SnapError, Unit] = {
    val patches = repo.patches
    for {
      _ <- checkFrontierCanonical(repo.frontier)
      _ <- checkPatchesSorted(patches)
      _ <- checkChangesSortedAndUnique(patches)
      _ <- checkDotConsistency(patches)
      deduped <- Replay.dedupePatches(patches)
      _ <- checkContiguity(deduped)
      _ <- checkBaseClosure(deduped)
      _ <- checkReachability(deduped, repo.frontier)
      _ <- Replay.integrationOrder(deduped).map(_ => ())
      _ <- checkStaticChangeDetails(deduped)
      _ <- Replay
        .materializeValidating(deduped, repo.frontier, validateChangesAgainstBase)
        .map(_ => ())
    } yield ()
  }

  /** Validate one patch's changes against its materialized exact base tree (SPEC §4.3, §4.4, §4.5
    * step 5). Used both as the replay validation hook and directly by commit.
    */
  def validateChangesAgainstBase(patch: Patch, baseTree: Model.Tree): Either[SnapError, Unit] = {
    var resultTree = baseTree
    val it = patch.changes.iterator
    var failure: Option[SnapError] = None
    while (it.hasNext && failure.isEmpty) {
      val change = it.next()
      change match {
        case Change.Del(path) =>
          if (!baseTree.contains(path)) failure = Some(SnapError.DeleteOfAbsentPath(path))
          else resultTree = resultTree - path

        case Change.Put(path, bytes) =>
          baseTree.get(path) match {
            case Some(old) if Model.bytesEqual(old, bytes) =>
              failure = Some(SnapError.NoOpChange(path))
            case _ =>
              resultTree = resultTree.updated(path, bytes)
          }

        case Change.Text(path, edit) =>
          baseTree.get(path) match {
            case Some(old) if !Model.isText(old) =>
              failure = Some(SnapError.TextOverBinaryBase(path))
            case Some(_) if edit.isEmpty =>
              // An empty edit only creates an empty file; over an existing path it is a
              // create-of-present-path error (SPEC §4.3/§4.4).
              failure = Some(SnapError.CreateOfPresentPath(path))
            case Some(old) =>
              val baseTokens = Model.tokenize(Model.decodeUtf8(old).get)
              Model.applyEdit(baseTokens, edit, path) match {
                case Left(err) => failure = Some(err)
                case Right(tokens) =>
                  val bytes = Model.utf8Bytes(Model.detokenize(tokens))
                  if (Model.bytesEqual(bytes, old)) failure = Some(SnapError.NoOpChange(path))
                  else resultTree = resultTree.updated(path, bytes)
              }
            case None =>
              Model.applyEdit(Vector.empty, edit, path) match {
                case Left(err) => failure = Some(err)
                case Right(tokens) =>
                  resultTree = resultTree.updated(path, Model.utf8Bytes(Model.detokenize(tokens)))
              }
          }
      }
    }
    failure match {
      case Some(err) => Left(err)
      case None      => checkPrefixFree(resultTree)
    }
  }

  /** Cross-repository dot-collision check (SPEC §3.5; tests 16, 26). Must run before any local
    * mutation. Structurally equal duplicates are fine; different values at one dot are corruption.
    */
  def checkCollision(local: Vector[Patch], remote: Vector[Patch]): Either[SnapError, Unit] = {
    val localByDot = local.groupBy(p => (p.author.value, p.revision))
    val it = remote.iterator
    while (it.hasNext) {
      val rp = it.next()
      localByDot.get((rp.author.value, rp.revision)) match {
        case Some(lps) if lps.exists(lp => !Patch.sameValue(lp, rp)) =>
          return Left(SnapError.PatchCollision(rp.author.value, rp.revision))
        case _ => ()
      }
    }
    Right(())
  }

  /** New frontier after importing `incoming` patches: componentwise join of the local frontier with
    * every imported patch result (SPEC §3.3).
    */
  def joinedFrontier(localFrontier: Version, incoming: Vector[Patch]): Version =
    incoming.foldLeft(localFrontier)((acc, p) => Version.join(acc, p.result))

  // ---------------------------------------------------------------------------
  // §4.5 step-by-step checks
  // ---------------------------------------------------------------------------

  /** Step 1: frontier components must be in canonical unsigned-UTF-8 author order. */
  private def checkFrontierCanonical(frontier: Version): Either[SnapError, Unit] = {
    val comps = frontier.components
    val sorted = comps.sortWith((a, b) => Model.utf8Compare(a._1.value, b._1.value) < 0)
    val unique = comps.map(_._1.value).distinct.length == comps.length
    if (unique && comps == sorted) Right(())
    else
      Left(
        SnapError.NonCanonicalFrontier(
          renderPairs(comps),
          renderPairs(sorted)
        )
      )
  }

  private def renderPairs(pairs: Vector[(ContributorId, Long)]): String =
    if (pairs.isEmpty) "()"
    else pairs.map { case (id, r) => s"${id.value}->$r" }.mkString("(", ",", ")")

  /** Step 2: patches sorted by author (unsigned UTF-8), then numeric revision. */
  private def checkPatchesSorted(patches: Vector[Patch]): Either[SnapError, Unit] = {
    var i = 1
    while (i < patches.length) {
      val prev = patches(i - 1)
      val cur = patches(i)
      val c = Model.utf8Compare(prev.author.value, cur.author.value)
      val ok =
        if (c < 0) true
        else if (c == 0) prev.revision <= cur.revision
        else false
      if (!ok)
        return Left(
          SnapError.InvalidVersion(
            s"patches are not canonically sorted at ${cur.author.value} revision ${cur.revision}"
          )
        )
      i += 1
    }
    Right(())
  }

  /** Step 3: within each patch, changes sorted by path with at most one change per path. */
  private def checkChangesSortedAndUnique(patches: Vector[Patch]): Either[SnapError, Unit] = {
    val it = patches.iterator
    while (it.hasNext) {
      val p = it.next()
      var i = 1
      while (i < p.changes.length) {
        val prevPath = p.changes(i - 1).path
        val curPath = p.changes(i).path
        val c = Model.utf8Compare(prevPath, curPath)
        if (c == 0) return Left(SnapError.TreePathsConflict(curPath))
        if (c > 0)
          return Left(
            SnapError.InvalidVersion(
              s"changes in patch ${p.author.value} revision ${p.revision} are not sorted by path"
            )
          )
        i += 1
      }
    }
    Right(())
  }

  /** Step 4: every patch satisfies revision = base[author] + 1 (SPEC §4.2). */
  private def checkDotConsistency(patches: Vector[Patch]): Either[SnapError, Unit] = {
    val it = patches.iterator
    while (it.hasNext) {
      val p = it.next()
      val expected = p.base.get(p.author) + 1
      if (p.revision != expected)
        return Left(
          SnapError.InvalidVersion(
            s"patch ${p.author.value} revision ${p.revision} does not satisfy revision = base[author] + 1"
          )
        )
    }
    Right(())
  }

  /** Step 6: for each author, revisions are contiguous starting at 1 (SPEC §3.5). Assumes the input
    * is deduplicated and sorted by (author, revision).
    */
  private def checkContiguity(patches: Vector[Patch]): Either[SnapError, Unit] = {
    var currentAuthor: Option[String] = None
    var expectedRev = 1L
    val it = patches.iterator
    while (it.hasNext) {
      val p = it.next()
      val author = p.author.value
      if (currentAuthor.contains(author)) {
        if (p.revision != expectedRev)
          return Left(SnapError.MissingPatch(author, expectedRev))
        expectedRev += 1
      } else {
        if (p.revision != 1L) return Left(SnapError.MissingPatch(author, 1L))
        currentAuthor = Some(author)
        expectedRev = 2L
      }
    }
    Right(())
  }

  /** Step 7: every dot referenced in a base exists among the patches. */
  private def checkBaseClosure(patches: Vector[Patch]): Either[SnapError, Unit] = {
    val dots = patches.map(p => (p.author.value, p.revision)).toSet
    val it = patches.iterator
    while (it.hasNext) {
      val p = it.next()
      val bIt = p.base.components.iterator
      while (bIt.hasNext) {
        val (id, rev) = bIt.next()
        if (!dots.contains((id.value, rev)))
          return Left(SnapError.MissingPatch(id.value, rev))
      }
    }
    Right(())
  }

  /** Step 8: every patch lies in the causal closure of the frontier, and the frontier is exactly
    * the componentwise maximum over all patch results.
    */
  private def checkReachability(
      patches: Vector[Patch],
      frontier: Version
  ): Either[SnapError, Unit] = {
    val byDot = patches.map(p => ((p.author.value, p.revision), p)).toMap
    val visited = mutable.HashSet.empty[(String, Long)]
    val queue = mutable.Queue.empty[(String, Long)]
    val fIt = frontier.components.iterator
    while (fIt.hasNext) {
      val (id, rev) = fIt.next()
      val dot = (id.value, rev)
      if (!byDot.contains(dot)) return Left(SnapError.MissingPatch(id.value, rev))
      queue.enqueue(dot)
    }
    while (queue.nonEmpty) {
      val dot = queue.dequeue()
      if (!visited.contains(dot)) {
        visited += dot
        byDot(dot).base.components.foreach(c => queue.enqueue((c._1.value, c._2)))
      }
    }
    patches.find(p => !visited.contains((p.author.value, p.revision))) match {
      case Some(p) => Left(SnapError.UnreachablePatch(p.author.value, p.revision))
      case None =>
        val expectedFrontier =
          patches.foldLeft(Version.empty)((acc, p) => Version.join(acc, p.result))
        if (expectedFrontier == frontier) Right(())
        else
          Left(
            SnapError.NonCanonicalFrontier(frontier.render, expectedFrontier.render)
          )
    }
  }

  /** Steps 10–14: per-change static checks that are defense-in-depth over the JSON layer and the
    * prefix-free guarantee of a patch's authored result (SPEC §2, §4.2, §4.4).
    */
  private def checkStaticChangeDetails(patches: Vector[Patch]): Either[SnapError, Unit] = {
    val it = patches.iterator
    while (it.hasNext) {
      val p = it.next()
      if (p.message.isEmpty) return Left(SnapError.EmptyField("patch", "message"))
      if (p.changes.isEmpty) return Left(SnapError.EmptyField("patch", "changes"))
      val cIt = p.changes.iterator
      while (cIt.hasNext) {
        val change = cIt.next()
        Model.validatePath(change.path) match {
          case Left(err) => return Left(err)
          case Right(()) => ()
        }
        change match {
          case Change.Text(path, edit) =>
            adjacentSameKind(edit) match {
              case Some(kind) => return Left(SnapError.AdjacentSameKindOps(kind))
              case None       => ()
            }
            val badToken = edit.iterator.collectFirst {
              case EditOp.Insert(tokens) if tokens.exists(t => !Model.isValidInsertToken(t)) =>
                true
            }
            if (badToken.isDefined) return Left(SnapError.NonCanonicalTokens(path))
          case _ => ()
        }
      }
      // A patch's authored change paths must themselves be prefix-free (SPEC §2).
      val paths = p.changes.map(_.path)
      val conflict = paths.sliding(2).collectFirst {
        case Vector(a, b) if Model.isProperAncestor(a, b) => b
      }
      conflict match {
        case Some(path) => return Left(SnapError.TreePathsConflict(path))
        case None       => ()
      }
    }
    Right(())
  }

  private def adjacentSameKind(edit: Vector[EditOp]): Option[String] = {
    def kind(op: EditOp): String = op match {
      case _: EditOp.Retain => "retain"
      case _: EditOp.Delete => "delete"
      case _: EditOp.Insert => "insert"
    }
    edit.sliding(2).collectFirst {
      case Vector(a, b) if kind(a) == kind(b) => kind(a)
    }
  }

  /** The authored result tree must be prefix-free by path segment (SPEC §2). */
  private def checkPrefixFree(tree: Model.Tree): Either[SnapError, Unit] = {
    val paths = Model.sortedPaths(tree)
    val conflict = paths.sliding(2).collectFirst {
      case Vector(a, b) if Model.isProperAncestor(a, b) => b
    }
    conflict match {
      case Some(path) => Left(SnapError.TreePathsConflict(path))
      case None       => Right(())
    }
  }
}
