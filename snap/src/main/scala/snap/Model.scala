package snap

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.util.Base64

/** Pure domain model for Snap: versions, tokens, edits, changes, patches, trees.
  *
  * All ordering the spec defines as "unsigned UTF-8 bytes" goes through [[Model.utf8Compare]] —
  * never `String.compareTo` (UTF-16 order diverges for non-BMP code points).
  */
object Model {

  /** JavaScript's maximum safe integer (SPEC §3.1). */
  val MaxSafeInteger: Long = 9007199254740991L

  /** Maximum UTF-8 byte length of a user-supplied commit message (SPEC §4.2/§7.5). */
  val MaxCommitMessageBytes: Int = 4096

  // ---------------------------------------------------------------------------
  // Unsigned UTF-8 byte ordering
  // ---------------------------------------------------------------------------

  def utf8Bytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  def utf8Compare(a: String, b: String): Int = {
    val ab = utf8Bytes(a)
    val bb = utf8Bytes(b)
    val n = math.min(ab.length, bb.length)
    var i = 0
    while (i < n) {
      val ai = ab(i) & 0xff
      val bi = bb(i) & 0xff
      if (ai != bi) return ai - bi
      i += 1
    }
    ab.length - bb.length
  }

  val PathOrdering: Ordering[String] = (a: String, b: String) => utf8Compare(a, b)

  /** Byte-exact equality for file contents. */
  def bytesEqual(a: Array[Byte], b: Array[Byte]): Boolean = java.util.Arrays.equals(a, b)

  type Tree = Map[String, Array[Byte]]

  def emptyTree: Tree = Map.empty

  def treeEqual(a: Tree, b: Tree): Boolean =
    a.size == b.size && a.forall { case (p, bytes) =>
      b.get(p).exists(other => java.util.Arrays.equals(bytes, other))
    }

  def sortedPaths(tree: Tree): Vector[String] =
    tree.keys.toVector.sortWith((x, y) => utf8Compare(x, y) < 0)

  // ---------------------------------------------------------------------------
  // Contributor IDs (SPEC §3.1)
  // ---------------------------------------------------------------------------

  final case class ContributorId(value: String) {
    override def toString: String = value
  }

  object ContributorId {

    /** ASCII email-shaped; exactly one '@' with nonempty sides; no control character, whitespace,
      * ',', '(', ')', or substring "->"; at most 254 bytes.
      */
    def parse(s: String): Either[SnapError, ContributorId] = {
      if (s.isEmpty) Left(SnapError.InvalidContributorId("must not be empty"))
      else if (utf8Bytes(s).length > 254)
        Left(SnapError.InvalidContributorId("must be at most 254 bytes"))
      else if (!s.forall(c => c >= 0x21 && c <= 0x7e && c != ',' && c != '(' && c != ')'))
        Left(SnapError.InvalidContributorId("contains a forbidden character"))
      else if (s.contains("->"))
        Left(SnapError.InvalidContributorId("must not contain '->'"))
      else if (s.count(_ == '@') != 1)
        Left(SnapError.InvalidContributorId("must contain exactly one '@'"))
      else if (s.indexOf('@') == 0 || s.indexOf('@') == s.length - 1)
        Left(SnapError.InvalidContributorId("must have text on both sides of '@'"))
      else Right(ContributorId(s))
    }
  }

  // ---------------------------------------------------------------------------
  // Versions (vector clocks, SPEC §3)
  // ---------------------------------------------------------------------------

  enum CausalOrder {
    case Equal, Before, After, Concurrent
  }

  /** A vector clock. `components` is always sorted by unsigned UTF-8 bytes of the contributor ID,
    * with unique IDs and nonzero revisions.
    */
  final case class Version(components: Vector[(ContributorId, Long)]) {
    def isEmpty: Boolean = components.isEmpty
    def ids: Vector[ContributorId] = components.map(_._1)

    def get(id: ContributorId): Long =
      components.find(_._1 == id).map(_._2).getOrElse(0L)

    /** SPEC §3.2 canonical CLI syntax: `()` or `(id->rev,id->rev)` sorted, no spaces. */
    def render: String =
      if (components.isEmpty) "()"
      else components.map { case (id, r) => s"${id.value}->$r" }.mkString("(", ",", ")")

    override def toString: String = render
  }

  object Version {
    val empty: Version = Version(Vector.empty)

    private def parseRevision(raw: String): Either[String, Long] = {
      if (raw.isEmpty || !raw.forall(c => c >= '0' && c <= '9'))
        Left(s"revision '$raw' is not a positive integer")
      else if (raw.length > 1 && raw.charAt(0) == '0')
        Left(s"revision '$raw' has a leading zero")
      else if (raw.length > 16)
        Left(s"revision '$raw' exceeds the safe range")
      else {
        val n = raw.toLong
        if (n < 1L || n > MaxSafeInteger) Left(s"revision '$raw' exceeds the safe range")
        else Right(n)
      }
    }

    /** Parse the canonical CLI form. Duplicate IDs, explicit zeroes, leading zeroes, overflow,
      * invalid IDs, whitespace, and noncanonical ordering are errors.
      */
    def parse(s: String): Either[SnapError, Version] = {
      if (s.exists(_.isWhitespace))
        Left(SnapError.InvalidVersion("must not contain whitespace"))
      else if (s == "()") Right(empty)
      else if (s.length < 2 || !s.startsWith("(") || !s.endsWith(")"))
        Left(SnapError.InvalidVersion("expected '(' and ')' around components"))
      else if (s.substring(1, s.length - 1).isEmpty)
        Left(SnapError.InvalidVersion("expected '()' for the empty version"))
      else parseParts(s.substring(1, s.length - 1).split(",", -1).toVector)
    }

    private def parseParts(
        parts: Vector[String]
    ): Either[SnapError, Version] = {
      val comps = Vector.newBuilder[(ContributorId, Long)]
      var prevId: Option[ContributorId] = None
      var i = 0
      var failure: Option[SnapError] = None
      while (i < parts.length && failure.isEmpty) {
        val part = parts(i)
        val arrow = part.indexOf("->")
        if (arrow < 0)
          failure = Some(SnapError.InvalidVersion(s"component '$part' is missing '->'"))
        else
          ContributorId.parse(part.substring(0, arrow)) match {
            case Left(err) =>
              failure = Some(SnapError.InvalidVersion(err.detail))
            case Right(id) =>
              parseRevision(part.substring(arrow + 2)) match {
                case Left(reason) =>
                  failure = Some(SnapError.InvalidVersion(reason))
                case Right(rev) =>
                  prevId.foreach { p =>
                    val cmp = utf8Compare(p.value, id.value)
                    if (cmp == 0)
                      failure = Some(SnapError.InvalidVersion(s"duplicate contributor ${id.value}"))
                    else if (cmp > 0)
                      failure = Some(
                        SnapError.InvalidVersion("contributors are not in canonical order")
                      )
                  }
                  prevId = Some(id)
                  comps += ((id, rev))
              }
          }
        i += 1
      }
      failure match {
        case Some(err) => Left(err)
        case None      => Right(Version(comps.result()))
      }
    }

    /** Parse the repository-JSON form: ordered [id, revision] pairs. Enforces valid IDs, positive
      * safe revisions, unique canonically-sorted components.
      */
    def fromPairs(pairs: Vector[(String, Long)], what: String): Either[SnapError, Version] = {
      val comps = Vector.newBuilder[(ContributorId, Long)]
      var prevId: Option[ContributorId] = None
      var i = 0
      var failure: Option[SnapError] = None
      while (i < pairs.length && failure.isEmpty) {
        val (rawId, rev) = pairs(i)
        ContributorId.parse(rawId) match {
          case Left(err) =>
            failure = Some(SnapError.RepositoryInvalid(s"$what has ${err.detail}"))
          case Right(id) =>
            if (rev < 1L || rev > MaxSafeInteger)
              failure = Some(
                SnapError.RepositoryInvalid(s"$what revision is not a positive safe integer")
              )
            else {
              prevId.foreach { p =>
                val cmp = utf8Compare(p.value, id.value)
                if (cmp == 0)
                  failure = Some(
                    SnapError.RepositoryInvalid(s"$what has duplicate contributor ${id.value}")
                  )
                else if (cmp > 0)
                  failure = Some(SnapError.RepositoryInvalid(s"$what is not in canonical order"))
              }
              prevId = Some(id)
              comps += ((id, rev))
            }
        }
        i += 1
      }
      failure match {
        case Some(err) => Left(err)
        case None      => Right(Version(comps.result()))
      }
    }

    /** Version equal to `base` with the component for `id` forced to `rev` (SPEC §4.2). */
    def withComponent(base: Version, id: ContributorId, rev: Long): Version = {
      val remaining = base.components.filterNot(_._1 == id)
      Version(
        (remaining :+ ((id, rev))).sortWith((a, b) => utf8Compare(a._1.value, b._1.value) < 0)
      )
    }

    private def unionIds(a: Version, b: Version): Vector[ContributorId] =
      (a.ids ++ b.ids).distinct.sortWith((x, y) => utf8Compare(x.value, y.value) < 0)

    /** SPEC §3.3 four-way causal comparison (absent component = zero). */
    def causalCompare(a: Version, b: Version): CausalOrder = {
      var less = false
      var greater = false
      val ids = unionIds(a, b)
      var i = 0
      while (i < ids.length && !((less && greater))) {
        val id = ids(i)
        val av = a.get(id)
        val bv = b.get(id)
        if (av < bv) less = true
        if (av > bv) greater = true
        i += 1
      }
      if (!less && !greater) CausalOrder.Equal
      else if (less && !greater) CausalOrder.Before
      else if (greater && !less) CausalOrder.After
      else CausalOrder.Concurrent
    }

    /** SPEC §3.3 componentwise join: max of every component. */
    def join(a: Version, b: Version): Version =
      Version(
        unionIds(a, b)
          .map(id => (id, math.max(a.get(id), b.get(id))))
          .filter(_._2 > 0L)
      )

    /** SPEC §3.4 Snap order: sorted union of IDs, first unequal counter decides. Extends causal
      * order; concurrent-version order has no chronological meaning.
      */
    def snapOrder(a: Version, b: Version): Int = {
      val ids = unionIds(a, b)
      var i = 0
      while (i < ids.length) {
        val id = ids(i)
        val av = a.get(id)
        val bv = b.get(id)
        if (av != bv) return java.lang.Long.compare(av, bv)
        i += 1
      }
      0
    }

    /** SPEC §4.1: `v` is known when every patch (c, n) with n <= v[c] exists. In a validated
      * repository (contiguous revisions) that is componentwise <= frontier.
      */
    def knownIn(v: Version, frontier: Version): Boolean =
      v.components.forall { case (id, rev) => rev <= frontier.get(id) }
  }

  // ---------------------------------------------------------------------------
  // Text tokens (SPEC §4.4)
  // ---------------------------------------------------------------------------

  /** Strict UTF-8 decode; None when the bytes are not valid UTF-8. */
  def decodeUtf8(bytes: Array[Byte]): Option[String] =
    try
      Some(
        StandardCharsets.UTF_8.newDecoder
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
    catch { case _: CharacterCodingException => None }

  /** A file is text when its bytes are valid UTF-8 and contain no NUL. */
  def isText(bytes: Array[Byte]): Boolean =
    !bytes.contains(0.toByte) && decodeUtf8(bytes).isDefined

  /** Split immediately after every LF byte, retaining LF in the token. */
  def tokenize(text: String): Vector[String] = {
    val out = Vector.newBuilder[String]
    val sb = new StringBuilder
    var i = 0
    while (i < text.length) {
      val c = text.charAt(i)
      sb += c
      if (c == '\n') {
        out += sb.result()
        sb.clear()
      }
      i += 1
    }
    if (sb.nonEmpty) out += sb.result()
    out.result()
  }

  def detokenize(tokens: Vector[String]): String = tokens.mkString

  /** Canonical token sequence: every token nonempty, no LF before a token's final byte, and every
    * token except possibly the final one ends in LF.
    */
  def isCanonicalTokenSeq(tokens: Vector[String]): Boolean =
    tokens.zipWithIndex.forall { case (t, i) =>
      if (t.isEmpty) false
      else {
        val lf = t.indexOf('\n')
        val noInteriorLf = lf < 0 || lf == t.length - 1
        if (i == tokens.length - 1) noInteriorLf
        else noInteriorLf && t.endsWith("\n")
      }
    }

  /** A single insert token must be nonempty with no LF before its final byte. */
  def isValidInsertToken(t: String): Boolean =
    t.nonEmpty && {
      val lf = t.indexOf('\n')
      lf < 0 || lf == t.length - 1
    }

  // ---------------------------------------------------------------------------
  // Edit scripts (SPEC §4.4)
  // ---------------------------------------------------------------------------

  sealed trait EditOp
  object EditOp {
    final case class Retain(n: Long) extends EditOp
    final case class Delete(n: Long) extends EditOp
    final case class Insert(tokens: Vector[String]) extends EditOp

    def tokensIn(op: EditOp): Long = op match {
      case Retain(n)      => n
      case Delete(n)      => n
      case Insert(tokens) => tokens.length.toLong
    }
  }

  /** Apply an edit script to base tokens. The script must consume the complete old sequence and
    * produce a canonical token sequence (SPEC §4.4).
    */
  def applyEdit(
      baseTokens: Vector[String],
      edit: Vector[EditOp]
  ): Either[SnapError, Vector[String]] = {
    @scala.annotation.tailrec
    def loop(
        ops: List[EditOp],
        i: Int,
        acc: Vector[String]
    ): Either[SnapError, Vector[String]] = ops match {
      case Nil =>
        if (i < baseTokens.length)
          Left(SnapError.RepositoryInvalid("edit script does not consume old content"))
        else if (!isCanonicalTokenSeq(acc))
          Left(
            SnapError.RepositoryInvalid("edit script result is not a canonical token sequence")
          )
        else Right(acc)
      case EditOp.Retain(n) :: rest =>
        if (i + n > baseTokens.length)
          Left(SnapError.RepositoryInvalid("edit script consumes beyond old content"))
        else loop(rest, i + n.toInt, acc ++ baseTokens.slice(i, i + n.toInt))
      case EditOp.Delete(n) :: rest =>
        if (i + n > baseTokens.length)
          Left(SnapError.RepositoryInvalid("edit script consumes beyond old content"))
        else loop(rest, i + n.toInt, acc)
      case EditOp.Insert(tokens) :: rest =>
        loop(rest, i, acc ++ tokens)
    }
    loop(edit.toList, 0, Vector.empty)
  }

  /** Adjacent operations of the same kind are forbidden (SPEC §4.4). */
  def hasAdjacentSameKind(edit: Vector[EditOp]): Option[String] = {
    val kinds = edit.map {
      case _: EditOp.Retain => "retain"
      case _: EditOp.Delete => "delete"
      case _: EditOp.Insert => "insert"
    }
    kinds
      .sliding(2)
      .collectFirst { case Vector(k1, k2) if k1 == k2 => k1 }
      .map(k => s"adjacent $k operations in edit script")
  }

  // ---------------------------------------------------------------------------
  // Tracked paths (SPEC §2)
  // ---------------------------------------------------------------------------

  def validatePath(p: String): Either[SnapError, Unit] = {
    if (p.isEmpty) Left(SnapError.RepositoryInvalid("path is invalid: empty path"))
    else if (p.indexOf('\\') >= 0) Left(SnapError.RepositoryInvalid(s"path is invalid: $p"))
    else if (p.exists(c => c < 0x20 || c == 0x7f))
      Left(SnapError.RepositoryInvalid(s"path is invalid: $p"))
    else {
      val segments = p.split("/", -1)
      if (segments.exists(_.isEmpty)) Left(SnapError.RepositoryInvalid(s"path is invalid: $p"))
      else if (segments.exists(s => s == "." || s == ".."))
        Left(SnapError.RepositoryInvalid(s"path is invalid: $p"))
      else if (segments.head == ".snap")
        Left(SnapError.RepositoryInvalid(s"path is invalid: $p"))
      else Right(())
    }
  }

  /** Segment-prefix test: true when `anc` is a proper ancestor directory of `desc`. */
  def isProperAncestor(anc: String, desc: String): Boolean =
    desc.startsWith(anc + "/")

  // ---------------------------------------------------------------------------
  // Messages (SPEC §4.2, §7.4, §7.5)
  // ---------------------------------------------------------------------------

  private def hasForbiddenMessageChar(m: String): Boolean =
    m.exists(c => (c < 0x20 && c != '\t' && c != '\n') || c == 0x7f)

  /** Repository-level message validation: nonempty, only tab/LF among ASCII controls. */
  def validateStoredMessage(m: String): Either[SnapError, Unit] = {
    if (m.isEmpty) Left(SnapError.RepositoryInvalid("patch message is empty"))
    else if (hasForbiddenMessageChar(m))
      Left(SnapError.RepositoryInvalid("patch message contains a forbidden control character"))
    else Right(())
  }

  /** `snap commit` message validation: nonempty, ≤ 4096 UTF-8 bytes, control rules. */
  def validateCommitMessage(m: String): Either[SnapError, Unit] = {
    if (m.isEmpty) Left(SnapError.InvalidCommitMessage)
    else if (utf8Bytes(m).length > MaxCommitMessageBytes) Left(SnapError.InvalidCommitMessage)
    else if (hasForbiddenMessageChar(m)) Left(SnapError.InvalidCommitMessage)
    else Right(())
  }

  /** SPEC §7.4 log escaping: backslash first, then tab, then LF. */
  def escapeLogMessage(m: String): String =
    m.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

  // ---------------------------------------------------------------------------
  // Base64 (SPEC §4.3: standard padded RFC 4648)
  // ---------------------------------------------------------------------------

  private val Base64Shape =
    "^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{4}|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{2}==)?$".r

  def decodeCanonicalBase64(s: String): Either[SnapError, Array[Byte]] = {
    if (Base64Shape.findFirstIn(s).isEmpty)
      Left(SnapError.RepositoryInvalid("content is not canonical base64"))
    else {
      val decoded =
        try Some(Base64.getDecoder.decode(s))
        catch { case _: IllegalArgumentException => None }
      decoded match {
        case None        => Left(SnapError.RepositoryInvalid("content is not canonical base64"))
        case Some(bytes) =>
          // Round-trip catches non-canonical trailing bits.
          if (Base64.getEncoder.encodeToString(bytes) != s)
            Left(SnapError.RepositoryInvalid("content is not canonical base64"))
          else Right(bytes)
      }
    }
  }

  def encodeBase64(bytes: Array[Byte]): String = Base64.getEncoder.encodeToString(bytes)

  // ---------------------------------------------------------------------------
  // Changes, patches, repositories
  // ---------------------------------------------------------------------------

  sealed trait Change {
    def path: String
  }
  object Change {
    final case class Text(path: String, edit: Vector[EditOp]) extends Change
    final case class Put(path: String, bytes: Array[Byte]) extends Change
    final case class Del(path: String) extends Change
  }

  final case class Patch(
      author: ContributorId,
      revision: Long,
      base: Version,
      message: String,
      changes: Vector[Change]
  ) {

    /** SPEC §4.2: result = base with result[author] = revision. */
    def result: Version = Version.withComponent(base, author, revision)
  }

  object Patch {

    /** Structural equality of parsed typed values (SPEC §4.2): JSON key order, whitespace, and
      * serialization details are irrelevant.
      */
    def sameValue(a: Patch, b: Patch): Boolean =
      a.author.value == b.author.value &&
        a.revision == b.revision &&
        versionSameValue(a.base, b.base) &&
        a.message == b.message &&
        a.changes.length == b.changes.length &&
        a.changes.zip(b.changes).forall { case (ca, cb) => changeSameValue(ca, cb) }

    private def versionSameValue(a: Version, b: Version): Boolean =
      a.components.length == b.components.length &&
        a.components.zip(b.components).forall { case ((ia, ra), (ib, rb)) =>
          ia.value == ib.value && ra == rb
        }

    private def changeSameValue(a: Change, b: Change): Boolean = (a, b) match {
      case (Change.Text(p1, e1), Change.Text(p2, e2)) =>
        p1 == p2 && editSameValue(e1, e2)
      case (Change.Put(p1, c1), Change.Put(p2, c2)) =>
        p1 == p2 && java.util.Arrays.equals(c1, c2)
      case (Change.Del(p1), Change.Del(p2)) => p1 == p2
      case _                                => false
    }

    private def editSameValue(a: Vector[EditOp], b: Vector[EditOp]): Boolean =
      a.length == b.length && a.zip(b).forall {
        case (EditOp.Retain(n1), EditOp.Retain(n2)) => n1 == n2
        case (EditOp.Delete(n1), EditOp.Delete(n2)) => n1 == n2
        case (EditOp.Insert(t1), EditOp.Insert(t2)) => t1 == t2
        case _                                      => false
      }
  }

  final case class Repository(frontier: Version, patches: Vector[Patch]) {
    def patchAt(id: ContributorId, revision: Long): Option[Patch] =
      patches.find(p => p.author == id && p.revision == revision)
  }
}
