package snap

import zio.*

import java.nio.file.{DirectoryNotEmptyException, Files, LinkOption, NoSuchFileException, Path}
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.*

/** Filesystem view of the working tree (SPEC §2, CONTRACT §9).
  *
  * Snap tracks every regular file below the repository root except `.snap/` and its contents.
  * Directories are implicit; empty directories are not tracked. Symlinks, FIFOs, and any other
  * non-regular entry are unsupported: a scan reports the first such entry (in deterministic
  * unsigned-UTF-8 order) and fails without reading further. Every collected path is validated
  * against the tracked-path rules (SPEC §2) so scanning commands reject §2-illegal names the same
  * way commit does. All failures map into [[SnapError]] — no raw exception escapes this module.
  */
object WorkingTree {

  /** A single row of `status` output: a path plus whether it was added, modified, or deleted. */
  type StatusRow = (String, Render.StatusKind)

  private val SnapDirName = ".snap"

  private def toSnapError(t: Throwable): SnapError = t match {
    case e: SnapError => e
    case other        => SnapError.IoFailure(Option(other.getMessage).getOrElse(other.toString))
  }

  /** A single blocking filesystem operation, mapped into [[SnapError]] on failure. */
  private def fs[A](op: => A): IO[SnapError, A] =
    ZIO.attemptBlocking(op).mapError(toSnapError)

  /** Children of `dir`, sorted by unsigned UTF-8 bytes of their file names. */
  private def listSortedChildren(dir: Path): IO[SnapError, Vector[Path]] =
    fs {
      val stream = Files.list(dir)
      try stream.iterator().asScala.toVector
      finally stream.close()
    }.map(
      _.sortWith((a, b) => Model.utf8Compare(a.getFileName.toString, b.getFileName.toString) < 0)
    )

  private def attributes(path: Path): IO[SnapError, BasicFileAttributes] =
    fs(Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS))

  private def relPath(prefix: String, name: String): String =
    if (prefix.isEmpty) name else s"$prefix/$name"

  // ---------------------------------------------------------------------------
  // Scanning
  // ---------------------------------------------------------------------------

  /** Recursively scan `root`, returning the tracked path → bytes map.
    *
    * Skips the top-level `.snap/` directory entirely. Only regular files are tracked; bytes are
    * read as-is (arbitrary binary). Any symlink, FIFO, or other non-regular entry fails the whole
    * scan with [[SnapError.UnsupportedEntry]] naming the offending relative path, and any tracked
    * path violating SPEC §2 rules fails with [[SnapError.InvalidRepoPath]]. Entries are visited in
    * unsigned-UTF-8 sorted order at every directory level, so the first offending entry is reported
    * deterministically.
    */
  def scan(root: Path): IO[SnapError, Model.Tree] =
    scanDir(root, "", isTop = true).map(_.toMap)

  private def scanDir(
      dir: Path,
      prefix: String,
      isTop: Boolean
  ): IO[SnapError, Vector[(String, Array[Byte])]] =
    for {
      sorted <- listSortedChildren(dir)
      collected <- ZIO.foreach(sorted) { entry =>
        val name = entry.getFileName.toString
        // F-utf8b: if lossy decoding produced a replacement character, re-exec is needed.
        if (Jnu.decodedNameNeedsReexec(Jnu.lossyRisk, name)) ZIO.die(Jnu.ReexecRequired)
        // The repository's own metadata directory is never tracked (SPEC §2).
        else if (isTop && name == SnapDirName) ZIO.succeed(Vector.empty[(String, Array[Byte])])
        else scanEntry(entry, relPath(prefix, name))
      }
    } yield collected.flatten

  private def scanEntry(entry: Path, rel: String): IO[SnapError, Vector[(String, Array[Byte])]] =
    for {
      attrs <- attributes(entry)
      result <-
        if (attrs.isSymbolicLink) ZIO.fail(SnapError.UnsupportedEntry(rel))
        else if (attrs.isDirectory) scanDir(entry, rel, isTop = false)
        else if (attrs.isRegularFile)
          for {
            _ <- ZIO.fromEither(Model.validatePath(rel))
            bytes <- fs(Files.readAllBytes(entry))
          } yield Vector(rel -> bytes)
        else ZIO.fail(SnapError.UnsupportedEntry(rel))
    } yield result

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
    * `.snap/` is never touched. Files are written before the caller persists repository.json.
    */
  def materialize(root: Path, target: Model.Tree): IO[SnapError, Unit] = {
    val absRoot = root.toAbsolutePath.normalize
    val targetPaths = target.keySet
    for {
      currentFiles <- collectRegularFiles(absRoot)
      // F-utf8b: if any path to write or delete contains non-ASCII chars, re-exec is needed.
      _ <-
        if (Jnu.writeNeedsReexec(Jnu.lossyRisk, targetPaths ++ currentFiles))
          ZIO.die(Jnu.ReexecRequired)
        else ZIO.unit
      removedAncestors <- ZIO.foreach(currentFiles.filterNot(targetPaths.contains)) { rel =>
        val file = absRoot.resolve(rel)
        fs(Files.deleteIfExists(file)).as(ancestorsOf(file, absRoot))
      }
      installedAncestors <- ZIO.foreach(Model.sortedPaths(target)) { rel =>
        installFile(absRoot, rel, target(rel))
      }
      _ <- pruneEmpty((removedAncestors ++ installedAncestors).flatten.toSet, absRoot)
    } yield ()
  }

  /** Write one target file, first clearing any directory or symlink that occupies its path. Returns
    * the ancestors of any entry removed (candidates for empty-dir pruning).
    */
  private def installFile(
      absRoot: Path,
      rel: String,
      content: Array[Byte]
  ): IO[SnapError, Vector[Path]] = {
    val file = absRoot.resolve(rel)
    for {
      exists <- fs(Files.exists(file, LinkOption.NOFOLLOW_LINKS))
      removedAncestors <-
        if (!exists) ZIO.succeed(Vector.empty[Path])
        else
          for {
            attrs <- attributes(file)
            ancestors <-
              if (attrs.isDirectory) deleteRecursively(file).as(ancestorsOf(file, absRoot))
              else if (attrs.isSymbolicLink) fs(Files.delete(file)).as(ancestorsOf(file, absRoot))
              else ZIO.succeed(Vector.empty[Path])
          } yield ancestors
      _ <- ensureParentDirs(absRoot, file)
      _ <- fs(Files.write(file, content))
    } yield removedAncestors
  }

  /** Collect relative paths of all regular files under `root`, skipping the top-level `.snap`.
    * Lenient: unsupported entries are simply not collected (callers guarantee a clean tree before
    * materialization).
    */
  private def collectRegularFiles(root: Path): IO[SnapError, Vector[String]] = {
    def walk(dir: Path, prefix: String, isTop: Boolean): IO[SnapError, Vector[String]] =
      for {
        sorted <- listSortedChildren(dir)
        collected <- ZIO.foreach(sorted) { entry =>
          val name = entry.getFileName.toString
          if (isTop && name == SnapDirName) ZIO.succeed(Vector.empty[String])
          else {
            val rel = relPath(prefix, name)
            attributes(entry).flatMap { attrs =>
              if (attrs.isDirectory) walk(entry, rel, isTop = false)
              else if (attrs.isRegularFile) ZIO.succeed(Vector(rel))
              else ZIO.succeed(Vector.empty) // unsupported entry: not collected
            }
          }
        }
      } yield collected.flatten

    walk(root, "", isTop = true)
  }

  /** Ancestors of `path` strictly between `path` and `root` (pure path arithmetic). */
  private def ancestorsOf(path: Path, root: Path): Vector[Path] = {
    val builder = Vector.newBuilder[Path]
    var cur = path.getParent
    while (cur != null && cur != root && cur.startsWith(root)) {
      builder += cur
      cur = cur.getParent
    }
    builder.result()
  }

  /** Create every parent directory of `file`, removing any regular file or symlink that blocks a
    * required directory (file→dir transition). Never touches `root` itself.
    */
  private def ensureParentDirs(root: Path, file: Path): IO[SnapError, Unit] = {
    val parent = file.getParent
    if (parent == null || parent == root) ZIO.unit
    else {
      // Chain from just-below-root down to the parent.
      val chain = List
        .unfold(parent) { cur =>
          if (cur == null || cur == root || !cur.startsWith(root)) None
          else Some((cur, cur.getParent))
        }
        .reverse
      for {
        _ <- ZIO.foreachDiscard(chain) { dir =>
          for {
            exists <- fs(Files.exists(dir, LinkOption.NOFOLLOW_LINKS))
            _ <- ZIO.when(exists) {
              for {
                attrs <- attributes(dir)
                _ <- ZIO.when(attrs.isSymbolicLink || !attrs.isDirectory)(fs(Files.delete(dir)))
              } yield ()
            }
          } yield ()
        }
        _ <- fs { Files.createDirectories(parent); () }
      } yield ()
    }
  }

  private def deleteRecursively(dir: Path): IO[SnapError, Unit] =
    for {
      all <- fs {
        val stream = Files.walk(dir)
        try stream.iterator().asScala.toVector
        finally stream.close()
      }
      deepestFirst = all.sortBy(_.getNameCount)(Ordering[Int].reverse)
      _ <- ZIO.foreachDiscard(deepestFirst)(deleteQuietly)
    } yield ()

  /** Delete a path, tolerating entries that already vanished or directories that still hold files
    * (those are pruned separately, only when empty).
    */
  private def deleteQuietly(path: Path): IO[SnapError, Unit] =
    ZIO
      .attemptBlocking(Files.deleteIfExists(path))
      .unit
      .catchSome {
        case _: DirectoryNotEmptyException => ZIO.unit
        case _: NoSuchFileException        => ZIO.unit
      }
      .mapError(toSnapError)

  private def pruneEmpty(candidates: Set[Path], root: Path): IO[SnapError, Unit] = {
    val deepestFirst = candidates.toVector.sortBy(_.getNameCount)(Ordering[Int].reverse)
    ZIO.foreachDiscard(deepestFirst) { dir =>
      if (dir == root || !dir.startsWith(root) || dir.getFileName.toString == SnapDirName)
        ZIO.unit
      else
        for {
          isDir <- fs(Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
          // Succeeds only if the directory is empty.
          _ <- ZIO.when(isDir)(deleteQuietly(dir))
        } yield ()
    }
  }
}
