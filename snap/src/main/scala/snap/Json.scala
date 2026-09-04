package snap

import zio.json.*
import zio.json.ast.{Json => ZJson}

import java.math.{BigDecimal => JBigDecimal}

/** Thin JSON codec layer for Snap built directly on zio-json (SPEC §4).
  *
  * Design (ARCHITECTURE.md revision 2026-09-05 #2 — user direction): there is no Snap-owned JSON
  * model. Input is decoded with zio-json into `zio.json.ast.Json` and converted straight into
  * [[Model]] types. Only the contract-forced behavior is hand-rolled:
  *
  *   - duplicate object keys are rejected with the offending key name (SPEC §4.1);
  *   - repository parsing rejects trailing bytes after the first value, config parsing tolerates
  *     them (CONTRACT §15 ruling A);
  *   - integer fields are validated as positive safe integers (SPEC §3.1/§4.4);
  *   - unknown object fields are rejected per level (SPEC §4.1);
  *   - `writeRepository`/`writeConfig` emit bytes identical to Node `JSON.stringify(v, null, 2)`
  *     plus a trailing LF (pinned by test 12).
  *
  * Structural schema checks live here; semantic history validation (closure, ordering, cycles,
  * change-vs-base replay) belongs to Codec/Replay (SPEC §4.5 steps 2–6).
  */
object Json {

  /** Minimal configuration document shape: `{"contributor":{"id":"<id>"}}` (SPEC §8). */
  final case class ConfigFile(id: Model.ContributorId)

  private type Fields = Vector[(String, ZJson)]

  private val RepoFields: Set[String] = Set("format", "frontier", "patches")
  private val PatchFields: Set[String] = Set("author", "revision", "base", "message", "changes")
  private val TextFields: Set[String] = Set("type", "path", "edit")
  private val PutFields: Set[String] = Set("type", "path", "content")
  private val DeleteFields: Set[String] = Set("type", "path")
  private val ConfigFields: Set[String] = Set("contributor")
  private val ContribFields: Set[String] = Set("id")

  // ---------------------------------------------------------------------------
  // Parsing entry points
  // ---------------------------------------------------------------------------

  /** Parse one complete repository document strictly (SPEC §4.1). Malformed JSON, duplicate keys,
    * unknown fields, trailing content, and invalid typed values are errors.
    */
  def parseRepository(input: String): Either[SnapError, Model.Repository] =
    input.fromJson[ZJson] match {
      case Left(err) => Left(SnapError.InvalidJson(err))
      case Right(ast) =>
        firstValueEnd(input) match {
          case Some(end) if isBlankAfter(input, end) => repositoryFromAst(ast)
          case Some(_) =>
            Left(SnapError.InvalidJson("trailing content after JSON value"))
          case None =>
            Left(SnapError.InvalidJson("unable to locate end of JSON value"))
        }
    }

  /** Parse a configuration document (SPEC §8, CONTRACT §15 ruling A). Same strictness as
    * [[parseRepository]] except bytes after the first complete JSON value are tolerated.
    */
  def parseConfig(input: String): Either[SnapError, ConfigFile] =
    input.fromJson[ZJson] match {
      case Left(err)  => Left(SnapError.InvalidJson(err))
      case Right(ast) => configFileFromAst(ast)
    }

  // ---------------------------------------------------------------------------
  // Writing entry points
  // ---------------------------------------------------------------------------

  /** Serialize a repository byte-identically to Node `JSON.stringify(value, null, 2)` + "\n", with
    * canonical field order (SPEC §4.1; test 12 pins the served snapshot bytes).
    */
  def writeRepository(repo: Model.Repository): String = {
    val sb = new StringBuilder(256)
    sb.append("{\n")
    pad(sb, 1); sb.append("\"format\": 1,\n")
    pad(sb, 1); sb.append("\"frontier\": "); writeVersion(sb, repo.frontier, 1); sb.append(",\n")
    pad(sb, 1); sb.append("\"patches\": "); writePatches(sb, repo.patches, 1); sb.append('\n')
    sb.append("}\n")
    sb.result()
  }

  /** Serialize config as `{"contributor":{"id":"<id>"}}` (two-space indent + trailing LF). */
  def writeConfig(cfg: ConfigFile): String = {
    val sb = new StringBuilder(64)
    sb.append("{\n")
    pad(sb, 1); sb.append("\"contributor\": {\n")
    pad(sb, 2); sb.append("\"id\": "); writeString(sb, cfg.id.value); sb.append('\n')
    pad(sb, 1); sb.append("}\n")
    sb.append("}\n")
    sb.result()
  }

  // ---------------------------------------------------------------------------
  // AST -> Model conversion (repository)
  // ---------------------------------------------------------------------------

  private def repositoryFromAst(ast: ZJson): Either[SnapError, Model.Repository] =
    ast match {
      case obj: ZJson.Obj =>
        for {
          fields <- objectFields(obj)
          _ <- findUnknown(fields, RepoFields) match {
            case Some(k) => Left(SnapError.UnknownRepoField(k))
            case None    => Right(())
          }
          _ <- requireFormat(fields)
          frontier <- requiredArray(fields, "frontier", "repository")
            .flatMap(versionFromPairs(_, "frontier"))
          patches <- requiredArray(fields, "patches", "repository").flatMap { arr =>
            seqEither(arr.elements.toVector.map(patchFromAst))
          }
        } yield Model.Repository(frontier, patches)
      case _ => Left(SnapError.InvalidJson("repository must be a JSON object"))
    }

  private def requireFormat(fields: Fields): Either[SnapError, Unit] =
    find(fields, "format") match {
      case None =>
        Left(SnapError.InvalidJson("repository: missing field 'format'"))
      case Some(ZJson.Num(n)) if n.compareTo(JBigDecimal.ONE) == 0 => Right(())
      case Some(_) =>
        Left(SnapError.InvalidJson("repository: format must be the integer 1"))
    }

  private def versionFromPairs(arr: ZJson.Arr, what: String): Either[SnapError, Model.Version] =
    seqEither(arr.elements.toVector.map(pairsFromAst(_, what))).flatMap { ps =>
      Model.Version.fromPairs(ps, what)
    }

  private def pairsFromAst(ast: ZJson, what: String): Either[SnapError, (String, Long)] =
    ast match {
      case ZJson.Arr(items) if items.length == 2 =>
        (items(0), items(1)) match {
          case (ZJson.Str(id), ZJson.Num(rev)) =>
            // Decode boundary: validated via the PositiveSafeInteger smart constructor, then
            // unwrapped to Long for the domain model's stable public signature.
            Model.PositiveSafeInteger.from(rev, s"$what revision").map(r => (id, r.toLong))
          case _ =>
            Left(
              SnapError.InvalidVersion(s"$what entries must be two-element [id, revision] pairs")
            )
        }
      case _ =>
        Left(SnapError.InvalidVersion(s"$what entries must be two-element [id, revision] pairs"))
    }

  private def patchFromAst(ast: ZJson): Either[SnapError, Model.Patch] =
    ast match {
      case obj: ZJson.Obj =>
        objectFields(obj).flatMap { fields =>
          findUnknown(fields, PatchFields) match {
            case Some(k) =>
              val authorCtx =
                find(fields, "author").collect { case ZJson.Str(s) => s }.getOrElse("?")
              val revCtx =
                find(fields, "revision").collect { case ZJson.Num(n) => n.longValue }.getOrElse(0L)
              Left(SnapError.UnknownPatchField(k, authorCtx, revCtx))
            case None =>
              for {
                authorStr <- requiredString(fields, "author", "patch")
                author <- Model.ContributorId.parse(authorStr)
                revBd <- requiredNumber(fields, "revision", "patch")
                // Decode boundary: smart-constructor validation, unwrapped to Long for Patch.
                revision <- Model.PositiveSafeInteger.from(revBd, "patch revision").map(_.toLong)
                baseArr <- requiredArray(fields, "base", "patch")
                base <- versionFromPairs(baseArr, "base")
                message <- requiredString(fields, "message", "patch")
                _ <- Model.validateStoredMessage(message)
                chArr <- requiredArray(fields, "changes", "patch")
                _ <-
                  if (chArr.elements.isEmpty) Left(SnapError.EmptyField("patch", "changes"))
                  else Right(())
                changes <- seqEither(
                  chArr.elements.toVector.map(changeFromAst(_, author, revision))
                )
              } yield Model.Patch(author, revision, base, message, changes)
          }
        }
      case _ => Left(SnapError.InvalidJson("patch must be a JSON object"))
    }

  private def changeFromAst(
      ast: ZJson,
      author: Model.ContributorId,
      revision: Long
  ): Either[SnapError, Model.Change] =
    ast match {
      case obj: ZJson.Obj =>
        objectFields(obj).flatMap { fields =>
          // Validate the discriminator first so the unknown-field check uses the right allowed set.
          requiredString(fields, "type", "change").flatMap {
            case typ @ ("text" | "put" | "delete") =>
              val allowed = typ match {
                case "text"   => TextFields
                case "put"    => PutFields
                case "delete" => DeleteFields
              }
              findUnknown(fields, allowed) match {
                case Some(k) =>
                  Left(SnapError.UnknownChangeField(k, author.value, revision))
                case None =>
                  typ match {
                    case "text" =>
                      for {
                        path <- requiredString(fields, "path", "change")
                        _ <- Model.validatePath(path)
                        edit <- requiredArray(fields, "edit", "change")
                        ops <- seqEither(edit.elements.toVector.map(editOpFromAst(_, path)))
                      } yield Model.Change.Text(path, ops)
                    case "put" =>
                      for {
                        path <- requiredString(fields, "path", "change")
                        _ <- Model.validatePath(path)
                        content <- requiredString(fields, "content", "change")
                        bytes <- Model.decodeCanonicalBase64(content, path)
                      } yield Model.Change.Put(path, bytes)
                    case "delete" =>
                      for {
                        path <- requiredString(fields, "path", "change")
                        _ <- Model.validatePath(path)
                      } yield Model.Change.Del(path)
                  }
              }
            case other =>
              Left(
                SnapError.InvalidJson(s"change type must be text, put, or delete (got '$other')")
              )
          }
        }
      case _ => Left(SnapError.InvalidJson("change must be a JSON object"))
    }

  private def editOpFromAst(ast: ZJson, path: String): Either[SnapError, Model.EditOp] =
    ast match {
      case obj: ZJson.Obj =>
        objectFields(obj).flatMap { fields =>
          if (fields.length != 1) Left(SnapError.EditOpWrongArity)
          else
            fields.head match {
              case ("retain", ZJson.Num(n)) =>
                // Decode boundary: smart-constructor validation, unwrapped to Long for EditOp.
                Model.PositiveSafeInteger
                  .from(n, "retain count")
                  .map(v => Model.EditOp.Retain(v.toLong))
              case ("delete", ZJson.Num(n)) =>
                Model.PositiveSafeInteger
                  .from(n, "delete count")
                  .map(v => Model.EditOp.Delete(v.toLong))
              case ("insert", ZJson.Arr(items)) =>
                if (items.isEmpty) Left(SnapError.EmptyField("edit", "insert"))
                else
                  seqEither(
                    items.toVector.map {
                      case ZJson.Str(s) => Right[SnapError, String](s)
                      case _ =>
                        Left[SnapError, String](
                          SnapError.InvalidJson("edit insert entries must be strings")
                        )
                    }
                  ).flatMap { tokens =>
                    if (tokens.forall(Model.isValidInsertToken))
                      Right(Model.EditOp.Insert(tokens))
                    else Left(SnapError.NonCanonicalTokens(path))
                  }
              case (_, _) => Left(SnapError.EditOpWrongArity)
            }
        }
      case _ => Left(SnapError.EditOpWrongArity)
    }

  // ---------------------------------------------------------------------------
  // AST -> Model conversion (config)
  // ---------------------------------------------------------------------------

  private def configFileFromAst(ast: ZJson): Either[SnapError, ConfigFile] =
    ast match {
      case obj: ZJson.Obj =>
        for {
          fields <- objectFields(obj)
          _ <- findUnknown(fields, ConfigFields) match {
            case Some(k) => Left(SnapError.InvalidJson(s"config: unknown field '$k'"))
            case None    => Right(())
          }
          contrib <- find(fields, "contributor") match {
            case Some(o: ZJson.Obj) => Right(o)
            case Some(_) =>
              Left(SnapError.InvalidJson("config: 'contributor' must be an object"))
            case None =>
              Left(SnapError.InvalidJson("config: missing field 'contributor'"))
          }
          cfields <- objectFields(contrib)
          _ <- findUnknown(cfields, ContribFields) match {
            case Some(k) =>
              Left(SnapError.InvalidJson(s"config contributor: unknown field '$k'"))
            case None => Right(())
          }
          idStr <- requiredString(cfields, "id", "config contributor")
          id <- Model.ContributorId.parse(idStr)
        } yield ConfigFile(id)
      case _ => Left(SnapError.InvalidJson("config must be a JSON object"))
    }

  // ---------------------------------------------------------------------------
  // Small shared AST helpers
  // ---------------------------------------------------------------------------

  /** Field list of an object, rejecting duplicate keys (SPEC §4.1 unique object keys). */
  private def objectFields(obj: ZJson.Obj): Either[SnapError, Fields] = {
    val seen = scala.collection.mutable.HashSet.empty[String]
    // foldLeft with a Left short-circuit guard replaces the early-exit iterator loop.
    obj.fields.foldLeft[Either[SnapError, Fields]](Right(Vector.empty)) {
      case (Left(err), _) => Left(err)
      case (Right(acc), (key, value)) =>
        if (!seen.add(key)) Left(SnapError.DuplicateJsonKey(key))
        else Right(acc :+ ((key, value)))
    }
  }

  private def find(fields: Fields, name: String): Option[ZJson] =
    fields.collectFirst { case (k, v) if k == name => v }

  private def findUnknown(fields: Fields, allowed: Set[String]): Option[String] =
    fields.collectFirst { case (k, _) if !allowed.contains(k) => k }

  private def requiredString(
      fields: Fields,
      name: String,
      what: String
  ): Either[SnapError, String] =
    find(fields, name) match {
      case Some(ZJson.Str(s)) => Right(s)
      case Some(_) => Left(SnapError.InvalidJson(s"$what: field '$name' must be a string"))
      case None    => Left(SnapError.InvalidJson(s"$what: missing field '$name'"))
    }

  private def requiredNumber(
      fields: Fields,
      name: String,
      what: String
  ): Either[SnapError, JBigDecimal] =
    find(fields, name) match {
      case Some(ZJson.Num(n)) => Right(n)
      case Some(_) => Left(SnapError.InvalidJson(s"$what: field '$name' must be a number"))
      case None    => Left(SnapError.InvalidJson(s"$what: missing field '$name'"))
    }

  private def requiredArray(
      fields: Fields,
      name: String,
      what: String
  ): Either[SnapError, ZJson.Arr] =
    find(fields, name) match {
      case Some(a: ZJson.Arr) => Right(a)
      case Some(_) => Left(SnapError.InvalidJson(s"$what: field '$name' must be an array"))
      case None    => Left(SnapError.InvalidJson(s"$what: missing field '$name'"))
    }

  private def seqEither[A](xs: Vector[Either[SnapError, A]]): Either[SnapError, Vector[A]] =
    // foldLeft with a Left short-circuit guard replaces the early-exit iterator loop.
    xs.foldLeft[Either[SnapError, Vector[A]]](Right(Vector.empty)) {
      case (Left(err), _)         => Left(err)
      case (Right(_), Left(err))  => Left(err)
      case (Right(acc), Right(a)) => Right(acc :+ a)
    }

  private def isBlankAfter(s: String, from: Int): Boolean =
    s.indexWhere(c => c != ' ' && c != '\t' && c != '\n' && c != '\r', from) < 0

  /** Index just past the first complete top-level JSON value. Assumes `input` already parsed; this
    * only locates the value boundary so strict mode can detect trailing bytes.
    *
    * KEPT as while loops (H2): this is a hot mutable-position character scanner — recursive or fold
    * rewrites would thread position state through every helper and hurt clarity/perf.
    */
  private def firstValueEnd(s: String): Option[Int] = {
    var pos = 0
    val n = s.length

    def isWs(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    def skipWs(): Unit =
      while (pos < n && isWs(s.charAt(pos))) pos += 1

    def scanString(): Boolean =
      if (pos >= n || s.charAt(pos) != '"') false
      else {
        pos += 1
        var closed = false
        var failed = false
        while (!closed && !failed && pos < n) {
          val c = s.charAt(pos)
          if (c == '"') {
            pos += 1
            closed = true
          } else if (c == '\\') {
            if (pos + 1 >= n) failed = true
            else if (s.charAt(pos + 1) == 'u') {
              if (pos + 6 > n) failed = true
              else pos += 6
            } else pos += 2
          } else pos += 1
        }
        closed
      }

    def scanExponent(): Boolean =
      if (pos < n && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
        pos += 1
        if (pos < n && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos += 1
        var digits = 0
        while (pos < n && s.charAt(pos).isDigit) {
          pos += 1
          digits += 1
        }
        digits > 0
      } else true

    def scanNumber(): Boolean = {
      if (pos < n && s.charAt(pos) == '-') pos += 1
      var digits = 0
      while (pos < n && s.charAt(pos).isDigit) {
        pos += 1
        digits += 1
      }
      if (digits == 0) false
      else if (pos < n && s.charAt(pos) == '.') {
        pos += 1
        var fractionDigits = 0
        while (pos < n && s.charAt(pos).isDigit) {
          pos += 1
          fractionDigits += 1
        }
        fractionDigits > 0 && scanExponent()
      } else scanExponent()
    }

    def scanLiteral(word: String): Boolean =
      if (pos + word.length <= n && s.regionMatches(pos, word, 0, word.length)) {
        pos += word.length
        true
      } else false

    def scanObject(): Boolean = {
      pos += 1
      skipWs()
      if (pos < n && s.charAt(pos) == '}') {
        pos += 1
        true
      } else {
        var ok = true
        var done = false
        while (ok && !done) {
          skipWs()
          if (!scanString()) ok = false
          else {
            skipWs()
            if (pos >= n || s.charAt(pos) != ':') ok = false
            else {
              pos += 1
              if (!scanValue()) ok = false
              else {
                skipWs()
                if (pos >= n) ok = false
                else
                  s.charAt(pos) match {
                    case ',' => pos += 1
                    case '}' =>
                      pos += 1
                      done = true
                    case _ => ok = false
                  }
              }
            }
          }
        }
        ok && done
      }
    }

    def scanArray(): Boolean = {
      pos += 1
      skipWs()
      if (pos < n && s.charAt(pos) == ']') {
        pos += 1
        true
      } else {
        var ok = true
        var done = false
        while (ok && !done) {
          if (!scanValue()) ok = false
          else {
            skipWs()
            if (pos >= n) ok = false
            else
              s.charAt(pos) match {
                case ',' => pos += 1
                case ']' =>
                  pos += 1
                  done = true
                case _ => ok = false
              }
          }
        }
        ok && done
      }
    }

    def scanValue(): Boolean = {
      skipWs()
      if (pos >= n) false
      else
        s.charAt(pos) match {
          case '{'                                     => scanObject()
          case '['                                     => scanArray()
          case '"'                                     => scanString()
          case 't'                                     => scanLiteral("true")
          case 'f'                                     => scanLiteral("false")
          case 'n'                                     => scanLiteral("null")
          case c if c == '-' || (c >= '0' && c <= '9') => scanNumber()
          case _                                       => false
        }
    }

    skipWs()
    if (scanValue()) Some(pos) else None
  }

  // ---------------------------------------------------------------------------
  // Canonical writer (Node JSON.stringify(value, null, 2) compatible)
  // ---------------------------------------------------------------------------

  private def pad(sb: StringBuilder, level: Int): Unit =
    sb.append("  " * level)

  private def writeVersion(sb: StringBuilder, v: Model.Version, level: Int): Unit = {
    val cs = v.components
    if (cs.isEmpty) { sb.append("[]"); return }
    sb.append("[\n")
    cs.zipWithIndex.foreach { case ((id, rev), i) =>
      pad(sb, level + 1)
      sb.append("[\n")
      pad(sb, level + 2)
      writeString(sb, id.value)
      sb.append(",\n")
      pad(sb, level + 2)
      sb.append(rev.toString)
      sb.append('\n')
      pad(sb, level + 1)
      sb.append(']')
      if (i < cs.length - 1) sb.append(',')
      sb.append('\n')
    }
    pad(sb, level)
    sb.append(']')
  }

  private def writePatches(sb: StringBuilder, patches: Vector[Model.Patch], level: Int): Unit = {
    if (patches.isEmpty) { sb.append("[]"); return }
    sb.append("[\n")
    patches.zipWithIndex.foreach { case (p, i) =>
      pad(sb, level + 1)
      writePatch(sb, p, level + 1)
      if (i < patches.length - 1) sb.append(',')
      sb.append('\n')
    }
    pad(sb, level)
    sb.append(']')
  }

  private def writePatch(sb: StringBuilder, p: Model.Patch, level: Int): Unit = {
    val k = level + 1
    sb.append("{\n")
    pad(sb, k); sb.append("\"author\": "); writeString(sb, p.author.value); sb.append(",\n")
    pad(sb, k); sb.append("\"revision\": "); sb.append(p.revision.toString); sb.append(",\n")
    pad(sb, k); sb.append("\"base\": "); writeVersion(sb, p.base, k); sb.append(",\n")
    pad(sb, k); sb.append("\"message\": "); writeString(sb, p.message); sb.append(",\n")
    pad(sb, k); sb.append("\"changes\": "); writeChanges(sb, p.changes, k); sb.append('\n')
    pad(sb, level)
    sb.append('}')
  }

  private def writeChanges(sb: StringBuilder, changes: Vector[Model.Change], level: Int): Unit = {
    if (changes.isEmpty) { sb.append("[]"); return }
    sb.append("[\n")
    changes.zipWithIndex.foreach { case (c, i) =>
      pad(sb, level + 1)
      writeChange(sb, c, level + 1)
      if (i < changes.length - 1) sb.append(',')
      sb.append('\n')
    }
    pad(sb, level)
    sb.append(']')
  }

  private def writeChange(sb: StringBuilder, c: Model.Change, level: Int): Unit = {
    val k = level + 1
    sb.append("{\n")
    c match {
      case Model.Change.Text(path, edit) =>
        pad(sb, k); sb.append("\"type\": \"text\",\n")
        pad(sb, k); sb.append("\"path\": "); writeString(sb, path); sb.append(",\n")
        pad(sb, k); sb.append("\"edit\": "); writeEditOps(sb, edit, k); sb.append('\n')
      case Model.Change.Put(path, bytes) =>
        pad(sb, k); sb.append("\"type\": \"put\",\n")
        pad(sb, k); sb.append("\"path\": "); writeString(sb, path); sb.append(",\n")
        pad(sb, k); sb.append("\"content\": "); writeString(sb, Model.encodeBase64(bytes));
        sb.append('\n')
      case Model.Change.Del(path) =>
        pad(sb, k); sb.append("\"type\": \"delete\",\n")
        pad(sb, k); sb.append("\"path\": "); writeString(sb, path); sb.append('\n')
    }
    pad(sb, level)
    sb.append('}')
  }

  private def writeEditOps(sb: StringBuilder, ops: Vector[Model.EditOp], level: Int): Unit = {
    if (ops.isEmpty) { sb.append("[]"); return }
    sb.append("[\n")
    ops.zipWithIndex.foreach { case (op, i) =>
      pad(sb, level + 1)
      writeEditOp(sb, op, level + 1)
      if (i < ops.length - 1) sb.append(',')
      sb.append('\n')
    }
    pad(sb, level)
    sb.append(']')
  }

  private def writeEditOp(sb: StringBuilder, op: Model.EditOp, level: Int): Unit = {
    val k = level + 1
    sb.append("{\n")
    op match {
      case Model.EditOp.Retain(n) =>
        pad(sb, k); sb.append("\"retain\": "); sb.append(n.toString); sb.append('\n')
      case Model.EditOp.Delete(n) =>
        pad(sb, k); sb.append("\"delete\": "); sb.append(n.toString); sb.append('\n')
      case Model.EditOp.Insert(tokens) =>
        pad(sb, k); sb.append("\"insert\": "); writeInsertTokens(sb, tokens, k); sb.append('\n')
    }
    pad(sb, level)
    sb.append('}')
  }

  private def writeInsertTokens(sb: StringBuilder, tokens: Vector[String], level: Int): Unit = {
    if (tokens.isEmpty) { sb.append("[]"); return }
    sb.append("[\n")
    tokens.zipWithIndex.foreach { case (t, i) =>
      pad(sb, level + 1)
      writeString(sb, t)
      if (i < tokens.length - 1) sb.append(',')
      sb.append('\n')
    }
    pad(sb, level)
    sb.append(']')
  }

  /** JSON string escaping compatible with `JSON.stringify`: named escapes for \b \f \n \r \t, `\"`
    * and `\\`, `\u00xx` (lowercase hex) for other control characters, and escaped lone surrogates.
    *
    * KEPT as a while loop (H2): surrogate-pair handling advances the index two chars at once, so a
    * foreach/zipWithIndex rewrite would need awkward skip state and lose clarity and speed.
    */
  private def writeString(sb: StringBuilder, s: String): Unit = {
    sb.append('"')
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case _ if c < 0x20 =>
          sb.append(f"\\u${c.toInt}%04x")
        case _ if Character.isHighSurrogate(c) =>
          if (i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1))) {
            sb.append(c)
            sb.append(s.charAt(i + 1))
            i += 1
          } else {
            sb.append(f"\\u${c.toInt}%04x")
          }
        case _ if Character.isLowSurrogate(c) =>
          sb.append(f"\\u${c.toInt}%04x")
        case _ => sb.append(c)
      }
      i += 1
    }
    sb.append('"')
  }
}
