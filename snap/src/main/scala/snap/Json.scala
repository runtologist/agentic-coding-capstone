package snap

import zio.Chunk
import zio.json.*
import zio.json.ast.{Json => ZJson}

import java.math.{BigDecimal => JBigDecimal}

/** JSON layer for Snap built on zio-json's AST.
  *
  * Parsing:
  *   - decoding is delegated to zio-json's `zio.json.ast.Json` decoder
  *   - duplicate object keys are rejected by walking the decoded AST (SPEC §4.1 "Valid input has
  *     unique object keys"); zio-json preserves duplicates in `Json.Obj.fields`
  *   - strict mode rejects trailing content after the top-level value; config mode tolerates it
  *     (docs/snap/CONTRACT.md §15 ruling A)
  *   - integer fields are validated as positive safe integers from the AST's `BigDecimal`
  *
  * Writing:
  *   - `writeCanonical` reproduces Node's `JSON.stringify(value, null, 2)` plus a trailing LF,
  *     which the `--serve` snapshot test pins byte-for-byte.
  */
object Json {

  /** Parsed JSON AST used by the codec layer. */
  type Value = ZJson

  // ---------------------------------------------------------------------------
  // Constructors used when writing canonical repository/config JSON
  // ---------------------------------------------------------------------------

  def obj(fields: (String, Value)*): Value = ZJson.Obj(Chunk.fromIterable(fields))

  def arr(items: Value*): Value = ZJson.Arr(Chunk.fromIterable(items))

  def str(s: String): Value = ZJson.Str(s)

  def num(n: Long): Value = ZJson.Num(JBigDecimal.valueOf(n))

  def bool(b: Boolean): Value = ZJson.Bool(b)

  val nul: Value = ZJson.Null

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  /** Parse one complete JSON document strictly: malformed input, duplicate keys, and trailing
    * content are errors.
    */
  def parseStrict(input: String, source: String): Either[SnapError, Value] =
    parseImpl(input, source, allowTrailing = false)

  /** Parse a configuration document: duplicate keys and malformed input are errors, but bytes after
    * the first complete JSON value are tolerated (CONTRACT §15 ruling A).
    */
  def parseConfig(input: String, source: String): Either[SnapError, Value] =
    parseImpl(input, source, allowTrailing = true)

  private def parseImpl(
      input: String,
      source: String,
      allowTrailing: Boolean
  ): Either[SnapError, Value] =
    input.fromJson[ZJson] match {
      case Left(err) => Left(SnapError.InvalidJson(s"$source: $err"))
      case Right(json) =>
        findDuplicateKey(json) match {
          case Some(name) => Left(SnapError.DuplicateJsonKey(name))
          case None =>
            if (allowTrailing) Right(json)
            else
              firstValueEnd(input) match {
                case Some(end)
                    if input
                      .substring(end)
                      .forall(c => c == ' ' || c == '\t' || c == '\n' || c == '\r') =>
                  Right(json)
                case Some(_) =>
                  Left(SnapError.InvalidJson(s"$source: trailing content after JSON value"))
                case None =>
                  Left(SnapError.InvalidJson(s"$source: unable to locate end of JSON value"))
              }
        }
    }

  /** First duplicated object key in document order, if any. zio-json's AST preserves duplicate
    * members, so uniqueness is enforced here.
    */
  private def findDuplicateKey(json: Value): Option[String] =
    json match {
      case ZJson.Obj(fields) =>
        val seen = scala.collection.mutable.HashSet.empty[String]
        val it = fields.iterator
        var result: Option[String] = None
        while (it.hasNext && result.isEmpty) {
          val (key, value) = it.next()
          if (!seen.add(key)) result = Some(key)
          else result = findDuplicateKey(value)
        }
        result
      case ZJson.Arr(items) =>
        val it = items.iterator
        var result: Option[String] = None
        while (it.hasNext && result.isEmpty) result = findDuplicateKey(it.next())
        result
      case _ => None
    }

  /** Index just past the first complete top-level JSON value. Assumes `input` has already parsed
    * successfully; this only locates the value boundary so strict mode can detect trailing bytes.
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
  // Typed extraction helpers for the codec layer
  // ---------------------------------------------------------------------------

  def asObject(json: Value, what: String): Either[SnapError, ZJson.Obj] =
    json match {
      case o: ZJson.Obj => Right(o)
      case _            => Left(SnapError.InvalidJson(s"$what: expected object"))
    }

  def asArray(json: Value, what: String): Either[SnapError, ZJson.Arr] =
    json match {
      case a: ZJson.Arr => Right(a)
      case _            => Left(SnapError.InvalidJson(s"$what: expected array"))
    }

  def asString(json: Value, what: String): Either[SnapError, String] =
    json match {
      case ZJson.Str(s) => Right(s)
      case _            => Left(SnapError.InvalidJson(s"$what: expected string"))
    }

  def asBoolean(json: Value, what: String): Either[SnapError, Boolean] =
    json match {
      case ZJson.Bool(b) => Right(b)
      case _             => Left(SnapError.InvalidJson(s"$what: expected boolean"))
    }

  def field(obj: ZJson.Obj, name: String, what: String): Either[SnapError, Value] =
    optionalField(obj, name) match {
      case Some(v) => Right(v)
      case None    => Left(SnapError.InvalidJson(s"$what: missing field '$name'"))
    }

  def optionalField(obj: ZJson.Obj, name: String): Option[Value] =
    obj.fields.collectFirst { case (k, v) if k == name => v }

  /** Distinct unknown keys in first-seen order. */
  def unknownFields(obj: ZJson.Obj, allowed: Set[String]): Vector[String] =
    obj.fields.map(_._1).filterNot(allowed.contains).toVector.distinct

  /** Extract a JSON number as a `BigDecimal` without applying positivity or range rules.
    *
    * Positive-safe-integer validation is a domain concern performed when constructing
    * revisions/counts (`Model.positiveSafeInteger`), not at the JSON boundary — the layer only
    * reports type mismatches.
    */
  def asNumber(json: Value, what: String): Either[SnapError, JBigDecimal] =
    json match {
      case ZJson.Num(n) => Right(n)
      case _            => Left(SnapError.InvalidJson(s"$what: expected number"))
    }

  // ---------------------------------------------------------------------------
  // Writing (Node JSON.stringify(value, null, 2) compatible)
  // ---------------------------------------------------------------------------

  /** Render with two-space indentation, Node-style, plus a trailing LF. */
  def writeCanonical(json: Value): String = {
    val sb = new StringBuilder
    writeValue(sb, json, 0)
    sb.append('\n')
    sb.result()
  }

  private def pad(sb: StringBuilder, indent: Int): Unit = {
    var i = 0
    while (i < indent) {
      sb.append("  ")
      i += 1
    }
  }

  private def writeValue(sb: StringBuilder, json: Value, indent: Int): Unit =
    json match {
      case ZJson.Null    => sb.append("null")
      case ZJson.Bool(b) => sb.append(if (b) "true" else "false")
      case ZJson.Num(n)  => writeNumber(sb, n)
      case ZJson.Str(s)  => writeString(sb, s)
      case ZJson.Arr(items) =>
        if (items.isEmpty) sb.append("[]")
        else {
          sb.append("[\n")
          var i = 0
          while (i < items.length) {
            pad(sb, indent + 1)
            writeValue(sb, items(i), indent + 1)
            if (i < items.length - 1) sb.append(',')
            sb.append('\n')
            i += 1
          }
          pad(sb, indent)
          sb.append(']')
        }
      case ZJson.Obj(fields) =>
        if (fields.isEmpty) sb.append("{}")
        else {
          sb.append("{\n")
          var i = 0
          while (i < fields.length) {
            val (key, value) = fields(i)
            pad(sb, indent + 1)
            writeString(sb, key)
            sb.append(": ")
            writeValue(sb, value, indent + 1)
            if (i < fields.length - 1) sb.append(',')
            sb.append('\n')
            i += 1
          }
          pad(sb, indent)
          sb.append('}')
        }
    }

  private def writeNumber(sb: StringBuilder, n: JBigDecimal): Unit =
    if (n.signum() == 0) sb.append("0")
    else {
      val stripped = n.stripTrailingZeros()
      if (stripped.scale() <= 0) sb.append(stripped.toBigIntegerExact.toString)
      else sb.append(stripped.toPlainString)
    }

  /** JSON string escaping compatible with JSON.stringify: named escapes for \b \f \n \r \t, \" and
    * \\, \u00xx for other control characters, and escaped lone surrogates.
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
