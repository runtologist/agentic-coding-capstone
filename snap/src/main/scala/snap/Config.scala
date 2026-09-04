package snap

import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

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

  private def writeConfigFile(path: Path, id: Model.ContributorId): IO[SnapError, Unit] =
    ZIO
      .attemptBlocking {
        val parent = path.getParent
        if (parent != null) Files.createDirectories(parent)
        Files.write(path, Json.writeConfig(Json.ConfigFile(id)).getBytes(UTF_8))
        ()
      }
      .mapError(toSnapError)

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
  ): IO[SnapError, Option[Model.ContributorId]] =
    ZIO
      .attemptBlocking {
        val local = repoRoot.map(localConfigPath).filter(p => Files.isRegularFile(p))
        local match {
          case Some(path) => readConfigId(path).map(id => Some(id))
          case None =>
            val global = home.map(globalConfigPath).filter(p => Files.isRegularFile(p))
            global match {
              case Some(path) => readConfigId(path).map(id => Some(id))
              case None       => Right(None)
            }
        }
      }
      .mapError(toSnapError)
      .absolve

  private def readConfigId(path: Path): Either[SnapError, Model.ContributorId] =
    try {
      val content = new String(Files.readAllBytes(path), UTF_8)
      Json.parseConfig(content).map(_.id)
    } catch {
      case NonFatal(e) => Left(toSnapError(e))
    }
}
