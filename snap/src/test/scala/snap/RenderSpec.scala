package snap

import zio.test.{diff => _, *}

import snap.Model.*
import snap.Render.*

/** Pins plain-mode byte-exact output (CONTRACT §3, goldens from harness tests 04/05/06/07/26) and
  * terminal-mode ANSI output (CONTRACT §4, goldens from harness test 28), plus presentation
  * resolution (CONTRACT §5 / SPEC §11 TTY matrix).
  */
object RenderSpec extends ZIOSpecDefault {

  private val esc = "\u001b"

  private def v(pairs: (String, Long)*): Version =
    Version(pairs.map { case (id, rev) => (ContributorId(id), rev) }.toVector)

  def spec = suite("Render")(
    suite("resolvePresentation (CONTRACT §5, SPEC §11 TTY matrix)")(
      test("auto/unset: terminal on both streams when both are TTYs and NO_COLOR absent") {
        assertTrue(
          resolvePresentation(
            None,
            noColorPresent = false,
            stdoutIsTty = true,
            stderrIsTty = true
          ) ==
            Right((Presentation.Terminal, Presentation.Terminal)),
          resolvePresentation(
            Some("auto"),
            noColorPresent = false,
            stdoutIsTty = true,
            stderrIsTty = true
          ) ==
            Right((Presentation.Terminal, Presentation.Terminal))
        )
      },
      test("auto: each stream resolves independently (stdout TTY only)") {
        assertTrue(
          resolvePresentation(
            None,
            noColorPresent = false,
            stdoutIsTty = true,
            stderrIsTty = false
          ) ==
            Right((Presentation.Terminal, Presentation.Plain))
        )
      },
      test("auto: each stream resolves independently (stderr TTY only)") {
        assertTrue(
          resolvePresentation(
            None,
            noColorPresent = false,
            stdoutIsTty = false,
            stderrIsTty = true
          ) ==
            Right((Presentation.Plain, Presentation.Terminal))
        )
      },
      test("auto: neither TTY → plain on both") {
        assertTrue(
          resolvePresentation(
            None,
            noColorPresent = false,
            stdoutIsTty = false,
            stderrIsTty = false
          ) ==
            Right((Presentation.Plain, Presentation.Plain))
        )
      },
      test("NO_COLOR present (any value, including empty) forces plain in auto mode") {
        assertTrue(
          resolvePresentation(
            None,
            noColorPresent = true,
            stdoutIsTty = true,
            stderrIsTty = true
          ) ==
            Right((Presentation.Plain, Presentation.Plain)),
          resolvePresentation(
            Some("auto"),
            noColorPresent = true,
            stdoutIsTty = true,
            stderrIsTty = true
          ) ==
            Right((Presentation.Plain, Presentation.Plain))
        )
      },
      test("always → terminal on both streams even with NO_COLOR and non-TTY streams") {
        assertTrue(
          resolvePresentation(
            Some("always"),
            noColorPresent = true,
            stdoutIsTty = false,
            stderrIsTty = false
          ) ==
            Right((Presentation.Terminal, Presentation.Terminal))
        )
      },
      test("never → plain on both streams even on TTYs") {
        assertTrue(
          resolvePresentation(
            Some("never"),
            noColorPresent = false,
            stdoutIsTty = true,
            stderrIsTty = true
          ) ==
            Right((Presentation.Plain, Presentation.Plain))
        )
      },
      test("any other SNAP_COLOR value is a pre-execution error") {
        assertTrue(
          resolvePresentation(
            Some("sometimes"),
            noColorPresent = false,
            stdoutIsTty = true,
            stderrIsTty = true
          ) ==
            Left(SnapError.InvalidSnapColor)
        )
      }
    ),
    suite("success lines")(
      test("plain: init prints just the empty version (tests 01/02)") {
        assertTrue(
          successLine(SuccessLabel.InitializedRepository, "()", Presentation.Plain) == "()\n"
        )
      },
      test("plain: commit/revert/merge print the new version") {
        assertTrue(
          successLine(SuccessLabel.Committed, "(alice@example.com->1)", Presentation.Plain) ==
            "(alice@example.com->1)\n",
          successLine(SuccessLabel.Reverted, "(a@x->3)", Presentation.Plain) == "(a@x->3)\n",
          successLine(SuccessLabel.Merged, "(remote@x->1)", Presentation.Plain) == "(remote@x->1)\n"
        )
      },
      test("terminal: init golden (test 28)") {
        assertTrue(
          successLine(SuccessLabel.InitializedRepository, "()", Presentation.Terminal) ==
            s"$esc[32m\u2713$esc[0m $esc[1mInitialized repository$esc[0m $esc[36m()$esc[0m\n"
        )
      },
      test("terminal: commit golden (test 28)") {
        assertTrue(
          successLine(SuccessLabel.Committed, "(alice@x->1)", Presentation.Terminal) ==
            s"$esc[32m\u2713$esc[0m $esc[1mCommitted$esc[0m $esc[36m(alice@x->1)$esc[0m\n"
        )
      },
      test("terminal: revert golden (test 28)") {
        assertTrue(
          successLine(SuccessLabel.Reverted, "(alice@x->3)", Presentation.Terminal) ==
            s"$esc[32m\u2713$esc[0m $esc[1mReverted$esc[0m $esc[36m(alice@x->3)$esc[0m\n"
        )
      },
      test("terminal: merge golden (test 28)") {
        assertTrue(
          successLine(SuccessLabel.Merged, "(a@x->1,b@x->1)", Presentation.Terminal) ==
            s"$esc[32m\u2713$esc[0m $esc[1mMerged$esc[0m $esc[36m(a@x->1,b@x->1)$esc[0m\n"
        )
      }
    ),
    suite("version banner")(
      test("plain --version") {
        assertTrue(versionLine(Presentation.Plain) == "snap 1.0.0\n")
      },
      test("terminal --version golden (test 28)") {
        assertTrue(versionLine(Presentation.Terminal) == s"$esc[1msnap 1.0.0$esc[0m\n")
      }
    ),
    suite("status")(
      test("plain: clean repo prints only the version line (test 04)") {
        assertTrue(
          status(
            v("alice@example.com" -> 1L),
            Nil,
            Presentation.Plain
          ) == "version (alice@example.com->1)\n"
        )
      },
      test("plain: initial dirty rows sorted by path (test 04)") {
        val rows = Vector(("z.txt", StatusKind.Added), ("a.txt", StatusKind.Added))
        assertTrue(
          status(Version.empty, rows, Presentation.Plain) == "version ()\nA a.txt\nA z.txt\n"
        )
      },
      test("plain: mixed M/A/D rows sorted by path (test 04)") {
        val rows = Vector(
          ("z.txt", StatusKind.Deleted),
          ("m.txt", StatusKind.Added),
          ("a.txt", StatusKind.Modified)
        )
        assertTrue(
          status(v("alice@example.com" -> 1L), rows, Presentation.Plain) ==
            "version (alice@example.com->1)\nM a.txt\nA m.txt\nD z.txt\n"
        )
      },
      test("terminal: clean golden (test 28)") {
        assertTrue(
          status(v("alice@x" -> 1L), Nil, Presentation.Terminal) ==
            s"$esc[1mSnap status$esc[0m  $esc[36m(alice@x->1)$esc[0m\n\n  $esc[32m\u2713$esc[0m Working tree clean\n"
        )
      },
      test("terminal: all-added golden (test 28)") {
        val rows = Vector(
          ("added.txt", StatusKind.Added),
          ("gone.txt", StatusKind.Added),
          ("modified.txt", StatusKind.Added)
        )
        assertTrue(
          status(Version.empty, rows, Presentation.Terminal) ==
            s"$esc[1mSnap status$esc[0m  $esc[36m()$esc[0m\n\n" +
            s"  $esc[32m+$esc[0m added.txt $esc[2m(added)$esc[0m\n" +
            s"  $esc[32m+$esc[0m gone.txt $esc[2m(added)$esc[0m\n" +
            s"  $esc[32m+$esc[0m modified.txt $esc[2m(added)$esc[0m\n"
        )
      },
      test("terminal: modified/deleted/added golden with U+2212 minus (test 28)") {
        val rows = Vector(
          ("new.txt", StatusKind.Added),
          ("modified.txt", StatusKind.Modified),
          ("gone.txt", StatusKind.Deleted),
          ("added.txt", StatusKind.Modified)
        )
        assertTrue(
          status(v("alice@x" -> 1L), rows, Presentation.Terminal) ==
            s"$esc[1mSnap status$esc[0m  $esc[36m(alice@x->1)$esc[0m\n\n" +
            s"  $esc[33m~$esc[0m added.txt $esc[2m(modified)$esc[0m\n" +
            s"  $esc[31m\u2212$esc[0m gone.txt $esc[2m(deleted)$esc[0m\n" +
            s"  $esc[33m~$esc[0m modified.txt $esc[2m(modified)$esc[0m\n" +
            s"  $esc[32m+$esc[0m new.txt $esc[2m(added)$esc[0m\n"
        )
      },
      test("terminal: trailing space in path is preserved inside the row (test 28)") {
        val rows = Vector(("trailing ", StatusKind.Added))
        assertTrue(
          status(Version.empty, rows, Presentation.Terminal) ==
            s"$esc[1mSnap status$esc[0m  $esc[36m()$esc[0m\n\n  $esc[32m+$esc[0m trailing  $esc[2m(added)$esc[0m\n"
        )
      },
      test("plain: unicode paths sort by UTF-8 bytes (test 25 order nested/file < z < é < 😀)") {
        val rows = Vector(
          ("\ud83d\ude00", StatusKind.Added),
          ("z", StatusKind.Added),
          ("nested/file", StatusKind.Added),
          ("\u00e9", StatusKind.Added)
        )
        assertTrue(
          status(Version.empty, rows, Presentation.Plain) ==
            "version ()\nA nested/file\nA z\nA \u00e9\nA \ud83d\ude00\n"
        )
      }
    ),
    suite("log")(
      test("plain: reverse canonical order with escape order backslash, tab, LF (test 04)") {
        val entries = Vector(
          LogEntry(v("alice@example.com" -> 2L), "alice@example.com", "second"),
          LogEntry(v("alice@example.com" -> 1L), "alice@example.com", "first\tline\nsecond\\tail")
        )
        assertTrue(
          log(entries, Presentation.Plain) ==
            "(alice@example.com->2)\talice@example.com\tsecond\n(alice@example.com->1)\talice@example.com\tfirst\\tline\\nsecond\\\\tail\n"
        )
      },
      test("plain: revert history golden (test 07)") {
        val entries = Vector(
          LogEntry(v("a@x" -> 4L), "a@x", "revert to (a@x->2)"),
          LogEntry(v("a@x" -> 3L), "a@x", "revert to (a@x->1)"),
          LogEntry(v("a@x" -> 2L), "a@x", "directory"),
          LogEntry(v("a@x" -> 1L), "a@x", "file")
        )
        assertTrue(
          log(entries, Presentation.Plain) ==
            "(a@x->4)\ta@x\trevert to (a@x->2)\n(a@x->3)\ta@x\trevert to (a@x->1)\n(a@x->2)\ta@x\tdirectory\n(a@x->1)\ta@x\tfile\n"
        )
      },
      test("terminal: single entry golden (test 28)") {
        val entries = Vector(LogEntry(v("alice@x" -> 1L), "alice@x", "first"))
        assertTrue(
          log(entries, Presentation.Terminal) ==
            s"$esc[36m\u25cf$esc[0m $esc[1mfirst$esc[0m\n  $esc[36m(alice@x->1)$esc[0m $esc[2mby$esc[0m $esc[35malice@x$esc[0m\n"
        )
      },
      test("terminal: two entries joined with an extra blank line (test 28)") {
        val entries = Vector(
          LogEntry(v("alice@x" -> 2L), "alice@x", "second"),
          LogEntry(v("alice@x" -> 1L), "alice@x", "first")
        )
        assertTrue(
          log(entries, Presentation.Terminal) ==
            s"$esc[36m\u25cf$esc[0m $esc[1msecond$esc[0m\n  $esc[36m(alice@x->2)$esc[0m $esc[2mby$esc[0m $esc[35malice@x$esc[0m\n\n" +
            s"$esc[36m\u25cf$esc[0m $esc[1mfirst$esc[0m\n  $esc[36m(alice@x->1)$esc[0m $esc[2mby$esc[0m $esc[35malice@x$esc[0m\n"
        )
      },
      test("terminal: trailing-space message keeps the space inside the bold wrap (test 28)") {
        val entries = Vector(LogEntry(v("spaces@x" -> 1L), "spaces@x", "message "))
        assertTrue(
          log(entries, Presentation.Terminal) ==
            s"$esc[36m\u25cf$esc[0m $esc[1mmessage $esc[0m\n  $esc[36m(spaces@x->1)$esc[0m $esc[2mby$esc[0m $esc[35mspaces@x$esc[0m\n"
        )
      }
    ),
    suite("diff (plain, CONTRACT §10)")(
      test("no differences → empty output (test 05)") {
        assertTrue(diff(Nil, Presentation.Plain) == "")
      },
      test("repeated-line golden and missing final newline (test 05)") {
        val entries = Vector(
          DiffEntry.Text(
            path = "repeated.txt",
            oldPresent = true,
            newPresent = true,
            oldTokens = Vector("a\n", "b\n", "a\n"),
            edit = Vector(EditOp.Delete(1), EditOp.Retain(2), EditOp.Insert(Vector("a")))
          ),
          DiffEntry.Text(
            path = "added.txt",
            oldPresent = false,
            newPresent = true,
            oldTokens = Vector.empty,
            edit = Vector(EditOp.Insert(Vector("new")))
          )
        )
        val expected =
          "--- /dev/null\n+++ b/added.txt\n@@ -1,0 +1,1 @@\n+new\n\\ No newline at end of file\n" +
            "--- a/repeated.txt\n+++ b/repeated.txt\n@@ -1,3 +1,3 @@\n-a\n b\n a\n+a\n\\ No newline at end of file\n"
        assertTrue(diff(entries, Presentation.Plain) == expected)
      },
      test("binary create/delete and empty-file create (test 06)") {
        val createEntries = Vector(
          DiffEntry
            .Text("empty", oldPresent = false, newPresent = true, Vector.empty, Vector.empty),
          DiffEntry.Binary("data.bin", oldPresent = false, newPresent = true)
        )
        assertTrue(
          diff(createEntries, Presentation.Plain) ==
            "Binary files /dev/null and b/data.bin differ\n--- /dev/null\n+++ b/empty\n@@ -1,0 +1,0 @@\n"
        ) &&
        assertTrue(
          diff(
            Vector(DiffEntry.Binary("data.bin", oldPresent = true, newPresent = false)),
            Presentation.Plain
          ) ==
            "Binary files a/data.bin and /dev/null differ\n"
        )
      },
      test("CRLF, NUL-binary and unicode bytes are preserved (test 26)") {
        val entries = Vector(
          DiffEntry.Text(
            path = "unicode.txt",
            oldPresent = false,
            newPresent = true,
            oldTokens = Vector.empty,
            edit = Vector(EditOp.Insert(Vector("h\u00e9\n")))
          ),
          DiffEntry.Binary("nul.bin", oldPresent = false, newPresent = true),
          DiffEntry.Text(
            path = "crlf.txt",
            oldPresent = false,
            newPresent = true,
            oldTokens = Vector.empty,
            edit = Vector(EditOp.Insert(Vector("a\r\n", "b")))
          )
        )
        val expected =
          "--- /dev/null\n+++ b/crlf.txt\n@@ -1,0 +1,2 @@\n+a\r\n+b\n\\ No newline at end of file\n" +
            "Binary files /dev/null and b/nul.bin differ\n" +
            "--- /dev/null\n+++ b/unicode.txt\n@@ -1,0 +1,1 @@\n+h\u00e9\n"
        assertTrue(diff(entries, Presentation.Plain) == expected)
      },
      test("version-to-version diff goldens (test 21)") {
        val forward = Vector(
          DiffEntry.Text(
            path = "story.txt",
            oldPresent = true,
            newPresent = true,
            oldTokens = Vector("base\n"),
            edit = Vector(EditOp.Retain(1), EditOp.Insert(Vector("B1\n", "B2\n", "A2\n")))
          )
        )
        assertTrue(
          diff(forward, Presentation.Plain) ==
            "--- a/story.txt\n+++ b/story.txt\n@@ -1,1 +1,4 @@\n base\n+B1\n+B2\n+A2\n"
        ) &&
        assertTrue(
          diff(
            Vector(
              DiffEntry.Text(
                path = "story.txt",
                oldPresent = true,
                newPresent = true,
                oldTokens = Vector("base\n", "B1\n", "B2\n", "A2\n"),
                edit = Vector(EditOp.Retain(3), EditOp.Delete(1))
              )
            ),
            Presentation.Plain
          ) ==
            "--- a/story.txt\n+++ b/story.txt\n@@ -1,4 +1,3 @@\n base\n B1\n B2\n-A2\n"
        )
      }
    ),
    suite("diff (terminal, test 28 goldens)")(
      test("text diff coloring preserves plain bytes and wraps by prefix precedence") {
        val entries = Vector(
          DiffEntry.Text(
            path = "added.txt",
            oldPresent = true,
            newPresent = true,
            oldTokens = Vector("context\n", "old\n"),
            edit = Vector(EditOp.Retain(1), EditOp.Delete(1), EditOp.Insert(Vector("new\n")))
          )
        )
        val expected =
          s"$esc[1m--- a/added.txt$esc[0m\n$esc[1m+++ b/added.txt$esc[0m\n" +
            s"$esc[36m@@ -1,2 +1,2 @@$esc[0m\n context\n$esc[31m-old$esc[0m\n$esc[32m+new$esc[0m\n"
        assertTrue(diff(entries, Presentation.Terminal) == expected)
      },
      test("binary line and no-newline marker coloring") {
        val entries = Vector(
          DiffEntry.Text(
            path = "no-newline.txt",
            oldPresent = false,
            newPresent = true,
            oldTokens = Vector.empty,
            edit = Vector(EditOp.Insert(Vector("tail")))
          ),
          DiffEntry.Binary("binary.bin", oldPresent = false, newPresent = true)
        )
        val expected =
          s"$esc[33mBinary files /dev/null and b/binary.bin differ$esc[0m\n" +
            s"$esc[1m--- /dev/null$esc[0m\n$esc[1m+++ b/no-newline.txt$esc[0m\n" +
            s"$esc[36m@@ -1,0 +1,1 @@$esc[0m\n$esc[32m+tail$esc[0m\n$esc[2m\\ No newline at end of file$esc[0m\n"
        assertTrue(diff(entries, Presentation.Terminal) == expected)
      }
    ),
    suite("warnings and errors")(
      test("plain warning line (tests 09-11, 17)") {
        assertTrue(
          warningLine(ReplayWarning.LaterCreateWins("same"), Presentation.Plain) ==
            "warning: auto-resolved same: later-create-wins\n"
        )
      },
      test("plain warning renders all five reasons") {
        assertTrue(
          warningLine(ReplayWarning.DeleteWins("delete.txt"), Presentation.Plain) ==
            "warning: auto-resolved delete.txt: delete-wins\n",
          warningLine(ReplayWarning.LaterPutWins("later-put.txt"), Presentation.Plain) ==
            "warning: auto-resolved later-put.txt: later-put-wins\n",
          warningLine(ReplayWarning.NamespaceWins("a/b"), Presentation.Plain) ==
            "warning: auto-resolved a/b: namespace-wins\n",
          warningLine(ReplayWarning.PutWins("incompatible.txt"), Presentation.Plain) ==
            "warning: auto-resolved incompatible.txt: put-wins\n"
        )
      },
      test("terminal warning drops the 'warning: ' prefix (test 28)") {
        assertTrue(
          warningLine(ReplayWarning.LaterCreateWins("same"), Presentation.Terminal) ==
            s"$esc[33m\u26a0$esc[0m $esc[33mauto-resolved same: later-create-wins$esc[0m\n"
        )
      },
      test("plain error line wraps detail with 'snap: ' (tests 14/24)") {
        assertTrue(
          errorLine(SnapError.InvalidCommandOrArguments, Presentation.Plain) ==
            "snap: invalid command or arguments\n"
        )
      },
      test("terminal error wraps the whole plain line including 'snap: ' (test 28)") {
        assertTrue(
          errorLine(SnapError.InvalidCommandOrArguments, Presentation.Terminal) ==
            s"$esc[31m\u2717 snap: invalid command or arguments$esc[0m\n"
        )
      }
    ),
    suite("serve URL")(
      test("serve URL is always plain with the actual port (tests 12/28)") {
        assertTrue(serveUrlLine(8765) == "http://127.0.0.1:8765/repository.json\n") &&
        assertTrue(serveUrlLine(1) == "http://127.0.0.1:1/repository.json\n")
      }
    )
  )
}
