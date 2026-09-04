package snap

import snap.Model.*

import scala.collection.mutable

/** Semantic repository validation (SPEC §4.5) over decoded [[Model.Repository]] values.
  *
  * The JSON layer ([[Json.parseRepository]]) already rejects structural schema problems: unknown
  * fields, duplicate keys, non-integer numbers, malformed base64, invalid contributor IDs, invalid
  * paths, empty messages/changes/inserts, wrong edit-op arity, and non-positive counts. `Codec`
  * validates everything that needs the typed model and the history as a whole, in the fixed
  * first-error-wins order of SPEC §4.5:
  *
  *   1. canonical frontier sort; 2. patches sorted by (author unsigned-UTF-8, revision); 3. changes
  *      sorted by path, at most one per path; 4. `revision == base[author] + 1` for every patch; 5.
  *      one value per dot (structurally equal duplicates collapse, different values are a
  *      [[SnapError.PatchCollision]]); 6. contiguous contributor revisions (1..max) for every
  *      author of a patch or frontier dot; 7. complete base closure: every base dot exists as a
  *      patch; 8. every patch is reachable from the declared frontier (no unreachable patch, no
  *      patch dot beyond the frontier); 9. acyclic causality ([[Replay.integrationOrder]]); 10.
  *      message and changes non-empty; 11. edit-script shape: no adjacent same-kind operations,
  *      positive counts, non-empty inserts; 12. insert-token canonicality; 13. tracked-path
  *      validity; 14. prefix-free change paths within one patch; 15. every change validated against
  *      its materialized exact base tree ([[validateChangesAgainstBase]]); 16. deterministic replay
  *      of the declared frontier succeeds.
  *
  * Validation never mutates anything: all functions are pure.
  */
object Codec {

  /** Full SPEC §4.5 validation. Called on every repository load; the first error wins. */
  def validateRepository(repo: Repository): Either[SnapError, Unit] =
    for {
      _ <- checkFrontierSorted(repo.frontier)
      _ <- checkPatchesSorted(repo.patches)
      _ <- firstError(repo.patches.map(checkChangesSortedAndUnique))
      _ <- firstError(repo.patches.map(checkDotConsistency))
      _ <- checkDotCollisions(repo.patches)
      _ <- checkContiguity(repo)
      _ <- checkBaseClosure(repo.patches)
      _ <- checkReachability(repo)
      _ <- Replay.integrationOrder(repo.patches).map(_ => ())
      _ <- firstError(repo.patches.map(checkPatchStructure))
      // Steps 15+16 in one canonical replay: each patch's changes are validated against its
      // materialized exact base tree, and the declared frontier must materialize.
      _ <- Replay
        .materializeValidating(repo.patches, repo.frontier, validateChangesAgainstBase)
        .map(_ => ())
    } yield ()

  /** §4.3/§4.4: validate one patch's changes against its exact materialized base tree.
    *
    *   - delete requires the path present in the base;
    *   - a no-op change (identical bytes) is invalid;
    *   - an empty text edit is a create and requires the path absent from the base;
    *   - a text edit requires a text base and must consume the old tokens exactly while producing a
    *     canonical token sequence.
    */
  def validateChangesAgainstBase(patch: Patch, baseTree: Model.Tree): Either[SnapError, Unit] =
    firstError(patch.changes.map(ch => validateChangeAgainstBase(ch, baseTree)))

  /** §3.5: cross-repository dot collisions. Same dot with structurally different values is
    * corruption and must fail before any local mutation (tests 16, 26). Structurally equal
    * duplicates are allowed and collapse during merge.
    */
  def checkCollision(local: Vector[Patch], remote: Vector[Patch]): Either[SnapError, Unit] = {
    val localByDot = local.map(p => (p.author.value, p.revision) -> p).toMap
    remote.collectFirst {
      case rp
          if localByDot
            .get((rp.author.value, rp.revision))
            .exists(lp => !Patch.sameValue(lp, rp)) =>
        SnapError.PatchCollision(rp.author.value, rp.revision)
    } match {
      case Some(err) => Left(err)
      case None      => Right(())
    }
  }

  /** The frontier after importing `incoming` patches: the componentwise join of the local frontier
    * with every incoming patch's result version.
    */
  def joinedFrontier(localFrontier: Version, incoming: Vector[Patch]): Version =
    incoming.foldLeft(localFrontier)((acc, p) => Version.join(acc, p.result))

  /** §4.1: `version` is known (materializable) when it lies within the frontier and its selected
    * patch set is base-closed. Commands (`diff`, `revert`) reject unknown versions.
    */
  def knownVersion(repo: Repository, version: Version): Either[SnapError, Unit] =
    if (!Version.knownIn(version, repo.frontier)) Left(SnapError.UnknownVersion(version.render))
    else {
      val selected = repo.patches.filter(p => version.get(p.author) >= p.revision)
      val dots = selected.map(p => (p.author.value, p.revision)).toSet
      selected.iterator
        .flatMap(_.base.components)
        .collectFirst {
          case (cid, rev) if !dots.contains((cid.value, rev)) =>
            SnapError.MissingPatch(cid.value, rev)
        } match {
        case Some(err) => Left(err)
        case None      => Right(())
      }
    }

  // ---------------------------------------------------------------------------
  // §4.5 steps 1–9: shape, ordering, dots, closure, acyclicity
  // ---------------------------------------------------------------------------

  /** Step 1: frontier components sorted by unsigned-UTF-8 author with unique IDs. */
  private def checkFrontierSorted(frontier: Version): Either[SnapError, Unit] = {
    val cs = frontier.components
    val sorted = cs.sortWith((a, b) => Model.utf8Compare(a._1.value, b._1.value) < 0)
    val unique = sorted.distinctBy(_._1.value)
    if (cs == unique) Right(())
    else
      Left(SnapError.NonCanonicalFrontier(Version(cs).render, Version(unique).render))
  }

  /** Step 2: patches sorted by (author unsigned-UTF-8, numeric revision). Structurally equal
    * adjacent duplicates are allowed (collapsed later); reverse order is a format violation.
    */
  private def checkPatchesSorted(patches: Vector[Patch]): Either[SnapError, Unit] = {
    val unsorted = patches.sliding(2).exists {
      case Vector(p1, p2) =>
        val c = Model.utf8Compare(p1.author.value, p2.author.value)
        c > 0 || (c == 0 && p1.revision > p2.revision)
      case _ => false
    }
    if (unsorted)
      Left(SnapError.InvalidJson("repository: patches are not sorted by author and revision"))
    else Right(())
  }

  /** Step 3: changes within one patch sorted by path, at most one change per path. */
  private def checkChangesSortedAndUnique(p: Patch): Either[SnapError, Unit] = {
    val paths = p.changes.map(_.path)
    paths.indices.drop(1).find(i => Model.utf8Compare(paths(i - 1), paths(i)) >= 0) match {
      case Some(i) if paths(i - 1) == paths(i) =>
        Left(SnapError.TreePathsConflict(paths(i)))
      case Some(_) =>
        Left(
          SnapError.InvalidJson(
            s"repository: changes in patch ${p.author.value} revision ${p.revision} are not sorted by path"
          )
        )
      case None => Right(())
    }
  }

  /** Step 4: SPEC §4.2 — `revision = base[author] + 1`. A base already containing the patch's own
    * dot (or beyond) is a self-referencing, i.e. cyclic or inconsistent, history.
    */
  private def checkDotConsistency(p: Patch): Either[SnapError, Unit] =
    if (p.revision == p.base.get(p.author) + 1L) Right(())
    else Left(SnapError.CyclicOrIncompleteHistory)

  /** Step 5: one value per dot; structurally equal duplicates are fine, anything else is
    * corruption.
    */
  private def checkDotCollisions(patches: Vector[Patch]): Either[SnapError, Unit] = {
    val seen = mutable.HashMap.empty[(String, Long), Patch]
    var failure: Option[SnapError] = None
    val it = patches.iterator
    while (it.hasNext && failure.isEmpty) {
      val p = it.next()
      val key = (p.author.value, p.revision)
      seen.get(key) match {
        case Some(prev) if !Patch.sameValue(prev, p) =>
          failure = Some(SnapError.PatchCollision(p.author.value, p.revision))
        case Some(_) => ()
        case None    => seen.update(key, p)
      }
    }
    failure match {
      case Some(err) => Left(err)
      case None      => Right(())
    }
  }

  /** Step 6: for every author appearing in patches or the frontier, revisions 1..max are all
    * present. The first missing revision is reported.
    */
  private def checkContiguity(repo: Repository): Either[SnapError, Unit] = {
    val maxRev = mutable.HashMap.empty[String, Long]
    def bump(id: String, rev: Long): Unit =
      if (rev > maxRev.getOrElse(id, 0L)) maxRev.update(id, rev)
    repo.patches.foreach(p => bump(p.author.value, p.revision))
    repo.frontier.components.foreach { case (cid, rev) => bump(cid.value, rev) }

    val present: Map[String, Set[Long]] =
      repo.patches.groupBy(_.author.value).view.mapValues(_.map(_.revision).toSet).toMap

    val authors = maxRev.keys.toVector.sortWith((a, b) => Model.utf8Compare(a, b) < 0)
    var failure: Option[SnapError] = None
    val it = authors.iterator
    while (it.hasNext && failure.isEmpty) {
      val author = it.next()
      val revs = present.getOrElse(author, Set.empty[Long])
      var k = 1L
      while (k <= maxRev(author) && failure.isEmpty) {
        if (!revs.contains(k)) failure = Some(SnapError.MissingPatch(author, k))
        k += 1
      }
    }
    failure match {
      case Some(err) => Left(err)
      case None      => Right(())
    }
  }

  /** Step 7: every dot referenced in every base exists as a patch. */
  private def checkBaseClosure(patches: Vector[Patch]): Either[SnapError, Unit] = {
    val dots = patches.map(p => (p.author.value, p.revision)).toSet
    patches.iterator
      .flatMap(_.base.components)
      .collectFirst {
        case (id, rev) if !dots.contains((id.value, rev)) => SnapError.MissingPatch(id.value, rev)
      } match {
      case Some(err) => Left(err)
      case None      => Right(())
    }
  }

  /** Step 8: every patch lies in the causal closure of the frontier — no patch dot beyond the
    * frontier, and walking bases back from the frontier dots reaches every patch.
    */
  private def checkReachability(repo: Repository): Either[SnapError, Unit] = {
    repo.patches.collectFirst {
      case p if p.revision > repo.frontier.get(p.author) =>
        SnapError.UnreachablePatch(p.author.value, p.revision)
    } match {
      case Some(err) => Left(err)
      case None =>
        val byDot = repo.patches.map(p => (p.author.value, p.revision) -> p).toMap
        val visited = mutable.HashSet.empty[(String, Long)]
        val queue = mutable.Queue.empty[(String, Long)]
        repo.frontier.components.foreach { case (cid, rev) =>
          val key = (cid.value, rev)
          if (byDot.contains(key) && visited.add(key)) queue.enqueue(key)
        }
        while (queue.nonEmpty) {
          val key = queue.dequeue()
          byDot.get(key).foreach { p =>
            p.base.components.foreach { case (cid, rev) =>
              val k = (cid.value, rev)
              if (byDot.contains(k) && visited.add(k)) queue.enqueue(k)
            }
          }
        }
        repo.patches.collectFirst {
          case p if !visited.contains((p.author.value, p.revision)) =>
            SnapError.UnreachablePatch(p.author.value, p.revision)
        } match {
          case Some(err) => Left(err)
          case None      => Right(())
        }
    }
  }

  // ---------------------------------------------------------------------------
  // §4.5 steps 10–14: per-patch structural checks
  // ---------------------------------------------------------------------------

  private def checkPatchStructure(p: Patch): Either[SnapError, Unit] =
    for {
      _ <- Model.validateStoredMessage(p.message)
      _ <- if (p.changes.isEmpty) Left(SnapError.EmptyField("patch", "changes")) else Right(())
      _ <- firstError(p.changes.map(checkChangeStructure))
      _ <- checkPrefixConflicts(p.changes)
    } yield ()

  private def checkChangeStructure(ch: Change): Either[SnapError, Unit] =
    ch match {
      case Change.Text(path, edit) =>
        checkEditOps(path, edit).flatMap(_ => Model.validatePath(path))
      case other => Model.validatePath(other.path)
    }

  /** Steps 11–12: edit-script shape beyond what the JSON layer enforces — no adjacent same-kind
    * operations, positive counts, non-empty inserts, and canonical insert-token sequences.
    */
  private def checkEditOps(path: String, edit: Vector[EditOp]): Either[SnapError, Unit] =
    adjacentKind(edit) match {
      case Some(kind) => Left(SnapError.AdjacentSameKindOps(kind))
      case None =>
        edit.collectFirst {
          case EditOp.Retain(n) if n < 1L =>
            SnapError.NotPositiveSafeInteger("retain count")
          case EditOp.Delete(n) if n < 1L =>
            SnapError.NotPositiveSafeInteger("delete count")
          case EditOp.Insert(tokens) if tokens.isEmpty =>
            SnapError.EmptyField("edit", "insert")
          case EditOp.Insert(tokens)
              if !tokens.forall(Model.isValidInsertToken) ||
                !Model.isCanonicalTokenSeq(tokens) =>
            SnapError.NonCanonicalTokens(path)
        } match {
          case Some(err) => Left(err)
          case None      => Right(())
        }
    }

  private def adjacentKind(edit: Vector[EditOp]): Option[String] = {
    def kind(op: EditOp): String = op match {
      case _: EditOp.Retain => "retain"
      case _: EditOp.Delete => "delete"
      case _: EditOp.Insert => "insert"
    }
    edit.sliding(2).collectFirst { case Vector(a, b) if kind(a) == kind(b) => kind(a) }
  }

  /** Step 14: change paths within one patch form a prefix-free set. */
  private def checkPrefixConflicts(changes: Vector[Change]): Either[SnapError, Unit] = {
    val paths = changes.map(_.path)
    var failure: Option[SnapError] = None
    var i = 0
    while (i < paths.length && failure.isEmpty) {
      var j = i + 1
      while (j < paths.length && failure.isEmpty) {
        if (
          Model.isProperAncestor(paths(i), paths(j)) ||
          Model.isProperAncestor(paths(j), paths(i))
        ) failure = Some(SnapError.TreePathsConflict(paths(j)))
        j += 1
      }
      i += 1
    }
    failure match {
      case Some(err) => Left(err)
      case None      => Right(())
    }
  }

  // ---------------------------------------------------------------------------
  // §4.5 step 15: change-vs-base validation
  // ---------------------------------------------------------------------------

  private def validateChangeAgainstBase(
      ch: Change,
      baseTree: Model.Tree
  ): Either[SnapError, Unit] = {
    val path = ch.path
    val base = baseTree.get(path)
    ch match {
      case Change.Del(_) =>
        if (base.isEmpty) Left(SnapError.DeleteOfAbsentPath(path)) else Right(())

      case Change.Put(_, bytes) =>
        base match {
          case Some(existing) if Model.bytesEqual(existing, bytes) =>
            Left(SnapError.NoOpChange(path))
          case _ => Right(())
        }

      case Change.Text(_, edit) =>
        base match {
          case Some(_) if edit.isEmpty =>
            // An empty text edit is the create form (§4.4) and requires an absent path.
            Left(SnapError.CreateOfPresentPath(path))
          case Some(existing) if !Model.isText(existing) =>
            Left(SnapError.TextOverBinaryBase(path))
          case _ =>
            val baseTokens =
              base.flatMap(b => Model.decodeUtf8(b).map(Model.tokenize)).getOrElse(Vector.empty)
            Model.applyEdit(baseTokens, edit, path).flatMap { resultTokens =>
              val resultBytes = Model.utf8Bytes(Model.detokenize(resultTokens))
              if (!Model.isText(resultBytes)) Left(SnapError.NonCanonicalTokens(path))
              else if (base.exists(b => Model.bytesEqual(b, resultBytes)))
                Left(SnapError.NoOpChange(path))
              else Right(())
            }
        }
    }
  }

  private def firstError(checks: Vector[Either[SnapError, Unit]]): Either[SnapError, Unit] =
    checks.collectFirst { case Left(err) => err } match {
      case Some(err) => Left(err)
      case None      => Right(())
    }
}
