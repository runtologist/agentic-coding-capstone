package snap

import zio.*

import java.nio.file.{DirectoryNotEmptyException, Files, LinkOption, NoSuchFileException, Path}
import java.nio.file.attribute.BasicFileAttributes
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Filesystem view of the working tree (SPEC §2, CONTRACT §9).
  *
  * Snap tracks every regular file below the repository root except `.snap/` and its contents.
  * Directories are implicit; empty directories are not tracked. Symlinks, FIFOs, and any other
  * non-regular entry are unsupported: a scan reports the first such entry (in deterministic
  * unsigned-UTF-8 order) and fails without reading further. All failures map into [[SnapError]] —
  * no raw exception escapes this module.
  */
object WorkingTree {

  /** A single row of `status` output: a path plus whether it was added, modified, or deleted. */
  type StatusRow = (String, Render.StatusKind)

  private val SnapDirName = ".snap"

  private def toSnapError(t: Throwable): SnapError = t match {
    case e: SnapError => e
    case other        => SnapError.IoFailure(Option(other.getMessage).getOrElse(other.toString))
  }

  // ---------------------------------------------------------------------------
  // Scanning
  // ---------------------------------------------------------------------------

  /** Recursively scan `root`, returning the tracked path → bytes map.
    *
    * Skips the top-level `.snap/` directory entirely. Only regular files are tracked; bytes are
    * read as-is (arbitrary binary). Any symlink, FIFO, or other non-regular entry fails the whole
    * scan with [[SnapError.UnsupportedEntry]] naming the offending relative path. Entries are
    * visited in unsigned-UTF-8 sorted order at every directory level, so the reported unsupported
    * entry is deterministic.
    */
  def scan(root: Path): IO[SnapError, Model.Tree] =
    ZIO
      .attemptBlocking(scanOrError(root))
      .mapError(toSnapError)
      .absolve

  private def scanOrError(root: Path): Either[SnapError, Model.Tree] = {
    val acc = mutable.LinkedHashMap.empty[String, Array[Byte]]
    walkDir(root, "", isTop = true, acc) match {
      case Left(err) => Left(err)
      case Right(_)  => Right(acc.toMap)
    }
  }

  private def walkDir(
      dir: Path,
      prefix: String,
      isTop: Boolean,
      acc: mutable.LinkedHashMap[String, Array[Byte]]
  ): Either[SnapError, Unit] = {
    val stream =
      try Files.list(dir)
      catch { case NonFatal(e) => return Left(toSnapError(e)) }
    val children =
      try stream.iterator().asScala.toVector
      finally stream.close()

    val sorted =
      children.sortWith((a, b) =>
        Model.utf8Compare(a.getFileName.toString, b.getFileName.toString) < 0
      )

    sorted.foldLeft[Either[SnapError, Unit]](Right(())) { (state, entry) =>
      state match {
        case l @ Left(_) => l
        case Right(_) =>
          val name = entry.getFileName.toString
          // The repository's own metadata directory is never tracked (SPEC §2).
          if (isTop && name == SnapDirName) Right(())
          else processEntry(entry, name, prefix, acc)
      }
    }
  }

  private def processEntry(
      entry: Path,
      name: String,
      prefix: String,
      acc: mutable.LinkedHashMap[String, Array[Byte]]
  ): Either[SnapError, Unit] = {
    val rel = if (prefix.isEmpty) name else s"$prefix/$name"
    val attrs =
      try Files.readAttributes(entry, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      catch { case NonFatal(e) => return Left(toSnapError(e)) }

    if (attrs.isSymbolicLink) Left(SnapError.UnsupportedEntry(rel))
    else if (attrs.isDirectory) walkDir(entry, rel, isTop = false, acc)
    else if (attrs.isRegularFile) {
      val bytes =
        try Files.readAllBytes(entry)
        catch { case NonFatal(e) => return Left(toSnapError(e)) }
      acc += rel -> bytes
      Right(())
    } else Left(SnapError.UnsupportedEntry(rel))
  }

  // ---------------------------------------------------------------------------
  // Comparison (pure)
  // ---------------------------------------------------------------------------

  /** Sorted status rows comparing `current` (the committed tree) against `working`.
    *
    * `A` = absent → present, `M` = bytes changed, `D` = present → absent. Rows are ordered by
    * unsigned-UTF-8 path bytes (SPEC §2; test 25). Pure — no filesystem access.
    */
  def compare(current: Model.Tree, working: Model.Tree): Vector[StatusRow] = {
    val allPaths = (current.keySet ++ working.keySet).toVector
      .sortWith((a, b) => Model.utf8Compare(a, b) < 0)
    allPaths.flatMap { p =>
      (current.get(p), working.get(p)) match {
        case (None, Some(_)) => Some((p, Render.StatusKind.Added))
        case (Some(a), Some(b)) =>
          if (Model.bytesEqual(a, b)) None else Some((p, Render.StatusKind.Modified))
        case (Some(_), None) => Some((p, Render.StatusKind.Deleted))
        case (None, None)    => None
      }
    }
  }

  /** A tree is clean when its path/byte map exactly equals the current tree (SPEC §2). */
  def isClean(current: Model.Tree, working: Model.Tree): Boolean =
    Model.treeEqual(current, working)

  // ---------------------------------------------------------------------------
  // Materialization
  // ---------------------------------------------------------------------------

  /** Make the on-disk tree under `root` exactly equal `target` (SPEC §6.2 installation, §10).
    *
    * Used by `revert` and `merge` after the caller has verified the tree is clean. Steps:
    *   1. delete tracked files absent from `target`; 2. remove any directory that blocks a required
    *      file path (dir→file transition); 3. create required directories and write target bytes
    *      (file→dir transition handled here); 4. prune directories that became empty as a result of
    *      this materialization, leaving unrelated pre-existing empty directories intact.
    * `.snap/` is never touched.
    */
  def materialize(root: Path, target: Model.Tree): IO[SnapError, Unit] =
    ZIO
      .attemptBlocking(materializeOrError(root, target))
      .mapError(toSnapError)
      .absolve

  private def materializeOrError(root: Path, target: Model.Tree): Either[SnapError, Unit] = {
    val absRoot = root.toAbsolutePath.normalize
    try {
      val currentFiles = collectRegularFiles(absRoot)
      val targetPaths = target.keySet

      // Paths whose parents may need pruning after deletion.
      val touchedParents = mutable.Set.empty[Path]

      // 1. Delete tracked files that are absent from the target.
      for (rel <- currentFiles if !targetPaths.contains(rel)) {
        val fp = absRoot.resolve(rel)
        Files.deleteIfExists(fp)
        recordAncestors(fp, absRoot, touchedParents)
      }

      // 2 & 3. Install every target file, resolving dir/file conflicts.
      for (rel <- Model.sortedPaths(target)) {
        val fp = absRoot.resolve(rel)
        if (Files.exists(fp, LinkOption.NOFOLLOW_LINKS)) {
          val attrs =
            Files.readAttributes(fp, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          if (attrs.isDirectory) {
            deleteRecursively(fp)
            recordAncestors(fp, absRoot, touchedParents)
          } else if (attrs.isSymbolicLink) {
            Files.delete(fp)
            recordAncestors(fp, absRoot, touchedParents)
          }
        }
        ensureParentDirs(absRoot, fp)
        Files.write(fp, target(rel))
      }

      // 4. Prune directories newly emptied by the deletions above (deepest first).
      pruneEmpty(touchedParents, absRoot)

      Right(())
    } catch {
      case NonFatal(e) => Left(toSnapError(e))
    }
  }

  /** Collect relative paths of all regular files under `root`, skipping the top-level `.snap`.
    * Lenient: unsupported entries are simply not collected (callers guarantee a clean tree before
    * materialization).
    */
  private def collectRegularFiles(root: Path): Vector[String] = {
    val out = Vector.newBuilder[String]
    def walk(dir: Path, prefix: String, isTop: Boolean): Unit = {
      val stream = Files.list(dir)
      val children =
        try stream.iterator().asScala.toVector
        finally stream.close()
      val sorted = children
        .sortWith((a, b) => Model.utf8Compare(a.getFileName.toString, b.getFileName.toString) < 0)
      sorted.foreach { entry =>
        val name = entry.getFileName.toString
        if (isTop && name == SnapDirName) ()
        else {
          val rel = if (prefix.isEmpty) name else s"$prefix/$name"
          val attrs =
            Files.readAttributes(entry, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          if (attrs.isDirectory) walk(entry, rel, isTop = false)
          else if (attrs.isRegularFile) out += rel
          else () // unsupported entry: not collected
        }
      }
    }
    walk(root, "", isTop = true)
    out.result()
  }

  private def recordAncestors(p: Path, root: Path, into: mutable.Set[Path]): Unit = {
    var cur = p.getParent
    while (cur != null && cur != root && cur.startsWith(root)) {
      into += cur
      cur = cur.getParent
    }
  }

  /** Create every parent directory of `file`, removing any regular file or symlink that blocks a
    * required directory (file→dir transition). Never touches `root` itself.
    */
  private def ensureParentDirs(root: Path, file: Path): Unit = {
    val parent = file.getParent
    if (parent == null || parent == root) return
    // Build the chain from just-below-root down to the parent.
    var chain = List.empty[Path]
    var cur = parent
    while (cur != null && cur != root && cur.startsWith(root)) {
      chain ::= cur
      cur = cur.getParent
    }
    chain.foreach { d =>
      if (Files.exists(d, LinkOption.NOFOLLOW_LINKS)) {
        val attrs = Files.readAttributes(d, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
        if (attrs.isSymbolicLink || !attrs.isDirectory) Files.delete(d)
      }
    }
    Files.createDirectories(parent)
  }

  private def deleteRecursively(dir: Path): Unit = {
    val stream = Files.walk(dir)
    val all =
      try stream.iterator().asScala.toVector
      finally stream.close()
    all.sortBy(_.getNameCount)(Ordering[Int].reverse).foreach { p =>
      try Files.deleteIfExists(p)
      catch { case _: DirectoryNotEmptyException | _: NoSuchFileException => () }
    }
  }

  private def pruneEmpty(candidates: mutable.Set[Path], root: Path): Unit = {
    val ordered = candidates.toVector.sortBy(_.getNameCount)(Ordering[Int].reverse)
    ordered.foreach { d =>
      if (
        d != root && d.startsWith(root) && d.getFileName.toString != SnapDirName &&
        Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)
      ) {
        try Files.delete(d) // succeeds only if the directory is empty
        catch { case _: DirectoryNotEmptyException | _: NoSuchFileException => () }
      }
    }
  }
}
