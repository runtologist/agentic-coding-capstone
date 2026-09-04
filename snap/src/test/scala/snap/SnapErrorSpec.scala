package snap

import zio.test.*

/** Pins the exact `snap: <detail>` message strings that the public acceptance suite asserts
  * byte-for-byte (stderr_equals) or via substring/regex. These are frozen contract strings — see
  * docs/snap/CONTRACT.md §7.
  */
object SnapErrorSpec extends ZIOSpecDefault {

  def spec = suite("SnapError message contract")(
    test("CLI grammar errors") {
      assertTrue(
        SnapError.InvalidCommandOrArguments.detail == "invalid command or arguments",
        SnapError.DiffUsage("[<old> <new> [--repo <repository>]]").detail ==
          "usage: snap diff [<old> <new> [--repo <repository>]]",
        SnapError.InvalidPort("65536").detail == "invalid port: 65536",
        SnapError.InvalidPort("abc").detail == "invalid port: abc",
        SnapError.InvalidSnapColor.detail == "SNAP_COLOR must be auto, always, or never"
      )
    },
    test("repository discovery and init errors") {
      assertTrue(
        SnapError.NotASnapRepository.detail == "not a Snap repository",
        SnapError.RepositoryAlreadyExists.detail == "repository already exists",
        SnapError.CannotInitializeInsideRepository.detail == "cannot initialize inside repository"
      )
    },
    test("configuration errors") {
      assertTrue(
        SnapError.InvalidContributorId("must contain exactly one '@'").detail ==
          "invalid contributor id: must contain exactly one '@'",
        SnapError.InvalidJson("config: unexpected end of input").detail ==
          "invalid JSON: config: unexpected end of input",
        SnapError.DuplicateJsonKey("id").detail == "duplicate JSON key id",
        SnapError.ContributorIdRequired.detail ==
          "contributor.id is required; configure it locally or globally"
      )
    },
    test("version errors") {
      assertTrue(
        SnapError.InvalidVersion("revision '01' has a leading zero").detail ==
          "invalid version: revision '01' has a leading zero",
        SnapError.UnknownVersion("(a@x->2)").detail == "unknown version: (a@x->2)"
      )
    },
    test("working tree, commit and revert errors") {
      assertTrue(
        SnapError.WorkingTreeClean.detail == "working tree is clean",
        SnapError.WorkingTreeDirty.detail == "working tree is dirty",
        SnapError.UnsupportedEntry("link").detail == "unsupported working tree entry: link",
        SnapError.UnsupportedEntry("pipe").detail == "unsupported working tree entry: pipe",
        SnapError.InvalidCommitMessage.detail == "invalid commit message",
        SnapError.TargetTreeAlreadyCurrent.detail == "target tree is already current"
      )
    },
    test("repository validation errors carry harness-pinned substrings (tests 15/23/27)") {
      assertTrue(
        // test 23: ^snap: repository has unknown field: unknown\n$
        SnapError.UnknownRepoField("unknown").detail == "repository has unknown field: unknown",
        // test 23: ^snap: .+unknown field: extra\n$
        SnapError.UnknownPatchField("extra", "a@x", 1L).detail ==
          "patch a@x revision 1 has unknown field: extra",
        SnapError.UnknownChangeField("extra", "a@x", 1L).detail ==
          "change in patch a@x revision 1 has unknown field: extra",
        // test 15: contains "missing a@x"
        SnapError.MissingPatch("a@x", 1L).detail == "missing a@x revision 1",
        // test 23: ^snap: unreachable patch: .+\n$
        SnapError.UnreachablePatch("a@x", 1L).detail == "unreachable patch: a@x revision 1",
        // test 15: contains "cyclic or incomplete patch history"
        SnapError.CyclicOrIncompleteHistory.detail == "cyclic or incomplete patch history",
        // test 16: contains "patch collision: a@x revision 1"
        SnapError.PatchCollision("a@x", 1L).detail == "patch collision: a@x revision 1",
        // test 15: contains "path is invalid"
        SnapError.InvalidRepoPath(".snap/secret", "first segment is .snap").detail ==
          "path is invalid: .snap/secret",
        SnapError.InvalidRepoPath("", "empty path").detail == "path is invalid: empty path",
        // test 15: contains "canonical base64"
        SnapError.NonCanonicalBase64("").detail == "content is not canonical base64",
        SnapError.NonCanonicalBase64("f").detail == "content for f is not canonical base64",
        // tests 23: ^snap: .+positive safe integer\n$
        SnapError.NotPositiveSafeInteger("frontier revision").detail ==
          "frontier revision is not a positive safe integer",
        SnapError.NotPositiveSafeInteger("retain count").detail ==
          "retain count is not a positive safe integer",
        // test 23: ^snap: .+message is empty\n$
        SnapError.EmptyField("patch", "message").detail == "patch message is empty",
        // test 23: ^snap: .+changes is empty\n$
        SnapError.EmptyField("patch", "changes").detail == "patch changes is empty",
        // test 23: ^snap: .+insert is empty\n$
        SnapError.EmptyField("text edit", "insert").detail == "text edit insert is empty",
        // test 23: ^snap: .+must have one operation\n$
        SnapError.EditOpWrongArity.detail == "edit entry must have one operation",
        // test 15: contains "adjacent insert"
        SnapError
          .AdjacentSameKindOps("insert")
          .detail == "adjacent insert operations in edit script",
        SnapError
          .AdjacentSameKindOps("retain")
          .detail == "adjacent retain operations in edit script",
        // test 15: contains "does not consume old content"
        SnapError.EditNotConsuming("").detail == "edit script does not consume old content",
        SnapError.EditNotConsuming("f").detail == "edit for f does not consume old content",
        // test 23: ^snap: .+consumes beyond old content\n$
        SnapError.EditOverconsumes("").detail == "edit script consumes beyond old content",
        SnapError.EditOverconsumes("f").detail == "edit for f consumes beyond old content",
        // test 15: contains "tree paths conflict"
        SnapError.TreePathsConflict("a").detail == "tree paths conflict: a",
        // test 15: contains "no-op change"
        SnapError.NoOpChange("f").detail == "no-op change: f",
        // test 23: ^snap: delete of absent path: f\n$
        SnapError.DeleteOfAbsentPath("f").detail == "delete of absent path: f",
        SnapError.CreateOfPresentPath("f").detail == "create of present path: f",
        SnapError.TextOverBinaryBase("f").detail == "text change over binary base: f",
        SnapError.InvalidMessage("contains a forbidden control character").detail ==
          "patch message contains a forbidden control character",
        // test 23: ^snap: .*canonical.*\n$
        SnapError.NonCanonicalFrontier("(b@x->1,a@x->1)", "(a@x->1,b@x->1)").detail ==
          "frontier is not canonical: found (b@x->1,a@x->1), expected (a@x->1,b@x->1)",
        SnapError.NonCanonicalTokens("f").detail ==
          "edit result for f is not a canonical token sequence"
      )
    },
    test("HTTP, IO and internal errors") {
      assertTrue(
        SnapError.HttpStatus(302, "http://127.0.0.1:1/redirect").detail ==
          "HTTP 302 fetching http://127.0.0.1:1/redirect",
        SnapError.IoFailure("read failed: disk error").detail == "read failed: disk error",
        SnapError.InternalError.detail == "internal error"
      )
    }
  )
}
