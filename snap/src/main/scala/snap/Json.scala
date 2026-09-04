package snap

/** Minimal strict JSON layer for Snap.
  *
  * Parsing:
  *   - full RFC 8259 grammar (objects, arrays, strings with \u escapes, numbers, literals)
  *   - duplicate object keys are rejected (SPEC §4.1 "Valid input has unique object keys")
  *   - trailing content after the top-level value is rejected in strict mode but tolerated in
  *     lenient mode (config files only — see docs/snap/CONTRACT.md §15 ruling A)
  *   - numbers keep their raw literal so integer-literal exactness can be enforced later
  *
  * Writing:
  *   - `renderPretty` reproduces Node's `JSON.stringify(value, null, 2)` plus a trailing LF, which
  *     the `--serve` snapshot test pins byte-for-byte.
  */
object Json {

  // ---------------------------------------------------------------------------
  // AST
  // ---------------------------------------------------------------------------

  sealed trait Value

  case object JNull extends Value
  final case class JBool(value: Boolean) extends Value

  /** A JSON number keeping its raw literal so callers can distinguish `1` from `1.5`/`1e2`. */
  final case class JNum(raw: String) extends Value {
    def isIntegralLiteral: Boolean = JNum.Integral.matches(raw)
    def asLong: Option[Long] =
      if (isIntegralLiteral) scala.util.Try(raw.toLong).toOption else None
  }
  object JNum {
    private val Integral = "^-?(?:0|[1-9][0-9]*)$".r
    def of(n: Long): JNum = JNum(n.toString)
  }

  final case class JStr(value: String) extends Value
  final case class JArr(items: Vector[Value]) extends Value
  final case class JObj(entries: Vector[(String, Value)]) extends Value {
    def get(key: String): Option[Value] = entries.find(_._1 == key).map(_._2)
    def keys: Vector[String] = entries.map(_._1)
  }

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  /** Parse one JSON document.
    *
    * @param source
    *   label used in error messages (e.g. file path or "response body")
    * @param allowTrailing
    *   when true, bytes after the first complete value are ignored
    */
  def parse(
      input: String,
      source: String,
      allowTrailing: Boolean = false
  ): Either[SnapError, Value] =
    new Parser(input, source, allowTrailing).run()

  private final class Malformed(val reason: String) extends RuntimeException

  private final class Parser(input: String, source: String, allowTrailing: Boolean) {
    private var pos = 0

    def run(): Either[SnapError, Value] =
      try {
        skipWs()
        val v = parseValue()
        skipWs()
        if (!allowTrailing && pos < input.length)
          throw new Malformed(s"trailing content at offset $pos")
        Right(v)
      } catch {
        case m: Malformed    => Left(SnapError.InvalidJson(s"$source: ${m.reason}"))
        case d: DuplicateKey => Left(SnapError.DuplicateJsonKey(d.key))
      }

    private def fail(reason: String): Nothing = throw new Malformed(reason)

    private def peek: Char =
      if (pos < input.length) input.charAt(pos) else fail("unexpected end of input")

    private def peekOpt: Option[Char] =
      if (pos < input.length) Some(input.charAt(pos)) else None

    private def advance(): Char = {
      val c = peek
      pos += 1
      c
    }

    private def skipWs(): Unit =
      while (pos < input.length) {
        val c = input.charAt(pos)
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos += 1
        else return
      }

    private def expect(c: Char): Unit =
      if (pos >= input.length || input.charAt(pos) != c)
        fail(s"expected '$c' at offset $pos")
      else pos += 1

    private def parseValue(): Value =
      peek match {
        case '{'                        => parseObject()
        case '['                        => parseArray()
        case '"'                        => JStr(parseString())
        case 't'                        => parseLiteral("true", JBool(true))
        case 'f'                        => parseLiteral("false", JBool(false))
        case 'n'                        => parseLiteral("null", JNull)
        case c if c == '-' || c.isDigit => parseNumber()
        case c                          => fail(s"unexpected character '$c' at offset $pos")
      }

    private def parseLiteral(text: String, value: Value): Value = {
      if (input.regionMatches(pos, text, 0, text.length)) {
        pos += text.length
        value
      } else fail(s"invalid literal at offset $pos")
    }

    private def parseObject(): Value = {
      expect('{')
      skipWs()
      val entries = Vector.newBuilder[(String, Value)]
      val seen = scala.collection.mutable.HashSet.empty[String]
      if (peekOpt.contains('}')) {
        pos += 1
        return JObj(entries.result())
      }
      var continue = true
      while (continue) {
        skipWs()
        if (!peekOpt.contains('"')) fail(s"expected object key at offset $pos")
        val key = parseString()
        if (!seen.add(key)) throw DuplicateKey(key)
        skipWs()
        expect(':')
        skipWs()
        val value = parseValue()
        entries += ((key, value))
        skipWs()
        peekOpt match {
          case Some(',') => pos += 1
          case Some('}') => pos += 1; continue = false
          case _         => fail(s"expected ',' or '}' at offset $pos")
        }
      }
      JObj(entries.result())
    }

    private final case class DuplicateKey(key: String) extends RuntimeException

    private def parseArray(): Value = {
      expect('[')
      skipWs()
      val items = Vector.newBuilder[Value]
      if (peekOpt.contains(']')) {
        pos += 1
        return JArr(items.result())
      }
      var continue = true
      while (continue) {
        skipWs()
        items += parseValue()
        skipWs()
        peekOpt match {
          case Some(',') => pos += 1
          case Some(']') => pos += 1; continue = false
          case _         => fail(s"expected ',' or ']' at offset $pos")
        }
      }
      JArr(items.result())
    }

    private def parseString(): String = {
      expect('"')
      val sb = new StringBuilder
      var done = false
      while (!done) {
        if (pos >= input.length) fail("unterminated string")
        val c = input.charAt(pos)
        pos += 1
        if (c == '"') done = true
        else if (c == '\\') {
          if (pos >= input.length) fail("unterminated escape")
          val esc = input.charAt(pos)
          pos += 1
          esc match {
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case '/'  => sb.append('/')
            case 'b'  => sb.append('\b')
            case 'f'  => sb.append('\f')
            case 'n'  => sb.append('\n')
            case 'r'  => sb.append('\r')
            case 't'  => sb.append('\t')
            case 'u' =>
              if (pos + 4 > input.length) fail("truncated \\u escape")
              val hex = input.substring(pos, pos + 4)
              if (
                !hex.forall(h =>
                  (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F')
                )
              )
                fail(s"invalid \\u escape at offset $pos")
              sb.append(Integer.parseInt(hex, 16).toChar)
              pos += 4
            case other => fail(s"invalid escape '\\$other' at offset ${pos - 1}")
          }
        } else if (c < 0x20) {
          fail(s"unescaped control character in string at offset ${pos - 1}")
        } else {
          sb.append(c)
        }
      }
      sb.result()
    }

    private def parseNumber(): Value = {
      val start = pos
      if (peekOpt.contains('-')) pos += 1
      // integer part: 0 or [1-9][0-9]*
      peekOpt match {
        case Some('0') =>
          pos += 1
          peekOpt match {
            case Some(d) if d.isDigit => fail(s"invalid number with leading zero at offset $start")
            case _                    => ()
          }
        case Some(d) if d.isDigit =>
          while (peekOpt.exists(_.isDigit)) pos += 1
        case _ => fail(s"invalid number at offset $start")
      }
      // fraction
      if (peekOpt.contains('.')) {
        pos += 1
        if (!peekOpt.exists(_.isDigit)) fail(s"invalid number fraction at offset $start")
        while (peekOpt.exists(_.isDigit)) pos += 1
      }
      // exponent
      if (peekOpt.exists(c => c == 'e' || c == 'E')) {
        pos += 1
        if (peekOpt.exists(c => c == '+' || c == '-')) pos += 1
        if (!peekOpt.exists(_.isDigit)) fail(s"invalid number exponent at offset $start")
        while (peekOpt.exists(_.isDigit)) pos += 1
      }
      JNum(input.substring(start, pos))
    }
  }

  // ---------------------------------------------------------------------------
  // Writing (Node JSON.stringify(value, null, 2) compatible)
  // ---------------------------------------------------------------------------

  /** Render with two-space indentation, Node-style, plus a trailing LF. */
  def renderPretty(v: Value): String = {
    val sb = new StringBuilder
    writeValue(sb, v, 0)
    sb.append('\n')
    sb.result()
  }

  private def pad(sb: StringBuilder, indent: Int): Unit = {
    var i = 0
    while (i < indent) { sb.append("  "); i += 1 }
  }

  private def writeValue(sb: StringBuilder, v: Value, indent: Int): Unit = v match {
    case JNull     => sb.append("null")
    case JBool(b)  => sb.append(b.toString)
    case JNum(raw) => sb.append(raw)
    case JStr(s)   => writeString(sb, s)
    case JArr(items) =>
      if (items.isEmpty) sb.append("[]")
      else {
        sb.append("[\n")
        var first = true
        for (item <- items) {
          if (!first) sb.append(",\n")
          first = false
          pad(sb, indent + 1)
          writeValue(sb, item, indent + 1)
        }
        sb.append('\n')
        pad(sb, indent)
        sb.append(']')
      }
    case JObj(entries) =>
      if (entries.isEmpty) sb.append("{}")
      else {
        sb.append("{\n")
        var first = true
        for ((k, value) <- entries) {
          if (!first) sb.append(",\n")
          first = false
          pad(sb, indent + 1)
          writeString(sb, k)
          sb.append(": ")
          writeValue(sb, value, indent + 1)
        }
        sb.append('\n')
        pad(sb, indent)
        sb.append('}')
      }
  }

  /** JSON string escaping compatible with JSON.stringify: named escapes for \b \f \n \r \t, \" and
    * \\, \u00xx for other control characters.
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
          sb.append("\\u")
          sb.append(f"${c.toInt}%04x")
        case _ => sb.append(c)
      }
      i += 1
    }
    sb.append('"')
  }
}
