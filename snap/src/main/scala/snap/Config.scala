package snap

import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** Contributor configuration read/write (SPEC §8, CONTRACT §8).
  *
  * Shape is exactly `{"contributor":{"id":"<id>"}}`. Local config lives at
  * `<repoRoot>/.snap/config.json`; global config lives at `$HOME/.snapconfig.json`. Reads use
  * [[Json.parseConfig]], which tolerates trailing bytes after the first complete JSON value
  * (CONTRACT §15 ruling A) but rejects duplicate keys, unknown fields, and invalid ids.
  */
object Config {

  val GlobalConfigFileName = ".snapconfig.json"

  private def toSnapError(t: Throwable): SnapError = t match {
    case e: SnapError => e
    case other        => SnapError.IoFailure(Option(other.getMessage).getOrElse(other.toString))
  }

  /** Wrap a single blocking filesystem call, mapping any exception to [[SnapError]]. */
  private def io[A](a: => A): IO[SnapError, A] =
    ZIO.attemptBlocking(a).mapError(toSnapError)

  def localConfigPath(repoRoot: Path): Path = RepoIo.localConfigFile(repoRoot)

  def globalConfigPath(home: Path): Path = home.resolve(GlobalConfigFileName)

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /** Write (overwrite) local config. Unknown fields from any pre-existing file are dropped because
    * the writer serializes only the canonical shape (test 25). The id must already be validated via
    * [[Model.ContributorId.parse]] by the caller (command layer).
    */
  def writeLocal(repoRoot: Path, id: Model.ContributorId): IO[SnapError, Unit] =
    writeConfigFile(localConfigPath(repoRoot), id)

  /** Write (overwrite) global config at `$HOME/.snapconfig.json`. */
  def writeGlobal(home: Path, id: Model.ContributorId): IO[SnapError, Unit] =
    writeConfigFile(globalConfigPath(home), id)

  private def writeConfigFile(path: Path, id: Model.ContributorId): IO[SnapError, Unit] = {
    val parent = path.getParent
    for {
      _ <- if (parent == null) ZIO.unit else io(Files.createDirectories(parent))
      _ <- io(Files.write(path, Json.writeConfig(Json.ConfigFile(id)).getBytes(UTF_8)))
    } yield ()
  }

  // ---------------------------------------------------------------------------
  // Resolution
  // ---------------------------------------------------------------------------

  /** Resolve the contributor id, local-then-global (SPEC §8, CONTRACT §8).
    *
    *   - Local file present: parsed and validated; ANY failure is fatal — no fallback to global
    *     (test 25: invalid local id with valid global errors `invalid contributor id`).
    *   - Local file missing: fall through to global.
    *   - `home = None` (no `$HOME`) → global config unavailable, not an error (test 19).
    *   - Neither file provides an id → `Right(None)`; the calling command decides when to fail with
    *     [[SnapError.ContributorIdRequired]].
    */
  def resolveContributor(
      repoRoot: Option[Path],
      home: Option[Path]
  ): IO[SnapError, Option[Model.ContributorId]] = {
    def existingFile(p: Path): IO[SnapError, Option[Path]] =
      io(Files.isRegularFile(p)).map(if (_) Some(p) else None)

    def readFrom(pathOpt: Option[Path]): IO[SnapError, Option[Model.ContributorId]] =
      pathOpt match {
        case Some(path) => readConfigId(path).map(Some(_))
        case None       => ZIO.succeed(None)
      }

    for {
      localPath <- repoRoot match {
        case Some(root) => existingFile(localConfigPath(root))
        case None       => ZIO.succeed(None)
      }
      result <- localPath match {
        case Some(path) => readFrom(Some(path))
        case None =>
          for {
            globalPath <- home match {
              case Some(h) => existingFile(globalConfigPath(h))
              case None    => ZIO.succeed(None)
            }
            res <- readFrom(globalPath)
          } yield res
      }
    } yield result
  }

  /** Read and parse a config file, decoding bytes strictly as UTF-8. */
  private def readConfigId(path: Path): IO[SnapError, Model.ContributorId] =
    for {
      bytes <- io(Files.readAllBytes(path))
      content <- ZIO
        .fromOption(Model.decodeUtf8(bytes))
        .orElseFail(SnapError.InvalidJson("config is not valid UTF-8"))
      cfg <- ZIO.fromEither(Json.parseConfig(content))
    } yield cfg.id
}
