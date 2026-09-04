package tabbyshell

import zio.test.*

object ParserSpec extends ZIOSpecDefault {
  import Parser.*

  private def parseErr(line: String): Either[TabbyError, Option[Pipeline]] =
    parseLine(line)

  private def expectParseError(line: String, detailContains: String): TestResult =
    parseLine(line) match {
      case Left(TabbyError.Parse(detail, _)) =>
        assertTrue(detail.contains(detailContains))
      case other =>
        assertTrue(false)
    }

  override def spec = suite("Parser")(
    suite("parseLine basics")(
      test("parses a bare command") {
        assertTrue(parseLine("ls") == Right(Some(Pipeline(List(Command("ls", Nil))))))
      },
      test("parses a command with a bare-ident argument") {
        val expected = Right(Some(Pipeline(List(Command("open", List(Arg.Bare("file.txt")))))))
        assertTrue(parseLine("open file.txt") == expected)
      },
      test("parses a pipe chain") {
        val expected = Right(Some(Pipeline(List(Command("ls", Nil), Command("length", Nil)))))
        assertTrue(parseLine("ls | length") == expected)
      },
      test("returns None for empty, whitespace-only and comment-only lines") {
        assertTrue(
          parseLine("") == Right(None),
          parseLine("   ") == Right(None),
          parseLine("# only a comment") == Right(None)
        )
      },
      test("a trailing comment is dropped") {
        val expected = Right(Some(Pipeline(List(Command("ls", Nil)))))
        assertTrue(parseLine("ls # trailing comment") == expected)
      },
      test("a comment stops at the newline inside a logical line") {
        val expected = Right(Some(Pipeline(List(Command("ls", Nil), Command("length", Nil)))))
        assertTrue(parseLine("ls # comment\n| length") == expected)
      }
    ),
    suite("literals and flags")(
      test(".5 is a bare ident, not a number") {
        val expected = Right(Some(Pipeline(List(Command("open", List(Arg.Bare(".5")))))))
        assertTrue(parseLine("open .5") == expected)
      },
      test("integers, floats and filesizes parse as literals") {
        assertTrue(
          parseLine("first 3") ==
            Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LInt(3L)))))))),
          parseLine("first -1") ==
            Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LInt(-1L)))))))),
          parseLine("first 1.5") ==
            Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFloat(1.5)))))))),
          parseLine("where size > 1kb") ==
            Right(
              Some(
                Pipeline(
                  List(
                    Command(
                      "where",
                      List(Arg.Bare("size"), Arg.Op(">"), Arg.Lit(Literal.LFilesize(1000L)))
                    )
                  )
                )
              )
            )
        )
      },
      test("flags parse with and without values") {
        assertTrue(
          parseLine("ls -a") ==
            Right(Some(Pipeline(List(Command("ls", List(Arg.Flag("a", None))))))),
          parseLine("sort-by name --reverse") ==
            Right(
              Some(
                Pipeline(
                  List(Command("sort-by", List(Arg.Bare("name"), Arg.Flag("reverse", None))))
                )
              )
            ),
          parseLine("first --count=3") ==
            Right(
              Some(
                Pipeline(
                  List(Command("first", List(Arg.Flag("count", Some(Literal.LInt(3L))))))
                )
              )
            ),
          parseLine("cd -") ==
            Right(Some(Pipeline(List(Command("cd", List(Arg.Dash))))))
        )
      }
    ),
    suite("command heads")(
      test("true, false and null are accepted as command heads") {
        assertTrue(
          parseLine("true") == Right(Some(Pipeline(List(Command("true", Nil))))),
          parseLine("false") == Right(Some(Pipeline(List(Command("false", Nil))))),
          parseLine("null") == Right(Some(Pipeline(List(Command("null", Nil))))),
          parseLine("true extra") ==
            Right(Some(Pipeline(List(Command("true", List(Arg.Bare("extra")))))))
        )
      },
      test("other literals are rejected as command heads") {
        assertTrue(
          parseLine("42") match {
            case Left(TabbyError.Parse(detail, _)) => detail.contains("expected command name")
            case _                                 => false
          },
          parseLine("\"ls\"") match {
            case Left(TabbyError.Parse(detail, _)) => detail.contains("expected command name")
            case _                                 => false
          }
        )
      },
      test("float as command head is rejected") {
        expectParseError("1.5", "expected command name")
      },
      test("filesize as command head is rejected") {
        expectParseError("1kb", "expected command name")
      },
      test("long flag as command head is rejected") {
        expectParseError("--flag", "expected command name")
      },
      test("short flag as command head is rejected") {
        expectParseError("-a", "expected command name")
      },
      test("operator as command head is rejected") {
        expectParseError("== x", "expected command name")
      },
      test("dash as command head is rejected") {
        expectParseError("-", "expected command name")
      },
      test("pipe placement errors are reported") {
        assertTrue(
          parseLine("ls |") match {
            case Left(TabbyError.Parse(detail, _)) => detail.contains("expected command after")
            case _                                 => false
          },
          parseLine("| ls") match {
            case Left(TabbyError.Parse(detail, _)) => detail.contains("expected command before")
            case _                                 => false
          }
        )
      }
    ),
    suite("string literals")(
      test("double-quoted string with escape sequences") {
        val expected =
          Right(Some(Pipeline(List(Command("open", List(Arg.Lit(Literal.LStr("a\"b\\c\nd\te"))))))))
        assertTrue(parseLine("open \"a\\\"b\\\\c\\nd\\te\"") == expected)
      },
      test("single-quoted string preserves backslashes literally") {
        val expected =
          Right(Some(Pipeline(List(Command("open", List(Arg.Lit(Literal.LStr("a\\nb"))))))))
        assertTrue(parseLine("open 'a\\nb'") == expected)
      },
      test("single-quoted string with special chars") {
        val expected =
          Right(Some(Pipeline(List(Command("open", List(Arg.Lit(Literal.LStr("hello world"))))))))
        assertTrue(parseLine("open 'hello world'") == expected)
      },
      test("string containing # is not treated as comment") {
        val expected =
          Right(Some(Pipeline(List(Command("open", List(Arg.Lit(Literal.LStr("a#b"))))))))
        assertTrue(parseLine("open \"a#b\"") == expected)
      },
      test("unterminated double-quoted string") {
        parseLine("open \"abc") match {
          case Left(TabbyError.Parse(detail, col)) =>
            assertTrue(detail == "unterminated string literal", col == 6)
          case _ => assertTrue(false)
        }
      },
      test("unterminated single-quoted string") {
        parseLine("open 'abc") match {
          case Left(TabbyError.Parse(detail, col)) =>
            assertTrue(detail == "unterminated string literal", col == 6)
          case _ => assertTrue(false)
        }
      },
      test("invalid escape character") {
        parseLine("open \"a\\qb\"") match {
          case Left(TabbyError.Parse(detail, col)) =>
            assertTrue(detail.contains("invalid escape character 'q'"), col == 9)
          case _ => assertTrue(false)
        }
      },
      test("unterminated escape at end of string") {
        parseLine("open \"abc\\") match {
          case Left(TabbyError.Parse(detail, col)) =>
            assertTrue(detail == "unterminated escape in string", col == 6)
          case _ => assertTrue(false)
        }
      },
      test("empty string literal") {
        val expected = Right(Some(Pipeline(List(Command("open", List(Arg.Lit(Literal.LStr(""))))))))
        assertTrue(parseLine("open \"\"") == expected)
      },
      test("empty single-quoted string") {
        val expected = Right(Some(Pipeline(List(Command("open", List(Arg.Lit(Literal.LStr(""))))))))
        assertTrue(parseLine("open ''") == expected)
      }
    ),
    suite("numbers and filesizes")(
      test("negative float") {
        val expected =
          Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFloat(-1.5))))))))
        assertTrue(parseLine("first -1.5") == expected)
      },
      test("integer overflow falls back to float") {
        val expected = Right(
          Some(
            Pipeline(List(Command("first", List(Arg.Lit(Literal.LFloat(9.223372036854776e18))))))
          )
        )
        assertTrue(parseLine("first 9223372036854775808") == expected)
      },
      test("very large number becomes float") {
        val expected =
          Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFloat(1.0e20))))))))
        assertTrue(parseLine("first 99999999999999999999") == expected)
      },
      test("filesize unit b") {
        val expected =
          Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFilesize(5L))))))))
        assertTrue(parseLine("first 5b") == expected)
      },
      test("filesize unit kb") {
        val expected =
          Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFilesize(2000L))))))))
        assertTrue(parseLine("first 2kb") == expected)
      },
      test("filesize unit mb") {
        val expected =
          Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFilesize(3000000L))))))))
        assertTrue(parseLine("first 3mb") == expected)
      },
      test("filesize unit gb") {
        val expected =
          Right(
            Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFilesize(4000000000L)))))))
          )
        assertTrue(parseLine("first 4gb") == expected)
      },
      test("filesize unit kib") {
        val expected =
          Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFilesize(1024L))))))))
        assertTrue(parseLine("first 1kib") == expected)
      },
      test("filesize unit mib") {
        val expected =
          Right(
            Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFilesize(1048576L)))))))
          )
        assertTrue(parseLine("first 1mib") == expected)
      },
      test("filesize unit gib") {
        val expected =
          Right(
            Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFilesize(1073741824L)))))))
          )
        assertTrue(parseLine("first 1gib") == expected)
      },
      test("filesize units are case-insensitive") {
        val expected =
          Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFilesize(1000L))))))))
        assertTrue(
          parseLine("first 1KB") == expected,
          parseLine("first 1Kb") == expected,
          parseLine("first 1kB") == expected
        )
      },
      test("fractional filesize rounds correctly") {
        val expected =
          Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFilesize(1500L))))))))
        assertTrue(parseLine("first 1.5kb") == expected)
      },
      test("negative filesize") {
        val expected =
          Right(Some(Pipeline(List(Command("first", List(Arg.Lit(Literal.LFilesize(-1000L))))))))
        assertTrue(parseLine("first -1kb") == expected)
      },
      test("unknown filesize unit produces error") {
        parseLine("first 1tb") match {
          case Left(TabbyError.Parse(detail, col)) =>
            assertTrue(detail == "unknown filesize unit 'tb'", col == 7)
          case _ => assertTrue(false)
        }
      },
      test("overflow filesize clamps to Long.MaxValue") {
        val expected = Right(
          Some(
            Pipeline(
              List(Command("first", List(Arg.Lit(Literal.LFilesize(Long.MaxValue)))))
            )
          )
        )
        assertTrue(parseLine("first 99999999999999999999b") == expected)
      },
      test("dot with no trailing digit is not a float suffix") {
        // "1." parses as int 1, then "." is a bare word
        val expected = Right(
          Some(
            Pipeline(
              List(Command("first", List(Arg.Lit(Literal.LInt(1L)), Arg.Bare("."))))
            )
          )
        )
        assertTrue(parseLine("first 1.") == expected)
      },
      test("dot followed by non-digit is not a float") {
        val expected = Right(
          Some(
            Pipeline(
              List(Command("first", List(Arg.Lit(Literal.LInt(1L)), Arg.Bare(".x"))))
            )
          )
        )
        assertTrue(parseLine("first 1.x") == expected)
      }
    ),
    suite("long flags")(
      test("long flag without value") {
        val expected =
          Right(Some(Pipeline(List(Command("ls", List(Arg.Flag("all", None)))))))
        assertTrue(parseLine("ls --all") == expected)
      },
      test("long flag with hyphenated name") {
        val expected =
          Right(Some(Pipeline(List(Command("sort-by", List(Arg.Flag("sort-by", None)))))))
        assertTrue(parseLine("sort-by --sort-by") == expected)
      },
      test("long flag with string value (double quotes)") {
        val expected = Right(
          Some(
            Pipeline(
              List(Command("cmd", List(Arg.Flag("name", Some(Literal.LStr("hello world"))))))
            )
          )
        )
        assertTrue(parseLine("cmd --name=\"hello world\"") == expected)
      },
      test("long flag with string value (single quotes)") {
        val expected = Right(
          Some(
            Pipeline(
              List(Command("cmd", List(Arg.Flag("name", Some(Literal.LStr("hello"))))))
            )
          )
        )
        assertTrue(parseLine("cmd --name='hello'") == expected)
      },
      test("long flag with int value") {
        val expected = Right(
          Some(Pipeline(List(Command("cmd", List(Arg.Flag("count", Some(Literal.LInt(42L))))))))
        )
        assertTrue(parseLine("cmd --count=42") == expected)
      },
      test("long flag with negative int value") {
        val expected = Right(
          Some(Pipeline(List(Command("cmd", List(Arg.Flag("offset", Some(Literal.LInt(-5L))))))))
        )
        assertTrue(parseLine("cmd --offset=-5") == expected)
      },
      test("long flag with float value") {
        val expected = Right(
          Some(Pipeline(List(Command("cmd", List(Arg.Flag("rate", Some(Literal.LFloat(1.5))))))))
        )
        assertTrue(parseLine("cmd --rate=1.5") == expected)
      },
      test("long flag with bool true value") {
        val expected = Right(
          Some(
            Pipeline(List(Command("cmd", List(Arg.Flag("verbose", Some(Literal.LBool(true)))))))
          )
        )
        assertTrue(parseLine("cmd --verbose=true") == expected)
      },
      test("long flag with bool false value") {
        val expected = Right(
          Some(
            Pipeline(List(Command("cmd", List(Arg.Flag("verbose", Some(Literal.LBool(false)))))))
          )
        )
        assertTrue(parseLine("cmd --verbose=false") == expected)
      },
      test("long flag with null value") {
        val expected = Right(
          Some(Pipeline(List(Command("cmd", List(Arg.Flag("val", Some(Literal.LNull)))))))
        )
        assertTrue(parseLine("cmd --val=null") == expected)
      },
      test("long flag with filesize value") {
        val expected = Right(
          Some(
            Pipeline(List(Command("cmd", List(Arg.Flag("size", Some(Literal.LFilesize(2000L)))))))
          )
        )
        assertTrue(parseLine("cmd --size=2kb") == expected)
      },
      test("long flag with empty value after = is an error") {
        parseLine("cmd --name=") match {
          case Left(TabbyError.Parse(detail, _)) =>
            assertTrue(detail == "expected literal after '='")
          case _ => assertTrue(false)
        }
      },
      test("long flag with invalid word literal after =") {
        parseLine("cmd --name=abc") match {
          case Left(TabbyError.Parse(detail, _)) =>
            assertTrue(detail == "invalid literal 'abc'")
          case _ => assertTrue(false)
        }
      },
      test("long flag with == after name is an error") {
        parseLine("cmd --name==") match {
          case Left(TabbyError.Parse(detail, _)) =>
            assertTrue(detail == "expected literal after '='")
          case _ => assertTrue(false)
        }
      },
      test("bare -- with no name is an error") {
        parseLine("cmd --") match {
          case Left(TabbyError.Parse(detail, _)) =>
            assertTrue(detail == "expected flag name after '--'")
          case _ => assertTrue(false)
        }
      }
    ),
    suite("short flags")(
      test("short flag is a single character") {
        val expected =
          Right(Some(Pipeline(List(Command("ls", List(Arg.Flag("a", None)))))))
        assertTrue(parseLine("ls -a") == expected)
      },
      test("short flag followed by digit token") {
        val expected = Right(
          Some(
            Pipeline(
              List(Command("ls", List(Arg.Flag("a", None), Arg.Lit(Literal.LInt(1L)))))
            )
          )
        )
        assertTrue(parseLine("ls -a1") == expected)
      },
      test("multiple short flags") {
        val expected = Right(
          Some(
            Pipeline(
              List(Command("ls", List(Arg.Flag("a", None), Arg.Flag("l", None))))
            )
          )
        )
        assertTrue(parseLine("ls -a -l") == expected)
      }
    ),
    suite("operators")(
      test("all comparison operators parse correctly") {
        val ops = List("==", "!=", "<", "<=", ">", ">=")
        val results = ops.map { op =>
          parseLine(s"where a $op 1") match {
            case Right(Some(Pipeline(List(Command("where", List(_, Arg.Op(o), _)))))) => o == op
            case _                                                                    => false
          }
        }
        assertTrue(results.forall(identity))
      },
      test("lone = is an error") {
        parseLine("where a = 1") match {
          case Left(TabbyError.Parse(detail, col)) =>
            assertTrue(detail == "expected '=' after '='", col == 9)
          case _ => assertTrue(false)
        }
      },
      test("lone ! is an error") {
        parseLine("where a ! 1") match {
          case Left(TabbyError.Parse(detail, col)) =>
            assertTrue(detail == "expected '=' after '!'", col == 9)
          case _ => assertTrue(false)
        }
      }
    ),
    suite("unexpected characters")(
      test("@ is unexpected") {
        parseLine("ls @") match {
          case Left(TabbyError.Parse(detail, col)) =>
            assertTrue(detail == "unexpected character '@'", col == 4)
          case _ => assertTrue(false)
        }
      },
      test("$ is unexpected") {
        parseLine("ls $x") match {
          case Left(TabbyError.Parse(detail, col)) =>
            assertTrue(detail == "unexpected character '$'", col == 4)
          case _ => assertTrue(false)
        }
      },
      test("( is unexpected") {
        parseLine("ls (x)") match {
          case Left(TabbyError.Parse(detail, col)) =>
            assertTrue(detail == "unexpected character '('", col == 4)
          case _ => assertTrue(false)
        }
      },
      test("% is unexpected") {
        parseLine("ls %") match {
          case Left(TabbyError.Parse(detail, col)) =>
            assertTrue(detail == "unexpected character '%'", col == 4)
          case _ => assertTrue(false)
        }
      }
    ),
    suite("whitespace handling")(
      test("tabs are treated as whitespace") {
        val expected =
          Right(Some(Pipeline(List(Command("ls", List(Arg.Flag("a", None)))))))
        assertTrue(parseLine("ls\t-a") == expected)
      },
      test("carriage return is treated as whitespace") {
        val expected =
          Right(Some(Pipeline(List(Command("ls", List(Arg.Flag("a", None)))))))
        assertTrue(parseLine("ls\r-a") == expected)
      },
      test("multiple spaces between tokens") {
        val expected =
          Right(Some(Pipeline(List(Command("ls", List(Arg.Bare("file")))))))
        assertTrue(parseLine("ls    file") == expected)
      }
    ),
    suite("miscellaneous")(
      test("multiple pipes produce multiple commands") {
        val expected = Right(
          Some(
            Pipeline(
              List(Command("a", Nil), Command("b", Nil), Command("c", Nil))
            )
          )
        )
        assertTrue(parseLine("a | b | c") == expected)
      },
      test("unicode identifier is a bare word") {
        val expected =
          Right(Some(Pipeline(List(Command("open", List(Arg.Bare("café")))))))
        assertTrue(parseLine("open café") == expected)
      },
      test("hash immediately after word starts a comment") {
        val expected = Right(Some(Pipeline(List(Command("ls", List(Arg.Bare("a")))))))
        assertTrue(parseLine("ls a#comment") == expected)
      },
      test("newline inside line acts as whitespace") {
        val expected = Right(
          Some(Pipeline(List(Command("ls", Nil), Command("length", Nil))))
        )
        assertTrue(parseLine("ls\n| length") == expected)
      }
    ),
    suite("joinContinuations")(
      test("joins continued lines, dropping the backslash and keeping a newline") {
        assertTrue(
          Parser.joinContinuations("ls \\\n| sort-by name") == List("ls \n| sort-by name"),
          Parser.joinContinuations("a\\\nb\\\nc") == List("a\nb\nc")
        )
      },
      test("does not join when the backslash is not immediately before the newline") {
        assertTrue(Parser.joinContinuations("ls \\ \n| length") == List("ls \\ ", "| length"))
      },
      test("keeps unrelated lines separate") {
        assertTrue(Parser.joinContinuations("a\nb") == List("a", "b"))
      },
      test("a trailing continuation at end of input is still flushed") {
        assertTrue(Parser.joinContinuations("ls \\") == List("ls \n"))
      },
      test("empty input yields a single empty logical line") {
        assertTrue(Parser.joinContinuations("") == List(""))
      },
      test("multiple continuations in sequence") {
        assertTrue(Parser.joinContinuations("a\\\nb\\\nc\\\nd") == List("a\nb\nc\nd"))
      },
      test("continuation with empty next line") {
        assertTrue(Parser.joinContinuations("a\\\n\\") == List("a\n\n"))
      }
    )
  )
}
