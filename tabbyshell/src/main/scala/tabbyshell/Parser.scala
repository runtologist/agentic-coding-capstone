package tabbyshell

import scala.collection.mutable.ListBuffer

object Parser {

  enum Literal {
    case LStr(value: String)
    case LInt(value: Long)
    case LFloat(value: Double)
    case LBool(value: Boolean)
    case LNull
    case LFilesize(bytes: Long)
  }

  enum Arg {
    case Flag(name: String, value: Option[Literal])
    case Lit(value: Literal)
    case Bare(value: String)
    case Op(value: String)
    case Dash
  }

  final case class Command(name: String, args: List[Arg])
  final case class Pipeline(commands: List[Command])

  private enum Token {
    case TWord(value: String, pos: Int)
    case TStr(value: String, pos: Int)
    case TInt(value: Long, pos: Int)
    case TFloat(value: Double, pos: Int)
    case TFilesize(bytes: Long, pos: Int)
    case TBool(value: Boolean, pos: Int)
    case TNull(pos: Int)
    case TLongFlag(name: String, value: Option[Literal], pos: Int)
    case TShortFlag(name: Char, pos: Int)
    case TOp(value: String, pos: Int)
    case TDash(pos: Int)
    case TPipe(pos: Int)
  }

  /** Resolves line continuations (spec §3.1 / §7.3).
    *
    * A physical line continues only when a backslash is the very last character (immediately before
    * the newline — no trailing-whitespace tolerance). The backslash and the physical newline are
    * dropped; a single '\n' is kept in the logical buffer so the tokenizer can treat it as
    * whitespace.
    *
    * Returns one element per logical pipeline.
    */
  def joinContinuations(script: String): List[String] = {
    val lines = script.split("\n", -1)
    val out = ListBuffer.empty[String]
    val buffer = new StringBuilder
    var i = 0
    while (i < lines.length) {
      val line = lines(i)
      if (line.endsWith("\\")) {
        buffer.append(line.dropRight(1)).append('\n')
      } else {
        buffer.append(line)
        out += buffer.toString
        buffer.clear()
      }
      i += 1
    }
    if (buffer.nonEmpty) out += buffer.toString
    out.toList
  }

  def parseLine(line: String): Either[TabbyError, Option[Pipeline]] = {
    new LineParser(line).parse()
  }

  private final class LineParser(line: String) {
    private var idx = 0

    def parse(): Either[TabbyError, Option[Pipeline]] = {
      tokenize().flatMap { tokens =>
        if (tokens.isEmpty) Right(None)
        else parseTokens(tokens)
      }
    }

    private def fail(detail: String, pos: Int): Either[TabbyError, Nothing] =
      Left(TabbyError.Parse(detail, pos + 1))

    private def currentChar: Char = line.charAt(idx)

    private def charAt(i: Int): Option[Char] =
      if (i < line.length) Some(line.charAt(i)) else None

    private def isWordStart(c: Char): Boolean =
      c.isLetter || c == '_' || c == '.' || c == '/' || c == '~'

    private def isWordPart(c: Char): Boolean =
      isWordStart(c) || c.isDigit || c == '-'

    private def tokenize(): Either[TabbyError, List[Token]] = {
      val tokens = ListBuffer.empty[Token]
      while (idx < line.length) {
        val c = currentChar
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          idx += 1
        } else if (c == '#') {
          while (idx < line.length && line.charAt(idx) != '\n') idx += 1
        } else if (c == '|') {
          tokens += Token.TPipe(idx)
          idx += 1
        } else if (c == '"' || c == '\'') {
          parseString() match {
            case Left(err)     => return Left(err)
            case Right((s, p)) => tokens += Token.TStr(s, p)
          }
        } else if (c == '-' && charAt(idx + 1).exists(_.isDigit)) {
          parseNumber() match {
            case Left(err)    => return Left(err)
            case Right(token) => tokens += token
          }
        } else if (c == '-' && charAt(idx + 1).contains('-')) {
          parseLongFlag() match {
            case Left(err)    => return Left(err)
            case Right(token) => tokens += token
          }
        } else if (c == '-' && charAt(idx + 1).exists(ch => ch.isLetter)) {
          val pos = idx
          idx += 1
          val flag = currentChar
          idx += 1
          tokens += Token.TShortFlag(flag, pos)
        } else if (c == '-') {
          val pos = idx
          idx += 1
          tokens += Token.TDash(pos)
        } else if (c.isDigit) {
          parseNumber() match {
            case Left(err)    => return Left(err)
            case Right(token) => tokens += token
          }
        } else if (c == '=' || c == '!' || c == '<' || c == '>') {
          parseOp() match {
            case Left(err)    => return Left(err)
            case Right(token) => tokens += token
          }
        } else if (isWordStart(c)) {
          val pos = idx
          val start = idx
          while (idx < line.length && isWordPart(currentChar)) idx += 1
          val word = line.substring(start, idx)
          word match {
            case "true"  => tokens += Token.TBool(true, pos)
            case "false" => tokens += Token.TBool(false, pos)
            case "null"  => tokens += Token.TNull(pos)
            case _       => tokens += Token.TWord(word, pos)
          }
        } else {
          return fail(s"unexpected character '$c'", idx)
        }
      }
      Right(tokens.toList)
    }

    private def parseString(): Either[TabbyError, (String, Int)] = {
      val start = idx
      val quote = currentChar
      idx += 1
      val sb = new StringBuilder
      while (idx < line.length && currentChar != quote) {
        val c = currentChar
        if (quote == '"' && c == '\\') {
          idx += 1
          if (idx >= line.length) return fail("unterminated escape in string", start)
          currentChar match {
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case 'n'  => sb.append('\n')
            case 't'  => sb.append('\t')
            case other =>
              return fail(s"invalid escape character '$other'", idx)
          }
          idx += 1
        } else {
          sb.append(c)
          idx += 1
        }
      }
      if (idx >= line.length) fail("unterminated string literal", start)
      else {
        idx += 1
        Right((sb.toString, start))
      }
    }

    private def parseNumber(): Either[TabbyError, Token] = {
      val start = idx
      if (currentChar == '-') idx += 1
      while (idx < line.length && currentChar.isDigit) idx += 1
      var isFloat = false
      if (idx < line.length && currentChar == '.' && charAt(idx + 1).exists(_.isDigit)) {
        isFloat = true
        idx += 1
        while (idx < line.length && currentChar.isDigit) idx += 1
      }

      if (idx < line.length && currentChar.isLetter) {
        val unitStart = idx
        while (idx < line.length && currentChar.isLetter) idx += 1
        val unit = line.substring(unitStart, idx).toLowerCase
        val multiplier = unit match {
          case "b"   => Some(1L)
          case "kb"  => Some(1000L)
          case "mb"  => Some(1000000L)
          case "gb"  => Some(1000000000L)
          case "kib" => Some(1024L)
          case "mib" => Some(1048576L)
          case "gib" => Some(1073741824L)
          case _     => None
        }
        multiplier match {
          case None => fail(s"unknown filesize unit '$unit'", start)
          case Some(mult) =>
            val text = line.substring(start, unitStart)
            val bytes: Long =
              if (isFloat) Math.round(text.toDouble * mult.toDouble)
              else {
                try text.toLong * mult
                catch {
                  case _: NumberFormatException =>
                    Math.round(text.toDouble * mult.toDouble)
                }
              }
            Right(Token.TFilesize(bytes, start))
        }
      } else {
        val text = line.substring(start, idx)
        if (isFloat) {
          text.toDoubleOption match {
            case Some(d) => Right(Token.TFloat(d, start))
            case None    => fail(s"invalid number '$text'", start)
          }
        } else {
          try Right(Token.TInt(text.toLong, start))
          catch {
            case _: NumberFormatException =>
              text.toDoubleOption match {
                case Some(d) => Right(Token.TFloat(d, start))
                case None    => fail(s"invalid number '$text'", start)
              }
          }
        }
      }
    }

    private def parseLongFlag(): Either[TabbyError, Token] = {
      val start = idx
      idx += 2
      val nameStart = idx
      while (
        idx < line.length && (currentChar.isLetterOrDigit || currentChar == '_' || currentChar == '-')
      ) idx += 1
      val name = line.substring(nameStart, idx)
      if (name.isEmpty) return fail("expected flag name after '--'", start)
      if (idx < line.length && currentChar == '=') {
        idx += 1
        parseLiteralValue().map { lit =>
          Token.TLongFlag(name, Some(lit), start)
        }
      } else {
        Right(Token.TLongFlag(name, None, start))
      }
    }

    private def parseLiteralValue(): Either[TabbyError, Literal] = {
      if (idx >= line.length) return fail("expected literal after '='", idx)
      val c = currentChar
      if (c == '"' || c == '\'') {
        parseString().map { case (s, _) => Literal.LStr(s) }
      } else if (c == '-' && charAt(idx + 1).exists(_.isDigit)) {
        parseNumber().map(numberToLiteral)
      } else if (c.isDigit) {
        parseNumber().map(numberToLiteral)
      } else if (isWordStart(c)) {
        val start = idx
        while (idx < line.length && isWordPart(currentChar)) idx += 1
        line.substring(start, idx) match {
          case "true"  => Right(Literal.LBool(true))
          case "false" => Right(Literal.LBool(false))
          case "null"  => Right(Literal.LNull)
          case other   => fail(s"invalid literal '$other'", start)
        }
      } else {
        fail("expected literal after '='", idx)
      }
    }

    private def numberToLiteral(token: Token): Literal = token match {
      case Token.TInt(v, _)      => Literal.LInt(v)
      case Token.TFloat(v, _)    => Literal.LFloat(v)
      case Token.TFilesize(v, _) => Literal.LFilesize(v)
      case _                     => Literal.LNull
    }

    private def parseOp(): Either[TabbyError, Token] = {
      val start = idx
      val c = currentChar
      c match {
        case '=' =>
          if (charAt(idx + 1).contains('=')) {
            idx += 2
            Right(Token.TOp("==", start))
          } else fail("expected '=' after '='", start)
        case '!' =>
          if (charAt(idx + 1).contains('=')) {
            idx += 2
            Right(Token.TOp("!=", start))
          } else fail("expected '=' after '!'", start)
        case '<' =>
          if (charAt(idx + 1).contains('=')) {
            idx += 2
            Right(Token.TOp("<=", start))
          } else {
            idx += 1
            Right(Token.TOp("<", start))
          }
        case '>' =>
          if (charAt(idx + 1).contains('=')) {
            idx += 2
            Right(Token.TOp(">=", start))
          } else {
            idx += 1
            Right(Token.TOp(">", start))
          }
        case _ => fail("invalid operator", start)
      }
    }

    private def parseTokens(tokens: List[Token]): Either[TabbyError, Option[Pipeline]] = {
      var pos = 0
      val commands = ListBuffer.empty[Command]

      def parseCommand(): Either[TabbyError, Command] = {
        if (pos >= tokens.length) {
          return fail("expected command", line.length)
        }
        val commandName: String = tokens(pos) match {
          case Token.TWord(name, _) =>
            pos += 1; name
          case Token.TBool(value, _) =>
            pos += 1; if (value) "true" else "false"
          case Token.TNull(_) =>
            pos += 1; "null"
          case Token.TPipe(p) =>
            return fail("expected command before '|'", p)
          case Token.TStr(_, p) =>
            return fail("expected command name", p)
          case Token.TInt(_, p) =>
            return fail("expected command name", p)
          case Token.TFloat(_, p) =>
            return fail("expected command name", p)
          case Token.TFilesize(_, p) =>
            return fail("expected command name", p)
          case Token.TLongFlag(_, _, p) =>
            return fail("expected command name", p)
          case Token.TShortFlag(_, p) =>
            return fail("expected command name", p)
          case Token.TOp(_, p) =>
            return fail("expected command name", p)
          case Token.TDash(p) =>
            return fail("expected command name", p)
        }
        val args = ListBuffer.empty[Arg]
        var done = false
        while (!done && pos < tokens.length) {
          tokens(pos) match {
            case Token.TPipe(_) =>
              done = true
            case Token.TWord(s, _) =>
              args += Arg.Bare(s); pos += 1
            case Token.TStr(s, _) =>
              args += Arg.Lit(Literal.LStr(s)); pos += 1
            case Token.TInt(v, _) =>
              args += Arg.Lit(Literal.LInt(v)); pos += 1
            case Token.TFloat(v, _) =>
              args += Arg.Lit(Literal.LFloat(v)); pos += 1
            case Token.TFilesize(v, _) =>
              args += Arg.Lit(Literal.LFilesize(v)); pos += 1
            case Token.TBool(v, _) =>
              args += Arg.Lit(Literal.LBool(v)); pos += 1
            case Token.TNull(_) =>
              args += Arg.Lit(Literal.LNull); pos += 1
            case Token.TLongFlag(name, value, _) =>
              args += Arg.Flag(name, value); pos += 1
            case Token.TShortFlag(name, _) =>
              args += Arg.Flag(name.toString, None); pos += 1
            case Token.TOp(op, _) =>
              args += Arg.Op(op); pos += 1
            case Token.TDash(_) =>
              args += Arg.Dash; pos += 1
          }
        }
        Right(Command(commandName, args.toList))
      }

      commands += (parseCommand() match {
        case Left(err)  => return Left(err)
        case Right(cmd) => cmd
      })

      while (pos < tokens.length) {
        tokens(pos) match {
          case Token.TPipe(p) =>
            pos += 1
            if (pos >= tokens.length) {
              return fail("expected command after '|'", line.length)
            }
            parseCommand() match {
              case Left(err)  => return Left(err)
              case Right(cmd) => commands += cmd
            }
          case other =>
            val p = other match {
              case Token.TWord(_, pos2)        => pos2
              case Token.TStr(_, pos2)         => pos2
              case Token.TInt(_, pos2)         => pos2
              case Token.TFloat(_, pos2)       => pos2
              case Token.TFilesize(_, pos2)    => pos2
              case Token.TBool(_, pos2)        => pos2
              case Token.TNull(pos2)           => pos2
              case Token.TLongFlag(_, _, pos2) => pos2
              case Token.TShortFlag(_, pos2)   => pos2
              case Token.TOp(_, pos2)          => pos2
              case Token.TDash(pos2)           => pos2
              case Token.TPipe(pos2)           => pos2
            }
            return fail("unexpected token", p)
        }
      }

      Right(Some(Pipeline(commands.toList)))
    }
  }
}
