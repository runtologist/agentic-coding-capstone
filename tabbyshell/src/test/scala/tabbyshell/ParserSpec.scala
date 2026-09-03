package tabbyshell

import zio.test.*

object ParserSpec extends ZIOSpecDefault {
  import Parser.*

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
      }
    )
  )
}
