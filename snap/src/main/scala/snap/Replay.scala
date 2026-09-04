package snap

import snap.Model.*

import scala.collection.mutable

/** Deterministic replay of patch histories (SPEC §6).
  *
  * Pure functions: no I/O, no clock, no randomness. Every ordering decision goes through
  * [[Model.Version.snapOrder]] and [[Model.utf8Compare]].
  *
  * `materialize` selects the patches `(c, n)` with `n <= target[c]`, integrates them in the
  * canonical §6.1 order starting from the empty tree, and returns the final tree plus the unique
  * §6.4 warnings sorted by path then reason. The same valid patch set and target always produce the
  * same bytes and warning set (§6.5), independent of the input order of `patches`.
  */
object Replay {

  /** SPEC §6.1: canonical integration order.
    *
    * Structurally equal duplicate dots collapse first; different values at one dot are corruption
    * (`PatchCollision`). Then repeatedly pick the least ready patch — ready means every dot of its
    * base is already integrated — ordered by (1) Snap order of result versions, (2) unsigned UTF-8
    * author, (3) numeric revision. When patches remain but none is ready, the history has a cycle
    * or a missing dependency.
    */
  def integrationOrder(patches: Vector[Patch]): Either[SnapError, Vector[Patch]] =
    dedupePatches(patches) match {
      case Left(err) => Left(err)
      case Right(deduped) =>
        val integrated = mutable.HashSet.empty[(String, Long)]
        val remaining = mutable.ArrayBuffer.from(deduped)
        val out = Vector.newBuilder[Patch]
        var failure: Option[SnapError] = None
        while (remaining.nonEmpty && failure.isEmpty) {
          var best = -1
          var i = 0
          while (i < remaining.length) {
            val p = remaining(i)
            val ready = p.base.components.forall(c => integrated.contains((c._1.value, c._2)))
            if (ready && (best < 0 || patchOrdering.compare(remaining(best), p) > 0)) best = i
            i += 1
          }
          if (best < 0) failure = Some(SnapError.CyclicOrIncompleteHistory)
          else {
            val chosen = remaining(best)
            out += chosen
            integrated += ((chosen.author.value, chosen.revision))
            remaining.remove(best)
          }
        }
        failure match {
          case Some(err) => Left(err)
          case None      => Right(out.result())
        }
    }

  /** Collapse structurally equal duplicate dots; different values at one dot are corruption (SPEC
    * §4.2, §3.5). Preserves first-occurrence order.
    */
  def dedupePatches(patches: Vector[Patch]): Either[SnapError, Vector[Patch]] = {
    val seen = mutable.LinkedHashMap.empty[(String, Long), Patch]
    var failure: Option[SnapError] = None
    val it = patches.iterator
    while (it.hasNext && failure.isEmpty) {
      val p = it.next()
      val key = (p.author.value, p.revision)
      seen.get(key) match {
        case Some(prev) if !Patch.sameValue(prev, p) =>
          failure = Some(SnapError.PatchCollision(p.author.value, p.revision))
        case Some(_) => () // structural duplicate: collapses to one patch
        case None    => seen(key) = p
      }
    }
    failure match {
      case Some(err) => Left(err)
      case None      => Right(seen.values.toVector)
    }
  }

  /** §6.1–§6.4: materialize the tree at `target` from `patches`. */
  def materialize(
      patches: Vector[Patch],
      target: Version
  ): Either[SnapError, (Model.Tree, Vector[ReplayWarning])] =
    materializeValidating(patches, target, (_, _) => Right(()))

  /** Like `materialize`, but calls `validate(patch, baseTree)` immediately before each patch is
    * integrated, where `baseTree` is the materialized exact base of the patch. Used by
    * [[Codec.validateRepository]] for the §4.5 change-against-base checks without re-walking the
    * history a second time.
    */
  def materializeValidating(
      patches: Vector[Patch],
      target: Version,
      validate: (Patch, Model.Tree) => Either[SnapError, Unit]
  ): Either[SnapError, (Model.Tree, Vector[ReplayWarning])] =
    dedupePatches(patches) match {
      case Left(err) => Left(err)
      case Right(deduped) =>
        val memo = mutable.HashMap.empty[Version, Model.Tree]
        memo(Model.Version.empty) = Model.emptyTree
        val selected = deduped.filter(p => p.revision <= target.get(p.author))
        replaySelected(deduped, selected, validate, memo)
    }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private val patchOrdering: Ordering[Patch] = (a: Patch, b: Patch) => {
    val s = Version.snapOrder(a.result, b.result)
    if (s != 0) s
    else {
      val c = Model.utf8Compare(a.author.value, b.author.value)
      if (c != 0) c else java.lang.Long.compare(a.revision, b.revision)
    }
  }

  /** Integrate `selected` (a subset of `all` closed under bases) in canonical order. Base trees are
    * looked up through `memo`, so each distinct version's tree is computed once.
    */
  private def replaySelected(
      all: Vector[Patch],
      selected: Vector[Patch],
      validate: (Patch, Model.Tree) => Either[SnapError, Unit],
      memo: mutable.HashMap[Version, Model.Tree]
  ): Either[SnapError, (Model.Tree, Vector[ReplayWarning])] =
    integrationOrder(selected) match {
      case Left(err) => Left(err)
      case Right(order) =>
        var tree = Model.emptyTree
        val warnings = mutable.ArrayBuffer.empty[ReplayWarning]
        var failure: Option[SnapError] = None
        val it = order.iterator
        while (it.hasNext && failure.isEmpty) {
          val p = it.next()
          treeAt(all, p.base, memo) match {
            case Left(err) => failure = Some(err)
            case Right(baseTree) =>
              validate(p, baseTree) match {
                case Left(err) => failure = Some(err)
                case Right(()) =>
                  integratePatch(tree, baseTree, p) match {
                    case Left(err) => failure = Some(err)
                    case Right((next, ws)) =>
                      tree = next
                      warnings ++= ws
                  }
              }
          }
        }
        failure match {
          case Some(err) => Left(err)
          case None =>
            Right((tree, warnings.toVector.distinct.sorted(ReplayWarning.byPathThenReason)))
        }
    }

  /** Tree at version `target` from patch set `all`, memoized per version. `target` shrinks strictly
    * along base edges, so the recursion terminates for acyclic histories.
    */
  private def treeAt(
      all: Vector[Patch],
      target: Version,
      memo: mutable.HashMap[Version, Model.Tree]
  ): Either[SnapError, Model.Tree] =
    memo.get(target) match {
      case Some(tree) => Right(tree)
      case None =>
        val selected = all.filter(p => p.revision <= target.get(p.author))
        replaySelected(all, selected, (_, _) => Right(()), memo) match {
          case Left(err) => Left(err)
          case Right((tree, _)) =>
            memo(target) = tree
            Right(tree)
        }
    }

  /** SPEC §6.2: integrate one patch into the current canonical tree `current`, given the patch's
    * exact base tree `base`. Namespace conflicts are resolved for the whole patch first, then the
    * per-path rules 1–4 (§6.2/§6.4) decide every remaining changed path.
    */
  private def integratePatch(
      current: Model.Tree,
      base: Model.Tree,
      patch: Patch
  ): Either[SnapError, (Model.Tree, Vector[ReplayWarning])] = {
    val warnings = mutable.ArrayBuffer.empty[ReplayWarning]

    // Authored result per changed path: None means the patch deletes the path.
    val authored = mutable.LinkedHashMap.empty[String, Option[Array[Byte]]]
    var failure: Option[SnapError] = None
    val chIt = patch.changes.iterator
    while (chIt.hasNext && failure.isEmpty) {
      val change = chIt.next()
      change match {
        case Change.Del(path)        => authored(path) = None
        case Change.Put(path, bytes) => authored(path) = Some(bytes)
        case Change.Text(path, edit) =>
          val baseTokens = base.get(path) match {
            case Some(bytes) if Model.isText(bytes) =>
              Model.decodeUtf8(bytes).map(Model.tokenize).getOrElse(Vector.empty)
            case _ => Vector.empty
          }
          Model.applyEdit(baseTokens, edit, path) match {
            case Left(err) => failure = Some(err)
            case Right(tokens) =>
              authored(path) = Some(Model.utf8Bytes(Model.detokenize(tokens)))
          }
      }
    }
    failure match {
      case Some(err) => Left(err)
      case None      =>
        // Namespace resolution: S = paths the patch makes present; C' = current minus the patch's
        // authored deletions. A path in S with a proper ancestor or descendant in C' is installed
        // as authored and every conflicting current path is removed, each removal warning
        // namespace-wins. Duplicate removals and warnings collapse.
        val madePresent = authored.collect { case (path, Some(_)) => path }.toSet
        val authoredDeletes = authored.collect { case (path, None) => path }.toSet
        val nsInstall = mutable.Set.empty[String]
        val nsRemove = mutable.Set.empty[String]
        for (s <- madePresent; q <- current.keys) {
          if (
            !authoredDeletes.contains(q) &&
            (Model.isProperAncestor(s, q) || Model.isProperAncestor(q, s))
          ) {
            nsInstall += s
            if (nsRemove.add(q)) warnings += ReplayWarning.NamespaceWins(q)
          }
        }

        val installs = mutable.LinkedHashMap.empty[String, Array[Byte]]
        val removals = mutable.HashSet.empty[String]
        for (s <- nsInstall) installs(s) = authored(s).get
        removals ++= nsRemove

        val ruleIt = patch.changes.iterator
        while (ruleIt.hasNext && failure.isEmpty) {
          val change = ruleIt.next()
          val path = change.path
          if (!nsInstall.contains(path) && !nsRemove.contains(path)) {
            val bOpt = base.get(path)
            val cOpt = current.get(path)
            val tOpt = authored(path)
            if (sameContent(bOpt, cOpt)) {
              // Rule 1: identical in base and current → apply the authored change directly.
              tOpt match {
                case Some(bytes) => installs(path) = bytes
                case None        => removals += path
              }
            } else if (sameContent(cOpt, tOpt)) {
              // Rule 2: identical concurrent change collapses; keep current, no warning.
              ()
            } else {
              val isTextChange = change match {
                case _: Change.Text => true
                case _              => false
              }
              val allText = isTextChange &&
                bOpt.exists(Model.isText) && cOpt.exists(Model.isText) && tOpt.exists(Model.isText)
              if (allText) {
                // Rule 3: transform the patch's edit through the aggregate context edit (§6.3).
                val bTokens = Model.tokenize(Model.decodeUtf8(bOpt.get).get)
                val cTokens = Model.tokenize(Model.decodeUtf8(cOpt.get).get)
                val contextEdit = Diff.canonicalDiff(bTokens, cTokens)
                val transformed = Ot.transform(change.asInstanceOf[Change.Text].edit, contextEdit)
                Model.applyEdit(cTokens, transformed, path) match {
                  case Left(err) => failure = Some(err)
                  case Right(tokens) =>
                    installs(path) = Model.utf8Bytes(Model.detokenize(tokens))
                }
              } else if (tOpt.isEmpty) {
                // §6.4 rule 2: incoming delete wins.
                removals += path
                warnings += ReplayWarning.DeleteWins(path)
              } else if (bOpt.isDefined && cOpt.isEmpty) {
                // §6.4 rule 3: the earlier concurrent delete wins.
                removals += path
                warnings += ReplayWarning.DeleteWins(path)
              } else if (bOpt.isEmpty && cOpt.isDefined) {
                // §6.4 rule 4: the incoming (canonically later) create wins.
                installs(path) = tOpt.get
                warnings += ReplayWarning.LaterCreateWins(path)
              } else if (change.isInstanceOf[Change.Put]) {
                // §6.4 rule 5: the incoming atomic replacement wins.
                installs(path) = tOpt.get
                warnings += ReplayWarning.LaterPutWins(path)
              } else {
                // §6.4 rule 6: incoming text vs non-text current → current wins.
                warnings += ReplayWarning.PutWins(path)
              }
            }
          }
        }
        failure match {
          case Some(err) => Left(err)
          case None =>
            val next = (current -- removals) ++ installs
            Right((next, warnings.toVector))
        }
    }
  }

  private def sameContent(a: Option[Array[Byte]], b: Option[Array[Byte]]): Boolean =
    (a, b) match {
      case (None, None)       => true
      case (Some(x), Some(y)) => Model.bytesEqual(x, y)
      case _                  => false
    }
}
