package snap

/** Expected failures. Every variant renders as `snap: <detail>` on stderr with process exit 1.
  *
  * Message strings are a frozen contract: the public YAML suite pins many of them exactly or via
  * substring/regex. See docs/snap/CONTRACT.md §1–§13 before changing any text.
  */
sealed abstract class SnapError(val detail: String) extends RuntimeException(detail)

object SnapError {

  // -- CLI grammar ---------------------------------------------------------------
  case object InvalidCommandOrArguments extends SnapError("invalid command or arguments")

  final case class DiffUsage(usage: String) extends SnapError(s"usage: snap diff $usage")

  final case class InvalidPort(input: String) extends SnapError(s"invalid port: $input")

  case object InvalidSnapColor extends SnapError("SNAP_COLOR must be auto, always, or never")

  // -- repository discovery / init ------------------------------------------------
  case object NotASnapRepository extends SnapError("not a Snap repository")

  case object RepositoryAlreadyExists extends SnapError("repository already exists")

  case object CannotInitializeInsideRepository
      extends SnapError("cannot initialize inside repository")

  // -- configuration ----------------------------------------------------------------
  final case class InvalidContributorId(reason: String)
      extends SnapError(s"invalid contributor id: $reason")

  final case class InvalidJson(detail0: String) extends SnapError(s"invalid JSON: $detail0")

  final case class DuplicateJsonKey(name: String) extends SnapError(s"duplicate JSON key $name")

  case object ContributorIdRequired
      extends SnapError("contributor.id is required; configure it locally or globally")

  // -- versions -----------------------------------------------------------------------
  final case class InvalidVersion(reason: String) extends SnapError(s"invalid version: $reason")

  final case class UnknownVersion(rendered: String) extends SnapError(s"unknown version: $rendered")

  // -- working tree / commit / revert ---------------------------------------------------
  case object WorkingTreeClean extends SnapError("working tree is clean")

  case object WorkingTreeDirty extends SnapError("working tree is dirty")

  final case class UnsupportedEntry(path: String)
      extends SnapError(s"unsupported working tree entry: $path")

  case object InvalidCommitMessage extends SnapError("invalid commit message")

  case object TargetTreeAlreadyCurrent extends SnapError("target tree is already current")

  // -- repository / patch validation: one case per distinct failure --------------------
  final case class UnknownRepoField(field: String)
      extends SnapError(s"repository has unknown field: $field")

  final case class UnknownPatchField(field: String, author: String, revision: Long)
      extends SnapError(s"patch $author revision $revision has unknown field: $field")

  final case class UnknownChangeField(field: String, author: String, revision: Long)
      extends SnapError(s"change in patch $author revision $revision has unknown field: $field")

  final case class MissingPatch(author: String, revision: Long)
      extends SnapError(s"missing $author revision $revision")

  final case class UnreachablePatch(author: String, revision: Long)
      extends SnapError(s"unreachable patch: $author revision $revision")

  case object CyclicOrIncompleteHistory extends SnapError("cyclic or incomplete patch history")

  final case class PatchCollision(author: String, revision: Long)
      extends SnapError(s"patch collision: $author revision $revision")

  final case class InvalidRepoPath(path: String, reason: String)
      extends SnapError(
        if (path.isEmpty) s"path is invalid: $reason" else s"path is invalid: $path"
      )

  final case class NonCanonicalBase64(path: String)
      extends SnapError(
        if (path.isEmpty) "content is not canonical base64"
        else s"content for $path is not canonical base64"
      )

  final case class NotPositiveSafeInteger(context: String)
      extends SnapError(s"$context is not a positive safe integer")

  final case class EmptyField(scope: String, field: String)
      extends SnapError(s"$scope $field is empty")

  case object EditOpWrongArity extends SnapError("edit entry must have one operation")

  final case class AdjacentSameKindOps(kind: String)
      extends SnapError(s"adjacent $kind operations in edit script")

  final case class EditNotConsuming(path: String)
      extends SnapError(
        if (path.isEmpty) "edit script does not consume old content"
        else s"edit for $path does not consume old content"
      )

  final case class EditOverconsumes(path: String)
      extends SnapError(
        if (path.isEmpty) "edit script consumes beyond old content"
        else s"edit for $path consumes beyond old content"
      )

  final case class NonCanonicalTokens(path: String)
      extends SnapError(
        if (path.isEmpty) "edit script result is not a canonical token sequence"
        else s"edit result for $path is not a canonical token sequence"
      )

  final case class TreePathsConflict(path: String) extends SnapError(s"tree paths conflict: $path")

  final case class NoOpChange(path: String) extends SnapError(s"no-op change: $path")

  final case class DeleteOfAbsentPath(path: String)
      extends SnapError(s"delete of absent path: $path")

  final case class CreateOfPresentPath(path: String)
      extends SnapError(s"create of present path: $path")

  final case class TextOverBinaryBase(path: String)
      extends SnapError(s"text change over binary base: $path")

  final case class InvalidMessage(reason: String) extends SnapError(s"patch message $reason")

  final case class NonCanonicalFrontier(found: String, expected: String)
      extends SnapError(s"frontier is not canonical: found $found, expected $expected")

  // -- HTTP / IO / internal ---------------------------------------------------------------
  final case class HttpStatus(status: Int, url: String)
      extends SnapError(s"HTTP $status fetching $url")

  final case class IoFailure(detail0: String) extends SnapError(detail0)

  case object InternalError extends SnapError("internal error")
}
