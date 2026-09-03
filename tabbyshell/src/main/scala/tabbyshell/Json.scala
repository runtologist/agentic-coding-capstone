package tabbyshell

import scala.collection.mutable.ListBuffer

object Json {

  final case class JsonParseError(message: String) extends Exception(message)

  def parse(text: String): Either[String, Value] = {
    try Right(new JsonParser(text).parseDocument())
    catch {
      case e: JsonParseError => Left(e.message)
    }
  }

  def quote(s: String): String = {
    val sb = new StringBuilder("\"")
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\t' => sb.append("\\t")
        case '\r' => sb.append("\\r")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case _ =>
          if (c < ' ') sb.append(f"\\u${c.toInt}%04x")
          else sb.append(c)
      }
      i += 1
    }
    sb.append('"').toString
  }

  def pretty(value: Value): String = {
    val sb = new StringBuilder
    writeValue(value, 0, sb)
    sb.append('\n')
    sb.toString
  }

  private def writeValue(value: Value, indent: Int, sb: StringBuilder): Unit = {
    import Value.*
    value match {
      case VNull           => sb.append("null")
      case VBool(v)        => sb.append(v.toString)
      case VInt(v)         => sb.append(v.toString)
      case VFloat(v)       => sb.append(jsonDouble(v))
      case VStr(v)         => sb.append(quote(v))
      case VFilesize(v)    => sb.append(v.toString)
      case VDate(v)        => sb.append(v.toString)
      case VList(items)    => writeArray(items, indent, sb)
      case VRecord(fields) => writeObject(fields, indent, sb)
      case VTable(columns, rows) =>
        val records = rows.map { row =>
          VRecord(columns.zip(row))
        }
        writeArray(records, indent, sb)
    }
  }

  private def writeArray(items: List[Value], indent: Int, sb: StringBuilder): Unit = {
    if (items.isEmpty) {
      sb.append("[]")
    } else {
      sb.append("[\n")
      items.zipWithIndex.foreach { case (item, i) =>
        appendIndent(indent + 1, sb)
        writeValue(item, indent + 1, sb)
        if (i < items.length - 1) sb.append(',')
        sb.append('\n')
      }
      appendIndent(indent, sb)
      sb.append(']')
    }
  }

  private def writeObject(fields: List[(String, Value)], indent: Int, sb: StringBuilder): Unit = {
    if (fields.isEmpty) {
      sb.append("{}")
    } else {
      sb.append("{\n")
      fields.zipWithIndex.foreach { case ((key, value), i) =>
        appendIndent(indent + 1, sb)
        sb.append(quote(key))
        sb.append(": ")
        writeValue(value, indent + 1, sb)
        if (i < fields.length - 1) sb.append(',')
        sb.append('\n')
      }
      appendIndent(indent, sb)
      sb.append('}')
    }
  }

  private def appendIndent(indent: Int, sb: StringBuilder): Unit = {
    var i = 0
    while (i < indent) {
      sb.append("  ")
      i += 1
    }
  }

  private def jsonDouble(d: Double): String = {
    if (d.isNaN || d.isInfinite) "null"
    else if (d == Math.rint(d) && Math.abs(d) < 9007199254740992.0d) d.toLong.toString
    else d.toString
  }

  private final class JsonParser(text: String) {
    private var pos = 0

    def parseDocument(): Value = {
      skipWhitespace()
      val value = parseValue()
      skipWhitespace()
      if (pos != text.length) error("unexpected trailing characters")
      value
    }

    private def parseValue(): Value = {
      skipWhitespace()
      if (pos >= text.length) error("unexpected end of JSON")
      current match {
        case '{' => parseObject()
        case '[' => parseArray()
        case '"' => Value.VStr(parseString())
        case 't' =>
          expectLiteral("true")
          Value.VBool(true)
        case 'f' =>
          expectLiteral("false")
          Value.VBool(false)
        case 'n' =>
          expectLiteral("null")
          Value.VNull
        case c if c == '-' || c.isDigit => parseNumber()
        case c                          => error(s"unexpected character '$c'")
      }
    }

    private def parseObject(): Value = {
      import Value.*
      pos += 1
      skipWhitespace()
      if (peekChar().contains('}')) {
        pos += 1
        return VRecord(Nil)
      }
      val fields = ListBuffer.empty[(String, Value)]
      var done = false
      while (!done) {
        skipWhitespace()
        if (!peekChar().contains('"')) error("expected object key string")
        val key = parseString()
        skipWhitespace()
        expectChar(':')
        skipWhitespace()
        val value = parseValue()
        val existing = fields.indexWhere(_._1 == key)
        if (existing >= 0) fields.update(existing, (key, value))
        else fields += ((key, value))
        skipWhitespace()
        peekChar() match {
          case Some(',') =>
            pos += 1
          case Some('}') =>
            pos += 1
            done = true
          case _ =>
            error("expected ',' or '}' in object")
        }
      }
      VRecord(fields.toList)
    }

    private def parseArray(): Value = {
      pos += 1
      skipWhitespace()
      if (peekChar().contains(']')) {
        pos += 1
        return Value.VList(Nil)
      }
      val items = ListBuffer.empty[Value]
      var done = false
      while (!done) {
        items += parseValue()
        skipWhitespace()
        peekChar() match {
          case Some(',') =>
            pos += 1
          case Some(']') =>
            pos += 1
            done = true
          case _ =>
            error("expected ',' or ']' in array")
        }
      }
      arrayValue(items.toList)
    }

    private def arrayValue(items: List[Value]): Value = {
      val records = items.collect { case r: Value.VRecord => r }
      if (items.nonEmpty && records.length == items.length) {
        Value.tableFromUniformRecords(records).getOrElse(Value.VList(items))
      } else {
        Value.VList(items)
      }
    }

    private def parseString(): String = {
      expectChar('"')
      val sb = new StringBuilder
      var done = false
      while (!done) {
        if (pos >= text.length) error("unterminated string")
        val c = text.charAt(pos)
        if (c == '"') {
          pos += 1
          done = true
        } else if (c == '\\') {
          pos += 1
          if (pos >= text.length) error("unterminated escape")
          text.charAt(pos) match {
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case '/'  => sb.append('/')
            case 'b'  => sb.append('\b')
            case 'f'  => sb.append('\f')
            case 'n'  => sb.append('\n')
            case 'r'  => sb.append('\r')
            case 't'  => sb.append('\t')
            case 'u' =>
              if (pos + 4 >= text.length) error("invalid unicode escape")
              val hex = text.substring(pos + 1, pos + 5)
              try sb.append(Integer.parseInt(hex, 16).toChar)
              catch {
                case _: NumberFormatException => error(s"invalid unicode escape '$hex'")
              }
              pos += 4
            case other => error(s"invalid escape character '$other'")
          }
          pos += 1
        } else {
          sb.append(c)
          pos += 1
        }
      }
      sb.toString
    }

    private def parseNumber(): Value = {
      val start = pos
      if (peekChar().contains('-')) pos += 1
      consumeDigits()
      var isFloat = false
      if (peekChar().contains('.')) {
        isFloat = true
        pos += 1
        consumeDigits()
      }
      if (peekChar().exists(c => c == 'e' || c == 'E')) {
        isFloat = true
        pos += 1
        if (peekChar().exists(c => c == '+' || c == '-')) pos += 1
        consumeDigits()
      }
      val raw = text.substring(start, pos)
      if (!isFloat) {
        raw.toLongOption match {
          case Some(n) => return Value.VInt(n)
          case None    => ()
        }
      }
      raw.toDoubleOption match {
        case Some(d) => Value.VFloat(d)
        case None    => error(s"invalid number '$raw'")
      }
    }

    private def consumeDigits(): Unit = {
      val start = pos
      while (peekChar().exists(_.isDigit)) pos += 1
      if (pos == start) error("expected digit")
    }

    private def skipWhitespace(): Unit = {
      while (pos < text.length && isWhitespace(text.charAt(pos))) pos += 1
    }

    private def isWhitespace(c: Char): Boolean =
      c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private def current: Char = text.charAt(pos)

    private def peekChar(): Option[Char] =
      if (pos < text.length) Some(text.charAt(pos)) else None

    private def expectChar(c: Char): Unit = {
      if (!peekChar().contains(c)) error(s"expected '$c'")
      pos += 1
    }

    private def expectLiteral(literal: String): Unit = {
      if (!text.regionMatches(pos, literal, 0, literal.length)) {
        error(s"expected '$literal'")
      }
      pos += literal.length
    }

    private def error(message: String): Nothing =
      throw JsonParseError(s"$message at position $pos")
  }
}
