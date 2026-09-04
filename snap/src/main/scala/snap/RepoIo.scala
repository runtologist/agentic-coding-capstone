package snap

import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{AtomicMoveNotSupportedException, Files, LinkOption, Path, StandardCopyOption}
import scala.util.control.NonFatal

/** Repository discovery, loading, atomic persistence, and initialization (SPEC §2, §7.1, §10;
  * CONTRACT §9, §13, §14).
  *
  * All filesystem failures are mapped into [[SnapError]]; no raw exception escapes. Loading and
  * validation never mutate state (SPEC §10). `writeRepositoryAtomic` replaces
  * `.snap/repository.json` through a same-directory temporary file and atomic rename.
  */
object RepoIo {

  val SnapDirName = ".snap"
  val RepoFileName = "repository.json"
  val ConfigFileName = "config.json"

  private def toSnapError(t: Throwable): SnapError = t match {
    case e: SnapError => e
    case other        => SnapError.IoFailure(Option(other.getMessage).getOrElse(other.toString))
  }

  def snapDir(root: Path): Path = root.resolve(SnapDirName)
  def repoFile(root: Path): Path = snapDir(root).resolve(RepoFileName)
  def localConfigFile(root: Path): Path = snapDir(root).resolve(ConfigFileName)

  // ---------------------------------------------------------------------------
  // Discovery
  // ---------------------------------------------------------------------------

  /** Walk from `start` up to the filesystem root and return the nearest directory containing a
    * `.snap/` directory (SPEC §7; test 19 requires discovery from nested cwd). Returns `None` when
    * no repository is found.
    */
  def discoverRepo(start: Path): UIO[Option[Path]] =
    ZIO
      .attemptBlocking {
        var cur: Path = start.toAbsolutePath.normalize
        var found: Option[Path] = None
        while (found.isEmpty && cur != null) {
          if (Files.isDirectory(cur.resolve(SnapDirName), LinkOption.NOFOLLOW_LINKS))
            found = Some(cur)
          else cur = cur.getParent
        }
        found
      }
      .catchAll(_ => ZIO.succeed(None))

  // ---------------------------------------------------------------------------
  // Load / write
  // ---------------------------------------------------------------------------

  /** Read `<root>/.snap/repository.json`, decode via [[Json.parseRepository]], then run full
    * semantic validation via [[Codec.validateRepository]] (SPEC §4.5). Missing metadata file →
    * [[SnapError.NotASnapRepository]]; I/O failures → [[SnapError.IoFailure]]. Never mutates.
    */
  def loadRepository(root: Path): IO[SnapError, Model.Repository] =
    ZIO
      .attemptBlocking {
        val file = repoFile(root)
        if (!Files.isRegularFile(file))
          Left[SnapError, Model.Repository](SnapError.NotASnapRepository)
        else {
          val content = new String(Files.readAllBytes(file), UTF_8)
          Json.parseRepository(content) match {
            case Left(err)   => Left(err)
            case Right(repo) => Codec.validateRepository(repo).map(_ => repo)
          }
        }
      }
      .mapError(toSnapError)
      .absolve

  /** Serialize with [[Json.writeRepository]] (canonical pretty bytes + trailing LF) and replace
    * `.snap/repository.json` atomically via a same-directory temp file (SPEC §10, CONTRACT §14).
    */
  def writeRepositoryAtomic(root: Path, repo: Model.Repository): IO[SnapError, Unit] =
    ZIO
      .attemptBlocking {
        val dir = snapDir(root)
        Files.createDirectories(dir)
        val target = dir.resolve(RepoFileName)
        var tmp: Path = null
        try {
          tmp = Files.createTempFile(dir, ".repository-", ".tmp")
          Files.write(tmp, Json.writeRepository(repo).getBytes(UTF_8))
          try
            Files.move(
              tmp,
              target,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING
            )
          catch {
            case _: AtomicMoveNotSupportedException =>
              Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
          }
          tmp = null // moved successfully; nothing to clean up
          Right(())
        } catch {
          case NonFatal(e) => Left(toSnapError(e))
        } finally {
          if (tmp != null)
            try Files.deleteIfExists(tmp)
            catch { case NonFatal(_) => () }
        }
      }
      .mapError(toSnapError)
      .absolve

  // ---------------------------------------------------------------------------
  // Init
  // ---------------------------------------------------------------------------

  /** `snap init [path]` core logic (SPEC §7.1, tests 01/02).
    *
    *   - target already contains `.snap` → [[SnapError.RepositoryAlreadyExists]];
    *   - any strict ancestor contains `.snap` → [[SnapError.CannotInitializeInsideRepository]] and
    *     nothing is created;
    *   - otherwise the target directory (with intermediates) and `.snap/` are created, existing
    *     working files are preserved, and an empty repository.json is written:
    *     `{"format":1,"frontier":[],"patches":[]}`.
    */
  def init(target: Path): IO[SnapError, Unit] =
    ZIO
      .attemptBlocking {
        val abs = target.toAbsolutePath.normalize
        val meta = abs.resolve(SnapDirName)
        if (Files.exists(meta, LinkOption.NOFOLLOW_LINKS))
          Left[SnapError, Unit](SnapError.RepositoryAlreadyExists)
        else if (isInsideRepository(abs))
          Left[SnapError, Unit](SnapError.CannotInitializeInsideRepository)
        else {
          Files.createDirectories(abs)
          Files.createDirectories(meta)
          val empty = Model.Repository(Model.Version.empty, Vector.empty)
          Files.write(meta.resolve(RepoFileName), Json.writeRepository(empty).getBytes(UTF_8))
          Right(())
        }
      }
      .mapError(toSnapError)
      .absolve

  /** True when any strict ancestor of `abs` contains a `.snap` directory. */
  private def isInsideRepository(abs: Path): Boolean = {
    var cur = abs.getParent
    while (cur != null) {
      if (Files.isDirectory(cur.resolve(SnapDirName), LinkOption.NOFOLLOW_LINKS)) return true
      cur = cur.getParent
    }
    false
  }
}
