package snap

import zio.test.*

import snap.Cli.Command

/** Pins the CLI grammar matrix from harness tests 14 and 24 plus the documented option rules
  * (CONTRACT §1).
  */
object CliSpec extends ZIOSpecDefault {

  private def isInvalidCommand(e: Either[SnapError, Command]): Boolean =
    e == Left(SnapError.InvalidCommandOrArguments)

  private def isDiffUsage(e: Either[SnapError, Command]): Boolean =
    e.isLeft && e.left.toOption.exists(_.isInstanceOf[SnapError.DiffUsage])

  private def diffUsageText(e: Either[SnapError, Command]): String =
    e.left.toOption.map(_.detail).getOrElse("")

  def spec = suite("Cli.parse")(
    suite("--version")(
      test("parses bare --version") {
        assertTrue(Cli.parse(Vector("--version")) == Right(Command.ShowVersion))
      },
      test("rejects --version with extra args (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("--version", "extra"))))
      }
    ),
    suite("init")(
      test("parses init without path") {
        assertTrue(Cli.parse(Vector("init")) == Right(Command.Init(None)))
      },
      test("parses init with one path") {
        assertTrue(Cli.parse(Vector("init", "repo")) == Right(Command.Init(Some("repo"))))
      },
      test("rejects two path operands (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("init", "a", "b"))))
      },
      test("rejects unknown option (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("init", "--unknown"))))
      }
    ),
    suite("config")(
      test("parses local config") {
        assertTrue(
          Cli.parse(Vector("config", "contributor.id", "a@x")) ==
            Right(Command.Config(global = false, id = "a@x"))
        )
      },
      test("parses global config") {
        assertTrue(
          Cli.parse(Vector("config", "--global", "contributor.id", "a@x")) ==
            Right(Command.Config(global = true, id = "a@x"))
        )
      },
      test("rejects misplaced --global (test 24)") {
        assertTrue(
          isInvalidCommand(Cli.parse(Vector("config", "contributor.id", "a@x", "--global")))
        )
      },
      test("rejects duplicated --global (test 24)") {
        assertTrue(
          isInvalidCommand(
            Cli.parse(Vector("config", "--global", "--global", "contributor.id", "a@x"))
          )
        )
      },
      test("rejects missing id value (tests 14/24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("config", "contributor.id"))))
        assertTrue(isInvalidCommand(Cli.parse(Vector("config", "--global", "contributor.id"))))
      },
      test("rejects unknown option") {
        assertTrue(
          isInvalidCommand(Cli.parse(Vector("config", "--unknown", "contributor.id", "a@x")))
        )
      },
      test("rejects unknown first operand") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("config", "contributor.name", "a@x"))))
      },
      test("rejects extra trailing operand") {
        assertTrue(
          isInvalidCommand(Cli.parse(Vector("config", "contributor.id", "a@x", "extra")))
        )
      }
    ),
    suite("status / log")(
      test("parses bare status") {
        assertTrue(Cli.parse(Vector("status")) == Right(Command.Status))
      },
      test("rejects status with extra operand (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("status", "extra"))))
      },
      test("parses bare log") {
        assertTrue(Cli.parse(Vector("log")) == Right(Command.Log))
      },
      test("rejects log with unknown option (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("log", "--unknown"))))
      }
    ),
    suite("commit")(
      test("parses commit with message") {
        assertTrue(Cli.parse(Vector("commit", "hello")) == Right(Command.Commit("hello")))
      },
      test("keeps a message containing spaces as one operand") {
        assertTrue(
          Cli.parse(Vector("commit", "multi word message")) ==
            Right(Command.Commit("multi word message"))
        )
      },
      test("rejects missing message (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("commit"))))
      },
      test("rejects extra operand (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("commit", "message", "extra"))))
      },
      test("passes empty message through for domain validation (test 25)") {
        assertTrue(Cli.parse(Vector("commit", "")) == Right(Command.Commit("")))
      }
    ),
    suite("diff grammar")(
      test("parses bare diff") {
        assertTrue(
          Cli.parse(Vector("diff")) == Right(Command.Diff(None, None, None))
        )
      },
      test("parses two version operands") {
        assertTrue(
          Cli.parse(Vector("diff", "(a@x->1)", "(a@x->2)")) ==
            Right(Command.Diff(Some("(a@x->1)"), Some("(a@x->2)"), None))
        )
      },
      test("parses two operands with --repo") {
        assertTrue(
          Cli.parse(Vector("diff", "()", "()", "--repo", "../remote")) ==
            Right(Command.Diff(Some("()"), Some("()"), Some("../remote")))
        )
      },
      test("keeps version operands raw for the Commands layer") {
        assertTrue(
          Cli.parse(Vector("diff", "(bad version", "also bad)")) ==
            Right(Command.Diff(Some("(bad version"), Some("also bad)"), None))
        )
      },
      test("rejects exactly one operand with usage error (tests 14/24)") {
        val result = Cli.parse(Vector("diff", "()"))
        assertTrue(isDiffUsage(result)) &&
        assertTrue(diffUsageText(result).startsWith("usage: snap diff "))
      },
      test("rejects unknown option with usage error (tests 14/24)") {
        assertTrue(isDiffUsage(Cli.parse(Vector("diff", "()", "()", "--unknown", "repo"))))
      },
      test("rejects duplicated --repo with usage error (tests 14/24)") {
        assertTrue(
          isDiffUsage(Cli.parse(Vector("diff", "()", "()", "--repo", "repo", "--repo", "repo")))
        )
      },
      test("rejects --repo without value with usage error (test 14)") {
        assertTrue(isDiffUsage(Cli.parse(Vector("diff", "()", "()", "../repo", "--repo"))))
      },
      test("rejects three operands with usage error") {
        assertTrue(isDiffUsage(Cli.parse(Vector("diff", "a", "b", "c"))))
      },
      test("rejects --repo before version operands") {
        assertTrue(isDiffUsage(Cli.parse(Vector("diff", "--repo", "r", "()", "()"))))
      },
      test("rejects --repo alone with no operands") {
        assertTrue(isDiffUsage(Cli.parse(Vector("diff", "--repo", "r"))))
      }
    ),
    suite("revert")(
      test("parses revert with version operand") {
        assertTrue(Cli.parse(Vector("revert", "(a@x->1)")) == Right(Command.Revert("(a@x->1)")))
      },
      test("rejects missing version (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("revert"))))
      },
      test("rejects extra operand (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("revert", "()", "extra"))))
      }
    ),
    suite("merge")(
      test("parses merge with local operand") {
        assertTrue(Cli.parse(Vector("merge", "../remote")) == Right(Command.Merge("../remote")))
      },
      test("parses merge with http operand") {
        assertTrue(
          Cli.parse(Vector("merge", "http://127.0.0.1:1/repository.json")) ==
            Right(Command.Merge("http://127.0.0.1:1/repository.json"))
        )
      },
      test("rejects missing operand (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("merge"))))
      },
      test("rejects extra operand (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("merge", "repo", "extra"))))
      }
    ),
    suite("--serve")(
      test("parses --serve without port using the default") {
        val expected = Cli.parse(Vector("--serve"))
        assertTrue(
          expected.map {
            case Command.Serve(port) => port == Model.Port.default
            case _                   => false
          } == Right(true)
        )
      },
      test("parses --serve with explicit port 0 (OS-assigned)") {
        assertTrue(
          Cli.parse(Vector("--serve", "0")).map(_.isInstanceOf[Command.Serve]) == Right(true)
        )
      },
      test("parses --serve with port 65535") {
        assertTrue(
          Cli.parse(Vector("--serve", "65535")).map(_.isInstanceOf[Command.Serve]) == Right(true)
        )
      },
      test("rejects --serve 65536 with invalid port (test 14)") {
        val result = Cli.parse(Vector("--serve", "65536"))
        assertTrue(result == Left(SnapError.InvalidPort("65536"))) &&
        assertTrue(result.left.toOption.exists(_.detail == "invalid port: 65536"))
      },
      test("rejects --serve with extra args (test 24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("--serve", "0", "extra"))))
      },
      test("rejects non-numeric port") {
        assertTrue(Cli.parse(Vector("--serve", "abc")) == Left(SnapError.InvalidPort("abc")))
      },
      test("rejects negative port") {
        assertTrue(Cli.parse(Vector("--serve", "-1")) == Left(SnapError.InvalidPort("-1")))
      },
      test("rejects port with leading zeros") {
        assertTrue(Cli.parse(Vector("--serve", "007")) == Left(SnapError.InvalidPort("007")))
      },
      test("rejects fractional port") {
        assertTrue(Cli.parse(Vector("--serve", "1.5")) == Left(SnapError.InvalidPort("1.5")))
      }
    ),
    suite("general grammar")(
      test("rejects empty argument list") {
        assertTrue(isInvalidCommand(Cli.parse(Vector.empty)))
      },
      test("rejects unknown first token (tests 14/24)") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("unknown"))))
      },
      test("rejects unknown leading option") {
        assertTrue(isInvalidCommand(Cli.parse(Vector("--unknown"))))
      }
    )
  )
}
