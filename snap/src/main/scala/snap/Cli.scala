package snap

import snap.Model.Port

/** CLI argument grammar (CONTRACT §1, SPEC §7).
  *
  * Pure parsing: no I/O, no filesystem effects — grammar errors must never create files (test 24
  * asserts this via `path_not_exists`). Version operands stay raw strings; the Commands layer
  * parses them so error ordering matches CONTRACT §13 (e.g. revert reports `unknown version` before
  * a missing contributor id).
  */
object Cli {

  sealed trait Command

  object Command {
    final case class Init(path: Option[String]) extends Command
    final case class Config(global: Boolean, id: String) extends Command
    case object Status extends Command
    case object Log extends Command
    final case class Commit(message: String) extends Command
    final case class Diff(oldRaw: Option[String], newRaw: Option[String], repo: Option[String])
        extends Command
    final case class Revert(versionRaw: String) extends Command
    final case class Merge(repo: String) extends Command
    final case class Serve(port: Port) extends Command
    case object ShowVersion extends Command
  }

  /** Canonical diff usage text, pinned by SnapErrorSpec and rendered as `usage: snap diff [<old>
    * <new> [--repo <repository>]]`.
    */
  val DiffUsageText: String = "[<old> <new> [--repo <repository>]]"

  private def isOption(token: String): Boolean = token.startsWith("-")

  private val invalid: Left[SnapError, Nothing] = Left(SnapError.InvalidCommandOrArguments)

  private val diffUsage: Left[SnapError, Nothing] = Left(SnapError.DiffUsage(DiffUsageText))

  def parse(args: Seq[String]): Either[SnapError, Command] = {
    val a = args.toVector
    a.headOption match {
      case None => invalid
      case Some("--version") =>
        if (a.length == 1) Right(Command.ShowVersion) else invalid
      case Some("--serve") =>
        a.tail match {
          case Vector()        => Right(Command.Serve(Port.default))
          case Vector(rawPort) => Port.parse(rawPort).map(Command.Serve(_))
          case _               => invalid
        }
      case Some("init") =>
        a.tail match {
          case Vector()                  => Right(Command.Init(None))
          case Vector(p) if !isOption(p) => Right(Command.Init(Some(p)))
          case _                         => invalid
        }
      case Some("config") => parseConfig(a.tail)
      case Some("status") => noExtraArgs(a.tail, Command.Status)
      case Some("log")    => noExtraArgs(a.tail, Command.Log)
      case Some("commit") =>
        a.tail match {
          case Vector(m) if !isOption(m) => Right(Command.Commit(m))
          case _                         => invalid
        }
      case Some("diff") => parseDiff(a.tail)
      case Some("revert") =>
        a.tail match {
          case Vector(v) if !isOption(v) => Right(Command.Revert(v))
          case _                         => invalid
        }
      case Some("merge") =>
        a.tail match {
          case Vector(r) if !isOption(r) => Right(Command.Merge(r))
          case _                         => invalid
        }
      case Some(_) => invalid
    }
  }

  private def noExtraArgs(rest: Vector[String], cmd: Command): Either[SnapError, Command] =
    if (rest.isEmpty) Right(cmd) else invalid

  /** `config [--global] contributor.id <id>` — `--global` only in first position, at most once; the
    * literal token `contributor.id` is required; the id is a single non-option operand.
    */
  private def parseConfig(rest: Vector[String]): Either[SnapError, Command] = {
    val (global, afterFlag) =
      if (rest.headOption.contains("--global")) (true, rest.tail) else (false, rest)
    afterFlag match {
      case Vector("contributor.id", id) if !isOption(id) => Right(Command.Config(global, id))
      case _                                             => invalid
    }
  }

  /** `diff` / `diff <old> <new>` / `diff <old> <new> --repo <repository>`. Every grammar error in
    * diff renders the usage line instead of the generic grammar error (tests 14/24).
    */
  private def parseDiff(rest: Vector[String]): Either[SnapError, Command] = {
    var operands = Vector.empty[String]
    var repo: Option[String] = None
    var i = 0
    var failed = false
    while (i < rest.length && !failed) {
      val token = rest(i)
      if (token == "--repo") {
        // --repo only after both version operands, at most once, value required.
        if (operands.length != 2 || repo.isDefined || i + 1 >= rest.length) failed = true
        else {
          repo = Some(rest(i + 1))
          i += 1
        }
      } else if (isOption(token)) failed = true
      else operands = operands :+ token
      i += 1
    }
    if (failed) diffUsage
    else
      operands.length match {
        case 0 if repo.isEmpty => Right(Command.Diff(None, None, None))
        case 2                 => Right(Command.Diff(Some(operands(0)), Some(operands(1)), repo))
        case _                 => diffUsage
      }
  }
}
