package snap

import zio.test.*

/** Tests for Jnu pure predicates (F-utf8b). */
object JnuSpec extends ZIOSpecDefault {

  def spec = suite("Jnu predicates")(
    suite("decodedNameNeedsReexec")(
      test("lossy and name contains U+FFFD → true") {
        assertTrue(Jnu.decodedNameNeedsReexec(true, "caf\uFFFD"))
      },
      test("lossy and name is plain ASCII → false") {
        assertTrue(!Jnu.decodedNameNeedsReexec(true, "cafe"))
      },
      test("not lossy and name contains U+FFFD → false") {
        assertTrue(!Jnu.decodedNameNeedsReexec(false, "caf\uFFFD"))
      },
      test("not lossy and name is plain ASCII → false") {
        assertTrue(!Jnu.decodedNameNeedsReexec(false, "cafe"))
      },
      test("lossy and name is empty → false") {
        assertTrue(!Jnu.decodedNameNeedsReexec(true, ""))
      },
      test("lossy and U+FFFD embedded in longer name → true") {
        assertTrue(Jnu.decodedNameNeedsReexec(true, "dir/\uFFFD/file.txt"))
      }
    ),
    suite("writeNeedsReexec")(
      test("lossy and one path contains non-ASCII → true") {
        assertTrue(Jnu.writeNeedsReexec(true, List("a/b", "caf\u00e9")))
      },
      test("lossy and all paths are ASCII → false") {
        assertTrue(!Jnu.writeNeedsReexec(true, List("a/b", "c")))
      },
      test("not lossy and path contains non-ASCII → false") {
        assertTrue(!Jnu.writeNeedsReexec(false, List("caf\u00e9")))
      },
      test("not lossy and all paths ASCII → false") {
        assertTrue(!Jnu.writeNeedsReexec(false, List("a/b", "c")))
      },
      test("lossy and empty list → false") {
        assertTrue(!Jnu.writeNeedsReexec(true, List.empty))
      },
      test("lossy and char just above 0x7F → true") {
        assertTrue(Jnu.writeNeedsReexec(true, List("a\u0080b")))
      },
      test("lossy and only 0x7F (DEL) → false") {
        assertTrue(!Jnu.writeNeedsReexec(true, List("a\u007Fb")))
      }
    )
  )
}
