package snap

import zio.test.*

/** Pins the exact `snap: <detail>` message strings that the public acceptance suite asserts
  * byte-for-byte (stderr_equals) or via substring/regex. These are frozen contract strings — see
  * docs/snap/CONTRACT.md.
  */
object SnapErrorSpec extends ZIOSpecDefault {

  def spec = suite("SnapError message contract")(
    test("CLI grammar and discovery errors") {
      assertTrue(
        SnapError.InvalidCommandOrArguments.detail == "invalid command or arguments",
        SnapError.NotASnapRepository.detail == "not a Snap repository",
        SnapError.RepositoryAlreadyExists.detail == "repository already exists",
        SnapError.CannotInitializeInsideRepository.detail == "cannot initialize inside repository",
        SnapError.InvalidPort("65536").detail == "invalid port: 65536",
        SnapError
          .DiffUsage("[<old> <new> [--repo <repository>]]")
          .detail
          .startsWith("usage: snap diff ")
      )
    },
    test("working tree errors") {
      assertTrue(
        SnapError.WorkingTreeClean.detail == "working tree is clean",
        SnapError.WorkingTreeDirty.detail == "working tree is dirty",
        SnapError.UnsupportedEntry("link").detail == "unsupported working tree entry: link",
        SnapError.UnsupportedEntry("pipe").detail == "unsupported working tree entry: pipe"
      )
    },
    test("configuration and contributor errors") {
      assertTrue(
        SnapError.ContributorIdRequired.detail ==
          "contributor.id is required; configure it locally or globally",
        SnapError
          .InvalidContributorId("must contain exactly one '@'")
          .detail
          .startsWith("invalid contributor id: "),
        SnapError.DuplicateJsonKey("id").detail == "duplicate JSON key id",
        SnapError
          .InvalidJson("config: unexpected end of input")
          .detail
          .startsWith("invalid JSON: ")
      )
    },
    test("version errors") {
      assertTrue(
        SnapError.UnknownVersion("(a@x->2)").detail == "unknown version: (a@x->2)",
        SnapError
          .InvalidVersion("revision '01' has a leading zero")
          .detail
          .startsWith("invalid version: ")
      )
    },
    test("commit and revert errors") {
      assertTrue(
        SnapError.InvalidCommitMessage.detail == "invalid commit message",
        SnapError.TargetTreeAlreadyCurrent.detail == "target tree is already current"
      )
    },
    test("repository validation messages carry harness-pinned substrings") {
      assertTrue(
        SnapError
          .RepositoryInvalid("repository has unknown field: unknown")
          .detail
          .contains("repository has unknown field: unknown"),
        SnapError.RepositoryInvalid("missing a@x revision 1").detail.contains("missing a@x"),
        SnapError
          .RepositoryInvalid("cyclic or incomplete patch history")
          .detail
          .contains("cyclic or incomplete patch history"),
        SnapError
          .RepositoryInvalid("revision is not a positive safe integer")
          .detail
          .contains("positive safe integer"),
        SnapError
          .RepositoryInvalid("edit script does not consume old content")
          .detail
          .contains("does not consume old content"),
        SnapError
          .RepositoryInvalid("edit script consumes beyond old content")
          .detail
          .contains("consumes beyond old content"),
        SnapError.RepositoryInvalid("insert is empty").detail.contains("insert is empty"),
        SnapError
          .RepositoryInvalid("edit operation must have one operation")
          .detail
          .contains("must have one operation"),
        SnapError
          .RepositoryInvalid("content is not canonical base64")
          .detail
          .contains("canonical base64"),
        SnapError
          .RepositoryInvalid("tree paths conflict: a and a/b")
          .detail
          .contains("tree paths conflict"),
        SnapError
          .RepositoryInvalid("adjacent insert operations in edit script")
          .detail
          .contains("adjacent insert"),
        SnapError.RepositoryInvalid("no-op change at path f").detail.contains("no-op change"),
        SnapError.RepositoryInvalid("patch message is empty").detail.contains("message is empty"),
        SnapError.RepositoryInvalid("patch changes is empty").detail.contains("changes is empty"),
        SnapError.RepositoryInvalid("delete of absent path: f").detail == "delete of absent path: f"
      )
    },
    test("cross-repository collision and HTTP errors") {
      assertTrue(
        SnapError.PatchCollision("a@x", 1L).detail == "patch collision: a@x revision 1",
        SnapError.HttpStatus(302, "http://127.0.0.1:1/redirect").detail.contains("HTTP 302")
      )
    },
    test("color policy error is exact") {
      assertTrue(
        SnapError.InvalidColorValue("sometimes").detail ==
          "SNAP_COLOR must be auto, always, or never"
      )
    }
  )
}
