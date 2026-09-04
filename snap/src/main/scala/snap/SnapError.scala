package snap

/** Expected failures. Every variant renders as `snap: <detail>` on stderr with process exit 1.
  *
  * Message strings are a frozen contract: the public YAML suite pins many of them exactly or via
  * substring/regex. See docs/snap/CONTRACT.md §1–§13 before changing any text.
  */
sealed abstract class SnapError(val detail: String) extends RuntimeException(detail)

object SnapError {

  // -- CLI grammar ----------------------------------------------------------
  case object InvalidCommandOrArguments extends SnapError("invalid command or arguments")

  final case class DiffUsage(usage: String) extends SnapError(s"usage: snap diff $usage")

  final case class InvalidPort(value: String) extends SnapError(s"invalid port: $value")

  final case class InvalidColorValue(value: String)
      extends SnapError("SNAP_COLOR must be auto, always, or never")

  // -- repository discovery / init -------------------------------------------
  case object NotASnapRepository extends SnapError("not a Snap repository")

  case object RepositoryAlreadyExists extends SnapError("repository already exists")

  case object CannotInitializeInsideRepository
      extends SnapError("cannot initialize inside repository")

  // -- configuration -----------------------------------------------------------
  final case class InvalidContributorId(reason: String)
      extends SnapError(s"invalid contributor id: $reason")

  final case class InvalidJson(detail0: String) extends SnapError(s"invalid JSON: $detail0")

  final case class DuplicateJsonKey(name: String) extends SnapError(s"duplicate JSON key $name")

  final case class InvalidConfig(detail0: String) extends SnapError(detail0)

  case object ContributorIdRequired
      extends SnapError("contributor.id is required; configure it locally or globally")

  // -- versions ---------------------------------------------------------------
  final case class InvalidVersion(reason: String) extends SnapError(s"invalid version: $reason")

  final case class UnknownVersion(version: String) extends SnapError(s"unknown version: $version")

  // -- working tree -------------------------------------------------------------
  case object WorkingTreeClean extends SnapError("working tree is clean")

  case object WorkingTreeDirty extends SnapError("working tree is dirty")

  final case class UnsupportedEntry(path: String)
      extends SnapError(s"unsupported working tree entry: $path")

  case object InvalidCommitMessage extends SnapError("invalid commit message")

  case object TargetTreeAlreadyCurrent extends SnapError("target tree is already current")

  // -- repository validation ------------------------------------------------------
  /** Generic schema/history validation failure. `detail0` must contain one of the substrings pinned
    * by the acceptance suite (see CONTRACT.md §7), e.g. "repository has unknown field: x", "missing
    * a@x revision 1", "cyclic or incomplete patch history".
    */
  final case class RepositoryInvalid(detail0: String) extends SnapError(detail0)

  final case class PatchCollision(author: String, revision: Long)
      extends SnapError(s"patch collision: $author revision $revision")

  // -- HTTP ---------------------------------------------------------------------
  final case class HttpStatus(status: Int, url: String)
      extends SnapError(s"HTTP $status fetching $url")

  // -- I/O and internal -----------------------------------------------------------
  final case class IoFailure(detail0: String) extends SnapError(detail0)
}
