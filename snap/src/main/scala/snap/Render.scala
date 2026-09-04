package snap

import snap.Model.*

/** Pure output rendering for the two presentations (CONTRACT §3–§5, SPEC §7.11).
  *
  * Plain mode is the byte-stable interface pinned by the acceptance suite; terminal mode adds
  * semantic color but never changes execution, warning selection/order, or exit status. Every
  * function is total and side-effect free; the caller selects a [[Presentation]] per stream via
  * [[resolvePresentation]].
  */
object Render {

  val snapVersion: String = "1.0.0"

  private val Esc = "\u001b"

  enum Presentation {
    case Plain, Terminal
  }

  /** S(n, text) = ESC[<n>m + text + ESC[0m (SPEC §7.11). */
  private def sgr(code: Int, text: String): String = s"$Esc[${code}m$text$Esc[0m"

  enum SuccessLabel(val text: String) {
    case InitializedRepository extends SuccessLabel("Initialized repository")
    case Committed extends SuccessLabel("Committed")
    case Reverted extends SuccessLabel("Reverted")
    case Merged extends SuccessLabel("Merged")
  }

  /** Status change kinds (SPEC §7.3): plain code letter, terminal (color, symbol, label). The
    * deleted symbol is U+2212 MINUS SIGN, not ASCII hyphen (test 28).
    */
  enum StatusKind(val code: String, val color: Int, val symbol: String, val label: String) {
    case Added extends StatusKind("A", 32, "+", "added")
    case Modified extends StatusKind("M", 33, "~", "modified")
    case Deleted extends StatusKind("D", 31, "\u2212", "deleted")
  }

  /** One log line's data; `message` is raw and escaped at render time (SPEC §7.4 order: backslash,
    * then tab, then LF). Entries are rendered in the order given — callers supply reverse canonical
    * integration order.
    */
  final case class LogEntry(version: Version, author: String, message: String)

  /** One changed path in a diff. `Text` carries the old side's tokens plus the canonical edit
    * script (SPEC §5); `Binary` covers any change where either side is non-text (SPEC §7.6).
    */
  sealed trait DiffEntry {
    def path: String
  }
  object DiffEntry {
    final case class Text(
        path: String,
        oldPresent: Boolean,
        newPresent: Boolean,
        oldTokens: Vector[String],
        edit: Vector[EditOp]
    ) extends DiffEntry
    final case class Binary(path: String, oldPresent: Boolean, newPresent: Boolean)
        extends DiffEntry
  }

  // ---------------------------------------------------------------------------
  // Presentation selection (CONTRACT §5)
  // ---------------------------------------------------------------------------

  /** Resolve (stdout, stderr) presentations. `NO_COLOR` presence — any value including empty —
    * forces plain in auto mode; `always` overrides `NO_COLOR`; any other value is an error before
    * command execution (that error is itself rendered plain by the caller).
    */
  def resolvePresentation(
      colorEnv: Option[String],
      noColorPresent: Boolean,
      stdoutIsTty: Boolean,
      stderrIsTty: Boolean
  ): Either[SnapError, (Presentation, Presentation)] =
    colorEnv match {
      case None | Some("auto") =>
        def forStream(isTty: Boolean): Presentation =
          if (isTty && !noColorPresent) Presentation.Terminal else Presentation.Plain
        Right((forStream(stdoutIsTty), forStream(stderrIsTty)))
      case Some("always") => Right((Presentation.Terminal, Presentation.Terminal))
      case Some("never")  => Right((Presentation.Plain, Presentation.Plain))
      case Some(_)        => Left(SnapError.InvalidSnapColor)
    }

  // ---------------------------------------------------------------------------
  // Plain + terminal renderers
  // ---------------------------------------------------------------------------

  /** Successful init/commit/revert/merge (SPEC §7.11). */
  def successLine(label: SuccessLabel, version: String, p: Presentation): String =
    p match {
      case Presentation.Plain => version + "\n"
      case Presentation.Terminal =>
        sgr(32, "\u2713") + " " + sgr(1, label.text) + " " + sgr(36, version) + "\n"
    }

  def versionLine(p: Presentation): String =
    p match {
      case Presentation.Plain    => s"snap $snapVersion\n"
      case Presentation.Terminal => sgr(1, s"snap $snapVersion") + "\n"
    }

  /** Status output; rows are sorted by unsigned UTF-8 path bytes regardless of input order. */
  def status(version: Version, rows: Seq[(String, StatusKind)], p: Presentation): String = {
    val sorted = rows.sortWith((a, b) => Model.utf8Compare(a._1, b._1) < 0)
    p match {
      case Presentation.Plain =>
        val sb = new StringBuilder
        sb.append("version ").append(version.render).append('\n')
        sorted.foreach { case (path, kind) =>
          sb.append(kind.code).append(' ').append(path).append('\n')
        }
        sb.result()
      case Presentation.Terminal =>
        val sb = new StringBuilder
        sb.append(sgr(1, "Snap status"))
          .append("  ")
          .append(sgr(36, version.render))
          .append("\n\n")
        if (sorted.isEmpty)
          sb.append("  ").append(sgr(32, "\u2713")).append(" Working tree clean").append('\n')
        else
          sorted.foreach { case (path, kind) =>
            sb.append("  ")
              .append(sgr(kind.color, kind.symbol))
              .append(' ')
              .append(path)
              .append(' ')
              .append(sgr(2, s"(${kind.label})"))
              .append('\n')
          }
        sb.result()
    }
  }

  /** Log output; one tab-separated line per entry in plain mode, styled two-line entries with a
    * blank line between entries in terminal mode.
    */
  def log(entries: Seq[LogEntry], p: Presentation): String =
    p match {
      case Presentation.Plain =>
        entries
          .map(e => s"${e.version.render}\t${e.author}\t${Model.escapeLogMessage(e.message)}\n")
          .mkString
      case Presentation.Terminal =>
        entries
          .map { e =>
            val escaped = Model.escapeLogMessage(e.message)
            sgr(36, "\u25cf") + " " + sgr(1, escaped) + "\n  " +
              sgr(36, e.version.render) + " " + sgr(2, "by") + " " + sgr(35, e.author) + "\n"
          }
          .mkString("\n")
    }

  /** One stderr warning line; terminal mode drops the `warning: ` prefix (test 28). */
  def warningLine(w: ReplayWarning, p: Presentation): String =
    p match {
      case Presentation.Plain    => s"warning: ${w.detail}\n"
      case Presentation.Terminal => sgr(33, "\u26a0") + " " + sgr(33, w.detail) + "\n"
    }

  /** One stderr error line; terminal mode wraps the entire plain line including `snap: `. */
  def errorLine(err: SnapError, p: Presentation): String =
    p match {
      case Presentation.Plain    => s"snap: ${err.detail}\n"
      case Presentation.Terminal => sgr(31, s"\u2717 snap: ${err.detail}") + "\n"
    }

  /** The `--serve` startup URL — always plain, even under `SNAP_COLOR=always` (test 28). */
  def serveUrlLine(port: Int): String = s"http://127.0.0.1:$port/repository.json\n"

  // ---------------------------------------------------------------------------
  // Diff rendering (CONTRACT §10)
  // ---------------------------------------------------------------------------

  /** Unified-style whole-file blocks, sorted by unsigned UTF-8 path order; empty when there are no
    * differences.
    */
  def diff(entries: Seq[DiffEntry], p: Presentation): String = {
    val sorted = entries.sortWith((a, b) => Model.utf8Compare(a.path, b.path) < 0)
    val plain = sorted.map(diffEntryPlain).mkString
    p match {
      case Presentation.Plain    => plain
      case Presentation.Terminal => colorizeDiff(plain)
    }
  }

  private def diffEntryPlain(entry: DiffEntry): String =
    entry match {
      case DiffEntry.Binary(path, oldPresent, newPresent) =>
        s"Binary files ${diffSide(path, oldPresent, "a")} and ${diffSide(path, newPresent, "b")} differ\n"
      case t: DiffEntry.Text =>
        val sb = new StringBuilder
        sb.append(s"--- ${diffSide(t.path, t.oldPresent, "a")}\n")
        sb.append(s"+++ ${diffSide(t.path, t.newPresent, "b")}\n")
        val (oldCount, newCount) = tokenCounts(t.oldTokens, t.edit)
        sb.append(s"@@ -1,$oldCount +1,$newCount @@\n")
        var oldIndex = 0
        t.edit.foreach {
          case EditOp.Retain(n) =>
            var k = 0L
            while (k < n) {
              sb.append(tokenLine(" ", t.oldTokens(oldIndex)))
              oldIndex += 1
              k += 1
            }
          case EditOp.Delete(n) =>
            var k = 0L
            while (k < n) {
              sb.append(tokenLine("-", t.oldTokens(oldIndex)))
              oldIndex += 1
              k += 1
            }
          case EditOp.Insert(tokens) =>
            tokens.foreach(tok => sb.append(tokenLine("+", tok)))
        }
        sb.result()
    }

  private def diffSide(path: String, present: Boolean, prefix: String): String =
    if (present) s"$prefix/$path" else "/dev/null"

  /** Hunk counts: old = consumed old tokens, new = retained + inserted tokens. */
  private def tokenCounts(oldTokens: Vector[String], edit: Vector[EditOp]): (Int, Long) = {
    var retained = 0L
    var inserted = 0L
    edit.foreach {
      case EditOp.Retain(n)      => retained += n
      case EditOp.Insert(tokens) => inserted += tokens.length
      case EditOp.Delete(_)      => ()
    }
    (oldTokens.length, retained + inserted)
  }

  /** Print one token line: the token without its trailing LF; a token lacking a final LF is
    * followed by the `\ No newline at end of file` marker line (SPEC §7.6).
    */
  private def tokenLine(prefix: String, token: String): String =
    if (token.endsWith("\n")) s"$prefix${token.dropRight(1)}\n"
    else s"$prefix$token\n\\ No newline at end of file\n"

  /** Terminal diff: preserve every plain byte; wrap each line's text (excluding LF) with the first
    * applicable style — `--- `/`+++ ` → 1, `@@ ` → 36, `-` → 31, `+` → 32, `\ ` → 2, `Binary files
    * ` → 33; context lines unchanged (SPEC §7.11).
    */
  private def colorizeDiff(plain: String): String =
    if (plain.isEmpty) ""
    else {
      val endsWithNewline = plain.endsWith("\n")
      val colored = plain.split('\n').map(colorizeDiffLine).mkString("\n")
      if (endsWithNewline) colored + "\n" else colored
    }

  private def colorizeDiffLine(line: String): String = {
    val style: Option[Int] =
      if (line.startsWith("--- ") || line.startsWith("+++ ")) Some(1)
      else if (line.startsWith("@@ ")) Some(36)
      else if (line.startsWith("-")) Some(31)
      else if (line.startsWith("+")) Some(32)
      else if (line.startsWith("\\ ")) Some(2)
      else if (line.startsWith("Binary files ")) Some(33)
      else None
    style.fold(line)(code => sgr(code, line))
  }
}
