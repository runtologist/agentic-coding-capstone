package snap

import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{AtomicMoveNotSupportedException, Files, LinkOption, Path, StandardCopyOption}

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

  /** Wrap a single blocking filesystem call, mapping any exception to [[SnapError]]. */
  private def io[A](a: => A): IO[SnapError, A] =
    ZIO.attemptBlocking(a).mapError(toSnapError)

  def snapDir(root: Path): Path = root.resolve(SnapDirName)
  def repoFile(root: Path): Path = snapDir(root).resolve(RepoFileName)
  def localConfigFile(root: Path): Path = snapDir(root).resolve(ConfigFileName)

  // ---------------------------------------------------------------------------
  // Discovery
  // ---------------------------------------------------------------------------

  /** Walk from `start` up to the filesystem root and return the nearest directory containing a
    * `.snap/` directory (SPEC §7; test 19 requires discovery from nested cwd). Returns `None` when
    * no repository is found. Any I/O failure during the walk is treated as "no repository".
    */
  def discoverRepo(start: Path): UIO[Option[Path]] = {
    def loop(cur: Path): IO[SnapError, Option[Path]] =
      if (cur == null) ZIO.succeed(None)
      else
        for {
          isRepo <- io(Files.isDirectory(cur.resolve(SnapDirName), LinkOption.NOFOLLOW_LINKS))
          found <- if (isRepo) ZIO.succeed(Some(cur)) else loop(cur.getParent)
        } yield found

    loop(start.toAbsolutePath.normalize).catchAll(_ => ZIO.succeed(None))
  }

  // ---------------------------------------------------------------------------
  // Load / write
  // ---------------------------------------------------------------------------

  /** Read `<root>/.snap/repository.json`, decode strictly as UTF-8, parse via
    * [[Json.parseRepository]], then run full semantic validation via [[Codec.validateRepository]]
    * (SPEC §4.5). Missing metadata file → [[SnapError.NotASnapRepository]]; invalid UTF-8 or
    * malformed JSON → [[SnapError.InvalidJson]]; I/O failures → [[SnapError.IoFailure]]. Never
    * mutates.
    */
  def loadRepository(root: Path): IO[SnapError, Model.Repository] = {
    val file = repoFile(root)
    for {
      isFile <- io(Files.isRegularFile(file))
      repo <-
        if (!isFile) ZIO.fail(SnapError.NotASnapRepository)
        else
          for {
            bytes <- io(Files.readAllBytes(file))
            content <- ZIO
              .fromOption(Model.decodeUtf8(bytes))
              .orElseFail(SnapError.InvalidJson("repository is not valid UTF-8"))
            parsed <- ZIO.fromEither(Json.parseRepository(content))
            _ <- ZIO.fromEither(Codec.validateRepository(parsed))
          } yield parsed
    } yield repo
  }

  /** Serialize with [[Json.writeRepository]] (canonical pretty bytes + trailing LF) and replace
    * `.snap/repository.json` atomically via a same-directory temp file (SPEC §10, CONTRACT §14). On
    * any failure the temp file is removed.
    */
  def writeRepositoryAtomic(root: Path, repo: Model.Repository): IO[SnapError, Unit] = {
    val dir = snapDir(root)
    val target = dir.resolve(RepoFileName)
    for {
      _ <- io(Files.createDirectories(dir))
      tmp <- io(Files.createTempFile(dir, ".repository-", ".tmp"))
      _ <- (for {
        _ <- io(Files.write(tmp, Json.writeRepository(repo).getBytes(UTF_8)))
        _ <- io {
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
        }
      } yield ()).onError((_: Cause[SnapError]) => io(Files.deleteIfExists(tmp)).ignore)
    } yield ()
  }

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
  def init(target: Path): IO[SnapError, Unit] = {
    val abs = target.toAbsolutePath.normalize
    val meta = abs.resolve(SnapDirName)
    for {
      metaExists <- io(Files.exists(meta, LinkOption.NOFOLLOW_LINKS))
      _ <- ZIO.when(metaExists)(ZIO.fail(SnapError.RepositoryAlreadyExists))
      inside <- isInsideRepository(abs)
      _ <- ZIO.when(inside)(ZIO.fail(SnapError.CannotInitializeInsideRepository))
      _ <- io(Files.createDirectories(abs))
      _ <- io(Files.createDirectories(meta))
      empty = Model.Repository(Model.Version.empty, Vector.empty)
      _ <- io(Files.write(meta.resolve(RepoFileName), Json.writeRepository(empty).getBytes(UTF_8)))
    } yield ()
  }

  /** True when any strict ancestor of `abs` contains a `.snap` directory. */
  private def isInsideRepository(abs: Path): IO[SnapError, Boolean] = {
    def loop(cur: Path): IO[SnapError, Boolean] =
      if (cur == null) ZIO.succeed(false)
      else
        for {
          isDir <- io(Files.isDirectory(cur.resolve(SnapDirName), LinkOption.NOFOLLOW_LINKS))
          res <- if (isDir) ZIO.succeed(true) else loop(cur.getParent)
        } yield res
    loop(abs.getParent)
  }
}
